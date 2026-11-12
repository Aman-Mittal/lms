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

-- Yard gate register, gate passes, ordered trip stops and imported route
-- corridors (issue #223).
--
-- gate_event (V10) stays what it was: the append-only evidence of a trip's own
-- gate-in and gate-out, written by the trip's state machine. The register is a
-- different thing -- one row per visit of any vehicle to a site, trip or not,
-- closed by its exit -- so it is a table of its own, and a trip's gate-in,
-- dispatch and arrival keep it in step (see GateRegister).

-- ---------------------------------------------------------------------------
-- Gate passes
-- ---------------------------------------------------------------------------

CREATE TABLE gate_pass
(
    id              UUID PRIMARY KEY,
    tenant_id       UUID        NOT NULL REFERENCES tenant (id) ON DELETE CASCADE,
    -- The site is an organisational unit; a pass may be narrowed to one of its
    -- terminals. The pass is checked against the unit the vehicle enters.
    org_unit_id     UUID        NOT NULL REFERENCES org_unit (id) ON DELETE RESTRICT,
    terminal_id     UUID REFERENCES terminal (id) ON DELETE RESTRICT,
    pass_no         TEXT        NOT NULL,
    vehicle_id      UUID REFERENCES vehicle (id) ON DELETE RESTRICT,
    -- Upper case, no blanks: how a visitor's vehicle is recognised at the gate.
    registration_no TEXT        NOT NULL,
    trip_id         UUID REFERENCES trip (id) ON DELETE SET NULL,
    purpose         TEXT        NOT NULL,
    valid_from      TIMESTAMPTZ NOT NULL,
    valid_until     TIMESTAMPTZ NOT NULL,
    -- Expired is not a status: it is a pass whose window has closed, and the
    -- clock decides, not a job.
    status          TEXT        NOT NULL DEFAULT 'ISSUED',
    used_at         TIMESTAMPTZ,
    used_visit_id   UUID,
    cancelled_at    TIMESTAMPTZ,
    cancel_reason   TEXT,
    version         BIGINT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT gate_pass_no_unique UNIQUE (tenant_id, pass_no),
    CONSTRAINT gate_pass_status_valid CHECK (status IN ('ISSUED', 'USED', 'CANCELLED')),
    CONSTRAINT gate_pass_purpose_valid CHECK (purpose IN ('TRIP', 'VISITOR', 'MAINTENANCE', 'RETURN', 'OTHER')),
    CONSTRAINT gate_pass_window_ordered CHECK (valid_until > valid_from),
    CONSTRAINT gate_pass_no_bounded CHECK (btrim(pass_no) <> '' AND length(pass_no) <= 60),
    CONSTRAINT gate_pass_registration_bounded CHECK (btrim(registration_no) <> '' AND length(registration_no) <= 30),
    CONSTRAINT gate_pass_used_whole CHECK ((status = 'USED') = (used_at IS NOT NULL)),
    CONSTRAINT gate_pass_cancelled_whole CHECK (
        (status = 'CANCELLED') = (cancelled_at IS NOT NULL AND cancel_reason IS NOT NULL))
);

-- A vehicle's passes at a unit, newest window first: the check at the gate.
CREATE INDEX gate_pass_vehicle_idx ON gate_pass (tenant_id, registration_no, org_unit_id, valid_until DESC);
-- The pass list, newest first, keyset on (created_at, id).
CREATE INDEX gate_pass_list_idx ON gate_pass (tenant_id, created_at DESC, id DESC);
CREATE INDEX gate_pass_org_unit_ref_idx ON gate_pass (tenant_id, org_unit_id);
CREATE INDEX gate_pass_terminal_ref_idx ON gate_pass (tenant_id, terminal_id);
CREATE INDEX gate_pass_vehicle_ref_idx ON gate_pass (tenant_id, vehicle_id) WHERE vehicle_id IS NOT NULL;
CREATE INDEX gate_pass_trip_idx ON gate_pass (tenant_id, trip_id) WHERE trip_id IS NOT NULL;

-- ---------------------------------------------------------------------------
-- The gate register
-- ---------------------------------------------------------------------------

CREATE TABLE gate_visit
(
    id               UUID PRIMARY KEY,
    tenant_id        UUID        NOT NULL REFERENCES tenant (id) ON DELETE CASCADE,
    org_unit_id      UUID        NOT NULL REFERENCES org_unit (id) ON DELETE RESTRICT,
    terminal_id      UUID REFERENCES terminal (id) ON DELETE RESTRICT,
    vehicle_id       UUID REFERENCES vehicle (id) ON DELETE RESTRICT,
    -- Always present: a registered vehicle's number is copied in, a visitor's
    -- is typed. One open visit per number is what "the vehicle is inside" means.
    registration_no  TEXT        NOT NULL,
    driver_id        UUID REFERENCES driver (id) ON DELETE RESTRICT,
    driver_name      TEXT,
    purpose          TEXT        NOT NULL,
    trip_id          UUID REFERENCES trip (id) ON DELETE SET NULL,
    gate_pass_id     UUID REFERENCES gate_pass (id) ON DELETE SET NULL,
    status           TEXT        NOT NULL DEFAULT 'OPEN',
    entered_at       TIMESTAMPTZ NOT NULL,
    entry_seal_nos   TEXT[]      NOT NULL DEFAULT '{}',
    entry_weight_ref TEXT,
    entry_remarks    TEXT,
    exited_at        TIMESTAMPTZ,
    exit_seal_nos    TEXT[]      NOT NULL DEFAULT '{}',
    exit_weight_ref  TEXT,
    exit_remarks     TEXT,
    version          BIGINT,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT gate_visit_status_valid CHECK (status IN ('OPEN', 'CLOSED')),
    CONSTRAINT gate_visit_purpose_valid CHECK (purpose IN ('TRIP', 'VISITOR', 'MAINTENANCE', 'RETURN', 'OTHER')),
    CONSTRAINT gate_visit_registration_bounded CHECK (btrim(registration_no) <> '' AND length(registration_no) <= 30),
    CONSTRAINT gate_visit_closed_whole CHECK ((status = 'CLOSED') = (exited_at IS NOT NULL)),
    CONSTRAINT gate_visit_exit_after_entry CHECK (exited_at IS NULL OR exited_at >= entered_at)
);

-- The database's own refusal of a second entry while one is open: a vehicle is
-- inside once, whichever way the entries race.
CREATE UNIQUE INDEX gate_visit_one_open_idx ON gate_visit (tenant_id, registration_no) WHERE status = 'OPEN';
-- The register, newest entry first, keyset on (entered_at, id).
CREATE INDEX gate_visit_register_idx ON gate_visit (tenant_id, entered_at DESC, id DESC);
-- Who is inside now.
CREATE INDEX gate_visit_open_list_idx ON gate_visit (tenant_id, entered_at DESC, id DESC) WHERE status = 'OPEN';
CREATE INDEX gate_visit_vehicle_idx ON gate_visit (tenant_id, vehicle_id, entered_at DESC) WHERE vehicle_id IS NOT NULL;
CREATE INDEX gate_visit_trip_idx ON gate_visit (tenant_id, trip_id) WHERE trip_id IS NOT NULL;
CREATE INDEX gate_visit_org_unit_ref_idx ON gate_visit (tenant_id, org_unit_id);
CREATE INDEX gate_visit_terminal_ref_idx ON gate_visit (tenant_id, terminal_id);
CREATE INDEX gate_visit_driver_ref_idx ON gate_visit (tenant_id, driver_id) WHERE driver_id IS NOT NULL;
CREATE INDEX gate_visit_pass_ref_idx ON gate_visit (tenant_id, gate_pass_id) WHERE gate_pass_id IS NOT NULL;

-- ---------------------------------------------------------------------------
-- Ordered trip stops
-- ---------------------------------------------------------------------------

CREATE TABLE trip_stop
(
    id                 UUID PRIMARY KEY,
    tenant_id          UUID        NOT NULL REFERENCES tenant (id) ON DELETE CASCADE,
    trip_id            UUID        NOT NULL REFERENCES trip (id) ON DELETE CASCADE,
    seq                INTEGER     NOT NULL,
    kind               TEXT        NOT NULL,
    terminal_id        UUID        NOT NULL REFERENCES terminal (id) ON DELETE RESTRICT,
    planned_arrival_at TIMESTAMPTZ,
    status             TEXT        NOT NULL DEFAULT 'PLANNED',
    arrived_at         TIMESTAMPTZ,
    departed_at        TIMESTAMPTZ,
    completed_at       TIMESTAMPTZ,
    skipped_at         TIMESTAMPTZ,
    skip_reason        TEXT,
    -- Arrived while an earlier stop was still waiting: recorded and flagged,
    -- never refused (a vehicle goes where the road takes it).
    out_of_order       BOOLEAN     NOT NULL DEFAULT false,
    arrival_source     TEXT,
    departure_source   TEXT,
    version            BIGINT,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT trip_stop_seq_unique UNIQUE (trip_id, seq),
    CONSTRAINT trip_stop_seq_positive CHECK (seq > 0),
    CONSTRAINT trip_stop_kind_valid CHECK (kind IN ('PICKUP', 'DROP', 'CHECKPOINT')),
    CONSTRAINT trip_stop_status_valid CHECK (status IN ('PLANNED', 'ARRIVED', 'DEPARTED', 'COMPLETED', 'SKIPPED')),
    CONSTRAINT trip_stop_source_valid CHECK (
        (arrival_source IS NULL OR arrival_source IN ('MANUAL', 'GEOFENCE', 'TRIP'))
            AND (departure_source IS NULL OR departure_source IN ('MANUAL', 'GEOFENCE', 'TRIP'))),
    CONSTRAINT trip_stop_skipped_whole CHECK (
        (status = 'SKIPPED') = (skipped_at IS NOT NULL AND skip_reason IS NOT NULL)),
    CONSTRAINT trip_stop_arrived_whole CHECK (
        (status IN ('ARRIVED', 'DEPARTED', 'COMPLETED')) = (arrived_at IS NOT NULL))
);

CREATE INDEX trip_stop_trip_idx ON trip_stop (tenant_id, trip_id, seq);
CREATE INDEX trip_stop_terminal_ref_idx ON trip_stop (tenant_id, terminal_id);

-- ---------------------------------------------------------------------------
-- Imported route corridors
-- ---------------------------------------------------------------------------

-- The road a lane is expected to take, imported as a GeoJSON-order polyline: [[lon, lat], ...].
-- A trip's lane is its origin and destination terminal. Import only: the
-- platform draws no routes (DOCS/adr/0004), it measures against one it was given.
CREATE TABLE route_corridor
(
    id                      UUID PRIMARY KEY,
    tenant_id               UUID        NOT NULL REFERENCES tenant (id) ON DELETE CASCADE,
    origin_terminal_id      UUID        NOT NULL REFERENCES terminal (id) ON DELETE RESTRICT,
    destination_terminal_id UUID        NOT NULL REFERENCES terminal (id) ON DELETE RESTRICT,
    name                    TEXT        NOT NULL,
    points                  JSONB       NOT NULL,
    point_count             INTEGER     NOT NULL,
    -- How far off the polyline a vehicle may be; NULL takes the trip's own
    -- telematics.routeDeviation.corridorMetres setting.
    width_metres            NUMERIC(10, 2),
    version                 BIGINT,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT route_corridor_lane_unique UNIQUE (tenant_id, origin_terminal_id, destination_terminal_id),
    CONSTRAINT route_corridor_points_bounded CHECK (point_count BETWEEN 2 AND 5000),
    CONSTRAINT route_corridor_name_bounded CHECK (btrim(name) <> '' AND length(name) <= 120),
    CONSTRAINT route_corridor_width_positive CHECK (width_metres IS NULL OR width_metres > 0)
);

CREATE INDEX route_corridor_destination_ref_idx ON route_corridor (tenant_id, destination_terminal_id);

-- What a deviation was measured against.
ALTER TABLE route_deviation
    ADD COLUMN reference TEXT NOT NULL DEFAULT 'STRAIGHT_LINE',
    ADD CONSTRAINT route_deviation_reference_valid CHECK (reference IN ('STRAIGHT_LINE', 'CORRIDOR'));

-- ---------------------------------------------------------------------------
-- Control tower: a late arrival at a stop
-- ---------------------------------------------------------------------------

ALTER TABLE exception_ticket
    DROP CONSTRAINT exception_ticket_category_valid,
    DROP CONSTRAINT exception_ticket_source_valid,
    DROP CONSTRAINT exception_ticket_subject_type_valid;

ALTER TABLE exception_ticket
    ADD CONSTRAINT exception_ticket_category_valid CHECK (category IN (
        'ACCIDENT', 'THEFT', 'BREAKDOWN',
        'ROUTE_DEVIATION', 'EXTENDED_STOPPAGE', 'TEMPERATURE_EXCURSION',
        'ETA_DELAY', 'DOCUMENT_MISMATCH',
        'MISSED_CONNECTION', 'MILK_RUN_STOP_SKIPPED', 'MILK_RUN_STOP_LATE',
        'RECEIPT_VARIANCE', 'PICK_SHORTFALL', 'STOP_LATE')),
    ADD CONSTRAINT exception_ticket_source_valid CHECK (source IN (
        'MANUAL', 'ROUTE_DEVIATION',
        'MISSED_CONNECTION', 'MILK_RUN_STOP_SKIPPED', 'MILK_RUN_STOP_LATE',
        'RECEIPT_VARIANCE', 'PICK_SHORTFALL', 'STOPPAGE_DETECTED',
        'ETA_SLIP_DETECTED', 'TEMPERATURE_EXCURSION_DETECTED', 'STOP_LATE_DETECTED')),
    ADD CONSTRAINT exception_ticket_subject_type_valid CHECK (
        subject_type IN ('ITINERARY', 'CIRCUIT_RUN', 'ASN', 'OUTBOUND_ORDER', 'TRIP_STOP'));

-- ---------------------------------------------------------------------------
-- Permissions
-- ---------------------------------------------------------------------------

-- Working the gate (entries, exits, checking a pass) and issuing or cancelling
-- passes are separate rights: the desk that lets vehicles in is not always the
-- one that decides who may come.
INSERT INTO permission (id, code, resource, action)
VALUES (gen_random_uuid(), 'GATE_REGISTER', 'GATE', 'EXECUTE'),
       (gen_random_uuid(), 'GATE_PASS_MANAGE', 'GATE_PASS', 'UPDATE');

-- ---------------------------------------------------------------------------
-- Row-level security
-- ---------------------------------------------------------------------------

DO
$$
    DECLARE
        t TEXT;
    BEGIN
        FOREACH t IN ARRAY ARRAY [
            'gate_pass', 'gate_visit', 'trip_stop', 'route_corridor'
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
