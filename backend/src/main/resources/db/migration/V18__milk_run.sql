--
-- Copyright 2026 Aman Mittal
--
-- Licensed under the Apache License, Version 2.0 (the "License");
-- you may not use this file except in compliance with the License.
-- You may obtain a copy of the License at
--
--     http://www.apache.org/licenses/LICENSE-2.0
--
-- Unless required by applicable law or agreed to in writing, software
-- distributed under the License is distributed on an "AS IS" BASIS,
-- WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
-- See the License for the specific language governing permissions and
-- limitations under the License.
--

-- Milk runs: scheduled multi-stop pickup circuits for inbound logistics.
--
-- A milk run is a fixed circuit that collects from several suppliers in
-- sequence, each inside a time window, and delivers to a plant. Two shapes of
-- data follow from that:
--
--   * the *route* is a plan -- an ordered list of supplier stops plus the
--     schedule and the circuit's price -- and it is versioned the way a rate
--     card is: a change publishes a new version from a date and closes the
--     incumbent the day before, so "what was the plan on the 3rd" stays
--     answerable;
--   * the *circuit run* is one day's execution of one route, copied from the
--     version in force on that day. Copied, not referenced, because a run that
--     changed shape when somebody edited next month's plan would be a run
--     nobody could reconcile.

-- ---------------------------------------------------------------------------
-- The plan
-- ---------------------------------------------------------------------------

CREATE TABLE milk_run_route
(
    id                 UUID PRIMARY KEY,
    tenant_id          UUID           NOT NULL REFERENCES tenant (id) ON DELETE CASCADE,
    -- The route's identity across versions. Runs are keyed on it, not on the
    -- version id, so publishing a new version cannot generate a second run for
    -- a day that already has one.
    code               TEXT           NOT NULL,
    name               TEXT           NOT NULL,
    plant_terminal_id  UUID           NOT NULL REFERENCES terminal (id) ON DELETE RESTRICT,
    effective_from     DATE           NOT NULL,
    -- Inclusive. NULL is the version currently in force.
    effective_to       DATE,
    -- Canonical, Monday first: 'MON,WED,FRI'.
    days_of_week       TEXT           NOT NULL,
    departure_time     TIME           NOT NULL,
    -- The departure is a local time at the plant. Stored as a zone, not an
    -- offset, so a circuit leaving at 06:30 still leaves at 06:30 after a
    -- daylight-saving change.
    time_zone          TEXT           NOT NULL,
    run_rate           NUMERIC(14, 2) NOT NULL,
    per_stop_fee       NUMERIC(14, 2) NOT NULL,
    currency           TEXT           NOT NULL DEFAULT 'INR',
    version            BIGINT,
    created_at         TIMESTAMPTZ    NOT NULL DEFAULT now(),
    CONSTRAINT milk_run_route_period_sane CHECK (effective_to IS NULL OR effective_to >= effective_from),
    CONSTRAINT milk_run_route_days_valid CHECK (
        days_of_week ~ '^(MON|TUE|WED|THU|FRI|SAT|SUN)(,(MON|TUE|WED|THU|FRI|SAT|SUN))*$'),
    CONSTRAINT milk_run_route_rates_non_negative CHECK (run_rate >= 0 AND per_stop_fee >= 0)
);

-- One version in force per route. The publishing service closes the
-- incumbent as it opens the successor; this is what makes two open versions
-- impossible rather than merely unlikely.
CREATE UNIQUE INDEX milk_run_route_open_version_idx
    ON milk_run_route (tenant_id, code)
    WHERE effective_to IS NULL;

-- The version in force on a date, and the generator's window over every
-- route. effective_from descending lets a per-route lookup stop at the first
-- row.
CREATE INDEX milk_run_route_lookup_idx
    ON milk_run_route (tenant_id, code, effective_from DESC);

CREATE TABLE milk_run_route_stop
(
    id                UUID PRIMARY KEY,
    tenant_id         UUID    NOT NULL REFERENCES tenant (id) ON DELETE CASCADE,
    route_id          UUID    NOT NULL REFERENCES milk_run_route (id) ON DELETE CASCADE,
    sequence          INTEGER NOT NULL,
    terminal_id       UUID    NOT NULL REFERENCES terminal (id) ON DELETE RESTRICT,
    -- The pickup window, in minutes after the circuit's departure. Relative
    -- rather than clock times, so one route serves every day it runs and the
    -- windows move with the departure if the departure moves.
    window_open_min   INTEGER NOT NULL,
    window_close_min  INTEGER NOT NULL,
    planned_dwell_min INTEGER NOT NULL,
    version           BIGINT,
    CONSTRAINT milk_run_route_stop_sequence_unique UNIQUE (tenant_id, route_id, sequence),
    CONSTRAINT milk_run_route_stop_window_sane CHECK (
        window_open_min >= 0 AND window_close_min >= window_open_min AND planned_dwell_min >= 0)
);

-- ---------------------------------------------------------------------------
-- The execution
-- ---------------------------------------------------------------------------

