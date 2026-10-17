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

-- Multimodal itineraries: first mile by road, the trunk by rail, last mile by
-- road (vision document 3.4).
--
-- An itinerary is a shipment's route as an ordered list of legs. A ROAD leg is
-- an existing trip -- execution already runs it, and this module only follows
-- it through events. A RAIL leg is a booking for a rake of wagons or a number
-- of containers, and rail is not tracked by GPS: it moves by terminal events
-- (placed, loaded, departed, arrived, released) reported over the API.
--
-- Railheads are ordinary terminals. A leg starts and ends at one, and the
-- itinerary must be contiguous: each leg starts where the previous one ended.

-- ---------------------------------------------------------------------------
-- The itinerary
-- ---------------------------------------------------------------------------

CREATE TABLE itinerary
(
    id            UUID PRIMARY KEY,
    tenant_id     UUID        NOT NULL REFERENCES tenant (id) ON DELETE CASCADE,
    itinerary_no  TEXT        NOT NULL,
    -- The customer's or the order's reference for the goods. Free text: the
    -- shipment may not originate as an order in this platform at all.
    shipment_ref  TEXT        NOT NULL,
    -- One currency for the whole itinerary, so the per-leg costs add up to a
    -- number that means something.
    currency      TEXT        NOT NULL DEFAULT 'INR',
    created_by    TEXT        NOT NULL,
    version       BIGINT,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT itinerary_no_unique UNIQUE (tenant_id, itinerary_no)
);

-- The listing: newest first, keyset on (created_at, id).
CREATE INDEX itinerary_listing_idx ON itinerary (tenant_id, created_at DESC, id DESC);

-- ---------------------------------------------------------------------------
-- Its legs
-- ---------------------------------------------------------------------------

-- Each leg is its own aggregate. Legs progress independently -- a rail
-- milestone and a trip completion can land at the same moment -- and a single
-- itinerary aggregate would turn every such pair into an optimistic-lock
-- failure for no gain; the hand-off rule reads the predecessor, it does not
-- need to own it.
CREATE TABLE itinerary_leg
(
    id                      UUID PRIMARY KEY,
    tenant_id               UUID           NOT NULL REFERENCES tenant (id) ON DELETE CASCADE,
    itinerary_id            UUID           NOT NULL REFERENCES itinerary (id) ON DELETE CASCADE,
    seq                     INT            NOT NULL,
    mode                    TEXT           NOT NULL,
    status                  TEXT           NOT NULL,
    from_terminal_id        UUID           NOT NULL REFERENCES terminal (id) ON DELETE RESTRICT,
    to_terminal_id          UUID           NOT NULL REFERENCES terminal (id) ON DELETE RESTRICT,
    planned_departure_at    TIMESTAMPTZ    NOT NULL,
    planned_arrival_at      TIMESTAMPTZ    NOT NULL,
    -- Time needed at this leg's origin to transfer the goods off the previous
    -- leg -- unloading the lorry, staging at the railhead. The leg may not
    -- start before the predecessor's arrival plus this.
    transfer_dwell_minutes  INT            NOT NULL DEFAULT 0,

    -- ROAD: the trip that runs it.
    trip_id                 UUID REFERENCES trip (id) ON DELETE RESTRICT,
    -- RAIL: what was booked.
    unit_type               TEXT,
    unit_count              INT,
    booking_ref             TEXT,
    train_no                TEXT,

    -- Rated per leg, and each leg's carrier is paid separately.
    carrier_partner_id      UUID           NOT NULL REFERENCES business_partner (id) ON DELETE RESTRICT,
    cost_amount             NUMERIC(14, 2) NOT NULL,

    -- Milestones. ROAD legs only ever fill departed_at and arrived_at, from
    -- the trip's dispatch and proof of delivery.
    placed_at               TIMESTAMPTZ,
    loaded_at               TIMESTAMPTZ,
    departed_at             TIMESTAMPTZ,
    arrived_at              TIMESTAMPTZ,
    released_at             TIMESTAMPTZ,

    -- A road leg's dispatch is execution's decision and cannot be refused
    -- from here; when it breaks the hand-off rule, that is recorded instead.
    started_early           BOOLEAN        NOT NULL DEFAULT false,
    -- Set once, when the predecessor arrived too late for the planned start.
    connection_missed_at    TIMESTAMPTZ,
    earliest_start_at       TIMESTAMPTZ,

    version                 BIGINT,
    created_at              TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ    NOT NULL DEFAULT now(),

    CONSTRAINT itinerary_leg_mode_valid CHECK (mode IN ('ROAD', 'RAIL')),
    CONSTRAINT itinerary_leg_status_valid CHECK (status IN (
        'PLANNED', 'PLACED', 'LOADED', 'DEPARTED', 'ARRIVED', 'RELEASED')),
    CONSTRAINT itinerary_leg_seq_positive CHECK (seq >= 1),
    CONSTRAINT itinerary_leg_window_valid CHECK (planned_arrival_at > planned_departure_at),
    CONSTRAINT itinerary_leg_dwell_valid CHECK (transfer_dwell_minutes >= 0),
    CONSTRAINT itinerary_leg_cost_valid CHECK (cost_amount >= 0),
    CONSTRAINT itinerary_leg_terminals_differ CHECK (from_terminal_id <> to_terminal_id),
    -- The shape of each mode, held by the database as well as the service.
    CONSTRAINT itinerary_leg_road_shape CHECK (
        mode <> 'ROAD' OR (trip_id IS NOT NULL AND booking_ref IS NULL
                           AND status IN ('PLANNED', 'DEPARTED', 'ARRIVED'))),
    CONSTRAINT itinerary_leg_rail_shape CHECK (
        mode <> 'RAIL' OR (trip_id IS NULL AND booking_ref IS NOT NULL
                           AND unit_type IN ('WAGON', 'CONTAINER') AND unit_count > 0))
);

CREATE UNIQUE INDEX itinerary_leg_seq_idx ON itinerary_leg (tenant_id, itinerary_id, seq);

-- How a trip event finds its leg. Unique: a trip runs at most one leg of one
-- itinerary, or a single proof of delivery would complete two.
CREATE UNIQUE INDEX itinerary_leg_trip_idx ON itinerary_leg (tenant_id, trip_id)
    WHERE trip_id IS NOT NULL;

-- ---------------------------------------------------------------------------
-- Permission vocabulary
-- ---------------------------------------------------------------------------

-- Planning an itinerary commits money to carriers; reporting a terminal event
-- is a yard clerk's job. Different people, different permissions.
INSERT INTO permission (id, code, resource, action)
VALUES (gen_random_uuid(), 'MULTIMODAL_READ', 'MULTIMODAL', 'READ'),
       (gen_random_uuid(), 'MULTIMODAL_PLAN', 'MULTIMODAL', 'CREATE'),
       (gen_random_uuid(), 'MULTIMODAL_TRACK', 'MULTIMODAL', 'UPDATE');

-- ---------------------------------------------------------------------------
-- Row-level security
-- ---------------------------------------------------------------------------

DO
$$
    DECLARE
        t TEXT;
    BEGIN
        FOREACH t IN ARRAY ARRAY [
            'itinerary', 'itinerary_leg'
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
