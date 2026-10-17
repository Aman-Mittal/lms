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

-- Exception and control tower management (vision document 3.8).
--
-- An exception is a ticket about one trip: something went wrong on the road
-- and somebody has to own it until it is closed. Two rules from 3.8 shape the
-- tables more than anything else:
--
--   * the vehicle's position and the time are captured when the ticket is
--     raised -- not looked up later, when the lorry has moved on;
--   * a critical ticket (P1, P2) suspends settlement of the trip until a
--     manager closes it. Finance keeps its own record of that hold, fed by
--     events, so the rule holds without finance reading these tables.

-- ---------------------------------------------------------------------------
-- The ticket
-- ---------------------------------------------------------------------------

CREATE TABLE exception_ticket
(
    id                   UUID PRIMARY KEY,
    tenant_id            UUID        NOT NULL REFERENCES tenant (id) ON DELETE CASCADE,
    ticket_no            TEXT        NOT NULL,
    trip_id              UUID        NOT NULL REFERENCES trip (id) ON DELETE RESTRICT,
    -- Copied rather than joined for: a ticket is read on a board next to
    -- dozens of others, and the trip number is what a dispatcher says aloud.
    trip_no              TEXT        NOT NULL,
    vehicle_id           UUID,
    category             TEXT        NOT NULL,
    -- Derived from the category, never chosen. A severity somebody can pick
    -- is a severity that drifts towards whatever gets attention fastest.
    severity             TEXT        NOT NULL,
    status               TEXT        NOT NULL,
    description          TEXT        NOT NULL,

    -- How the ticket came to exist. MANUAL is a person; anything else is a
    -- detector, and source_key is what makes a redelivered detection a no-op.
    source               TEXT        NOT NULL,
    source_key           TEXT,

    -- 3.8: captured at the moment of raising. position_source says where the
    -- coordinates came from and position_at when they were true, because a
    -- telematics fix from three hours ago is not where the lorry is now and
    -- the ticket should say so rather than imply otherwise.
    lat                  NUMERIC(9, 6),
    lon                  NUMERIC(9, 6),
    position_source      TEXT        NOT NULL,
    position_at          TIMESTAMPTZ,

    -- Fixed at raising, from the severity. Stored so the hold a ticket placed
    -- is released by the same ticket even if the category table is ever
    -- re-graded.
    suspends_settlement  BOOLEAN     NOT NULL,

    raised_by            TEXT        NOT NULL,
    raised_at            TIMESTAMPTZ NOT NULL,
    assignee_user_id     UUID REFERENCES app_user (id) ON DELETE SET NULL,
    assigned_at          TIMESTAMPTZ,
    resolution           TEXT,
    resolved_by          TEXT,
    resolved_at          TIMESTAMPTZ,
    closed_by            TEXT,
    closed_at            TIMESTAMPTZ,

    version              BIGINT,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT exception_ticket_no_unique UNIQUE (tenant_id, ticket_no),
    CONSTRAINT exception_ticket_category_valid CHECK (category IN (
        'ACCIDENT', 'THEFT', 'BREAKDOWN',
        'ROUTE_DEVIATION', 'EXTENDED_STOPPAGE', 'TEMPERATURE_EXCURSION',
        'ETA_DELAY', 'DOCUMENT_MISMATCH')),
    CONSTRAINT exception_ticket_severity_valid CHECK (severity IN ('P1', 'P2', 'P3')),
    CONSTRAINT exception_ticket_status_valid CHECK (status IN (
        'RAISED', 'ASSIGNED', 'UNDER_INVESTIGATION', 'RESOLVED', 'CLOSED')),
    CONSTRAINT exception_ticket_source_valid CHECK (source IN ('MANUAL', 'ROUTE_DEVIATION')),
    CONSTRAINT exception_ticket_position_source_valid CHECK (
        position_source IN ('TELEMATICS', 'REPORTED', 'DETECTOR', 'NONE')),
    -- Half a coordinate is worse than none: it plots somewhere plausible and
    -- wrong.
    CONSTRAINT exception_ticket_position_whole CHECK ((lat IS NULL) = (lon IS NULL)),
    CONSTRAINT exception_ticket_position_explained CHECK (
        (position_source = 'NONE') = (lat IS NULL)),
    -- The 3.8 rule, held by the database as well as the service: only a
    -- critical ticket may hold up money, and every critical ticket does.
    CONSTRAINT exception_ticket_hold_matches_severity CHECK (
        suspends_settlement = (severity IN ('P1', 'P2'))),
    CONSTRAINT exception_ticket_resolution_explained CHECK (
        status NOT IN ('RESOLVED', 'CLOSED') OR resolution IS NOT NULL)
);