CREATE TABLE circuit_run
(
    id                 UUID PRIMARY KEY,
    tenant_id          UUID           NOT NULL REFERENCES tenant (id) ON DELETE CASCADE,
    route_id           UUID           NOT NULL REFERENCES milk_run_route (id) ON DELETE RESTRICT,
    route_code         TEXT           NOT NULL,
    run_date           DATE           NOT NULL,
    planned_departure  TIMESTAMPTZ    NOT NULL,
    plant_terminal_id  UUID           NOT NULL REFERENCES terminal (id) ON DELETE RESTRICT,
    status             TEXT           NOT NULL,
    -- The execution trip that carries the circuit. Telematics reports
    -- geofence crossings per trip, so this is how a crossing finds its run.
    trip_id            UUID REFERENCES trip (id) ON DELETE RESTRICT,
    trip_no            TEXT,
    started_at         TIMESTAMPTZ,
    completed_at       TIMESTAMPTZ,
    completion_source  TEXT,
    -- Priced with the version in force on the run date, copied here so a
    -- later rate change does not reprice a circuit already run.
    run_rate           NUMERIC(14, 2) NOT NULL,
    per_stop_fee       NUMERIC(14, 2) NOT NULL,
    currency           TEXT           NOT NULL,
    stops_visited      INTEGER,
    stops_skipped      INTEGER,
    charge_amount      NUMERIC(14, 2),
    version            BIGINT,
    created_at         TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ    NOT NULL DEFAULT now(),
    CONSTRAINT circuit_run_status_valid CHECK (status IN ('PLANNED', 'IN_PROGRESS', 'COMPLETED')),
    CONSTRAINT circuit_run_completion_source_valid CHECK (
        completion_source IS NULL OR completion_source IN ('GEOFENCE', 'MANUAL')),
    CONSTRAINT circuit_run_started_has_trip CHECK (status = 'PLANNED' OR trip_id IS NOT NULL),
    CONSTRAINT circuit_run_completed_is_priced CHECK (
        status <> 'COMPLETED' OR (charge_amount IS NOT NULL AND completed_at IS NOT NULL))
);

-- The idempotency of generation: one run per route per day, however many
-- times the generator wakes up. The generator inserts with ON CONFLICT DO
-- NOTHING against exactly this key. Date first, so the same index serves the
-- day-ordered listing with its keyset cursor.
CREATE UNIQUE INDEX circuit_run_route_day_idx
    ON circuit_run (tenant_id, run_date, route_code);

-- A geofence crossing arrives with a trip id and nothing else. One trip
-- carries at most one circuit.
CREATE UNIQUE INDEX circuit_run_trip_idx
    ON circuit_run (tenant_id, trip_id)
    WHERE trip_id IS NOT NULL;

CREATE TABLE circuit_run_stop
(
    id                UUID PRIMARY KEY,
    tenant_id         UUID           NOT NULL REFERENCES tenant (id) ON DELETE CASCADE,
    run_id            UUID           NOT NULL REFERENCES circuit_run (id) ON DELETE CASCADE,
    sequence          INTEGER        NOT NULL,
    terminal_id       UUID           NOT NULL REFERENCES terminal (id) ON DELETE RESTRICT,
    -- Absolute instants, resolved from the route's relative window when the
    -- run is generated.
    window_opens_at   TIMESTAMPTZ    NOT NULL,
    window_closes_at  TIMESTAMPTZ    NOT NULL,
    planned_dwell_min INTEGER        NOT NULL,
    status            TEXT           NOT NULL,
    arrived_at        TIMESTAMPTZ,
    departed_at       TIMESTAMPTZ,
    arrival_source    TEXT,
    -- Zero when on time, positive when the window had closed. NULL until
    -- the stop is reached.
    minutes_late      INTEGER,
    picked_up_kg      NUMERIC(14, 3),
    skip_reason       TEXT,
    version           BIGINT,
    updated_at        TIMESTAMPTZ    NOT NULL DEFAULT now(),
    CONSTRAINT circuit_run_stop_sequence_unique UNIQUE (tenant_id, run_id, sequence),
    CONSTRAINT circuit_run_stop_status_valid CHECK (
        status IN ('PENDING', 'ARRIVED', 'DEPARTED', 'SKIPPED')),
    CONSTRAINT circuit_run_stop_arrival_source_valid CHECK (
        arrival_source IS NULL OR arrival_source IN ('GEOFENCE', 'MANUAL')),
    CONSTRAINT circuit_run_stop_reached_has_arrival CHECK (
        status NOT IN ('ARRIVED', 'DEPARTED') OR arrived_at IS NOT NULL),
    CONSTRAINT circuit_run_stop_departed_after_arrival CHECK (
        departed_at IS NULL OR departed_at >= arrived_at),
    CONSTRAINT circuit_run_stop_skip_explained CHECK (status <> 'SKIPPED' OR skip_reason IS NOT NULL),
    CONSTRAINT circuit_run_stop_pickup_non_negative CHECK (picked_up_kg IS NULL OR picked_up_kg >= 0)
);

-- ---------------------------------------------------------------------------
-- Permission vocabulary
-- ---------------------------------------------------------------------------

-- Planning a circuit and running one are different jobs: the plan carries the
-- price, so whoever records a stop on the road should not also be able to
-- change what the circuit pays.
INSERT INTO permission (id, code, resource, action)
VALUES (gen_random_uuid(), 'MILKRUN_READ', 'MILKRUN', 'READ'),
       (gen_random_uuid(), 'MILKRUN_PLAN', 'MILKRUN', 'CREATE'),
       (gen_random_uuid(), 'MILKRUN_EXECUTE', 'MILKRUN', 'EXECUTE');

-- ---------------------------------------------------------------------------
-- Row-level security
-- ---------------------------------------------------------------------------

DO
$$
    DECLARE
        t TEXT;
    BEGIN
        FOREACH t IN ARRAY ARRAY [
            'milk_run_route', 'milk_run_route_stop', 'circuit_run', 'circuit_run_stop'
            ]
            LOOP
                EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
                EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', t);
                EXECUTE format(
                        'CREATE POLICY %I ON %I USING (tenant_id = app_current_tenant()) '
                            || 'WITH CHECK (tenant_id = app_current_tenant())',
                        t || '_isolation', t);
            END LOOP;
    END
$$;