-- A detector that fires twice for the same occurrence raises one ticket.
CREATE UNIQUE INDEX exception_ticket_source_key_idx
    ON exception_ticket (tenant_id, source_key)
    WHERE source_key IS NOT NULL;

-- The board: newest first, optionally narrowed to one status. Both shapes end
-- in (raised_at, id) so the keyset cursor rides the index without a sort.
CREATE INDEX exception_ticket_board_idx
    ON exception_ticket (tenant_id, raised_at DESC, id DESC);
CREATE INDEX exception_ticket_status_board_idx
    ON exception_ticket (tenant_id, status, raised_at DESC, id DESC);

CREATE INDEX exception_ticket_trip_idx ON exception_ticket (tenant_id, trip_id);

-- ---------------------------------------------------------------------------
-- What happened to it
-- ---------------------------------------------------------------------------

-- Append-only. The ticket row says where things stand; this says how they got
-- there, which is what a post-incident review actually reads.
CREATE TABLE exception_activity
(
    id           UUID PRIMARY KEY,
    tenant_id    UUID        NOT NULL REFERENCES tenant (id) ON DELETE CASCADE,
    exception_id UUID        NOT NULL REFERENCES exception_ticket (id) ON DELETE CASCADE,
    action       TEXT        NOT NULL,
    from_status  TEXT,
    to_status    TEXT        NOT NULL,
    note         TEXT,
    actor        TEXT        NOT NULL,
    occurred_at  TIMESTAMPTZ NOT NULL,
    version      BIGINT,
    CONSTRAINT exception_activity_action_valid CHECK (action IN (
        'RAISED', 'ASSIGNED', 'INVESTIGATION_STARTED', 'RESOLVED', 'REOPENED', 'CLOSED'))
);

CREATE INDEX exception_activity_ticket_idx
    ON exception_activity (tenant_id, exception_id, occurred_at);

-- ---------------------------------------------------------------------------
-- Finance's side of the rule
-- ---------------------------------------------------------------------------

-- Owned by finance, written only from control tower events. Keyed on the
-- ticket, not the trip: two critical tickets on one trip are two holds, and
-- closing the first must not release the money while the second is still open.
--
-- No foreign key to exception_ticket, deliberately. The hold is finance's
-- record of what it was told, and it must be writable in whichever order the
-- outbox delivers the raise and the close -- see SettlementHolds.
CREATE TABLE settlement_hold
(
    tenant_id    UUID        NOT NULL REFERENCES tenant (id) ON DELETE CASCADE,
    exception_id UUID        NOT NULL,
    trip_id      UUID        NOT NULL,
    ticket_no    TEXT        NOT NULL,
    placed_at    TIMESTAMPTZ NOT NULL,
    released_at  TIMESTAMPTZ,
    PRIMARY KEY (tenant_id, exception_id)
);

-- Asked on every match and every dispute settlement: is anything still
-- holding this trip? Partial, because released holds are history.
CREATE INDEX settlement_hold_open_idx
    ON settlement_hold (tenant_id, trip_id)
    WHERE released_at IS NULL;

-- ---------------------------------------------------------------------------
-- Permission vocabulary
-- ---------------------------------------------------------------------------

-- Closing is its own permission. It is the act that releases held money, so
-- it belongs to whoever is accountable for that, not to everyone who can work
-- a ticket.
INSERT INTO permission (id, code, resource, action)
VALUES (gen_random_uuid(), 'EXCEPTION_READ', 'EXCEPTION', 'READ'),
       (gen_random_uuid(), 'EXCEPTION_RAISE', 'EXCEPTION', 'CREATE'),
       (gen_random_uuid(), 'EXCEPTION_MANAGE', 'EXCEPTION', 'UPDATE'),
       (gen_random_uuid(), 'EXCEPTION_CLOSE', 'EXCEPTION', 'EXECUTE');

-- ---------------------------------------------------------------------------
-- Row-level security
-- ---------------------------------------------------------------------------

DO
$$
    DECLARE
        t TEXT;
    BEGIN
        FOREACH t IN ARRAY ARRAY [
            'exception_ticket', 'exception_activity', 'settlement_hold'
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
