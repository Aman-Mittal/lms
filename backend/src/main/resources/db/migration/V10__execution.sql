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

-- Trip execution, gate operations and weighbridge (vision document 3.6).
-- Where the plan meets a lorry.

-- ---------------------------------------------------------------------------
-- 3.6.1 The trip
-- ---------------------------------------------------------------------------

CREATE TABLE trip
(
    id                      UUID PRIMARY KEY,
    tenant_id               UUID        NOT NULL REFERENCES tenant (id) ON DELETE CASCADE,
    org_unit_id             UUID        NOT NULL REFERENCES org_unit (id) ON DELETE RESTRICT,
    load_id                 UUID        NOT NULL REFERENCES load_unit (id) ON DELETE RESTRICT,
    trip_no                 TEXT        NOT NULL,
    vendor_partner_id       UUID REFERENCES business_partner (id) ON DELETE RESTRICT,
    vehicle_id              UUID REFERENCES vehicle (id) ON DELETE RESTRICT,
    driver_id               UUID REFERENCES driver (id) ON DELETE RESTRICT,
    -- The eight states of 3.6.1, plus CANCELLED. Enforced in the aggregate as
    -- an explicit transition table and repeated here so the rule survives any
    -- code path that writes the row.
    status                  TEXT        NOT NULL DEFAULT 'PLANNED',
    origin_terminal_id      UUID        NOT NULL REFERENCES terminal (id) ON DELETE RESTRICT,
    destination_terminal_id UUID REFERENCES terminal (id) ON DELETE RESTRICT,
    planned_start_at        TIMESTAMPTZ,
    -- Timestamps per milestone rather than one "last changed at". The dwell
    -- between gate-in and gate-out is what detention is billed on (3.9.1), so
    -- each moment has to survive the next transition.
    gate_in_at              TIMESTAMPTZ,
    loaded_at               TIMESTAMPTZ,
    dispatched_at           TIMESTAMPTZ,
    arrived_at              TIMESTAMPTZ,
    completed_at            TIMESTAMPTZ,
    tare_weight_kg          NUMERIC(14, 3),
    gross_weight_kg         NUMERIC(14, 3),
    -- Gross less tare. Stored rather than computed on read because it is the
    -- number a dispute is argued over, and it must not change if somebody
    -- later corrects a reading.
    payload_weight_kg       NUMERIC(14, 3),
    version                 BIGINT,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT trip_no_unique UNIQUE (tenant_id, trip_no),
    -- One live trip per load. A second trip against the same load would mean
    -- two lorries dispatched for one set of freight.
    CONSTRAINT trip_load_unique UNIQUE (tenant_id, load_id),
    CONSTRAINT trip_status_valid CHECK (status IN
        ('PLANNED', 'ASSIGNED', 'AT_ORIGIN', 'LOADED', 'DISPATCHED',
         'IN_TRANSIT', 'AT_DESTINATION', 'COMPLETED', 'CANCELLED'))
);

CREATE INDEX trip_status_idx ON trip (tenant_id, status);
CREATE INDEX trip_vehicle_idx ON trip (tenant_id, vehicle_id, status);
-- Serves the telematics ping path, which asks "which trip is this vehicle on"
-- for every point received, and is therefore the hottest read in the platform.
CREATE INDEX trip_driver_idx ON trip (tenant_id, driver_id, status);

-- ---------------------------------------------------------------------------
-- Documents that must be aboard before a trip may leave
-- ---------------------------------------------------------------------------

-- Distinct from compliance_document in V4, which is about the *asset* -- a
-- vehicle's insurance, a driver's licence. These are about this journey: the
-- consignment note, the e-way bill, the delivery challan. Both are checked at
-- dispatch and neither substitutes for the other.
CREATE TABLE trip_document
(
    id            UUID PRIMARY KEY,
    tenant_id     UUID        NOT NULL REFERENCES tenant (id) ON DELETE CASCADE,
    trip_id       UUID        NOT NULL REFERENCES trip (id) ON DELETE CASCADE,
    document_type TEXT        NOT NULL,
    document_ref  TEXT        NOT NULL,
    -- No file storage in the MVP: a reference to the document, not the document.
    -- Object storage is a dependency the free tier cannot carry, and the rule
    -- being enforced is "is it accounted for", which a reference answers.
    issued_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    version       BIGINT,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT trip_document_type_unique UNIQUE (trip_id, document_type)
);

CREATE INDEX trip_document_trip_idx ON trip_document (tenant_id, trip_id);

-- ---------------------------------------------------------------------------
-- 3.6.2 Gate operations
-- ---------------------------------------------------------------------------

CREATE TABLE gate_event
(
    id          UUID PRIMARY KEY,
    tenant_id   UUID        NOT NULL REFERENCES tenant (id) ON DELETE CASCADE,
    trip_id     UUID        NOT NULL REFERENCES trip (id) ON DELETE CASCADE,
    terminal_id UUID        NOT NULL REFERENCES terminal (id) ON DELETE RESTRICT,
    event_type  TEXT        NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    remarks     TEXT,
    version     BIGINT,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT gate_event_type_valid CHECK (event_type IN ('GATE_IN', 'GATE_OUT'))
);

-- Append-only in practice: the gate log is the evidence in a detention
-- dispute, so events accumulate rather than being corrected in place.
CREATE INDEX gate_event_trip_idx ON gate_event (tenant_id, trip_id, occurred_at);

CREATE TABLE weighbridge_reading
(
    id           UUID PRIMARY KEY,
    tenant_id    UUID        NOT NULL REFERENCES tenant (id) ON DELETE CASCADE,
    trip_id      UUID        NOT NULL REFERENCES trip (id) ON DELETE CASCADE,
    terminal_id  UUID        NOT NULL REFERENCES terminal (id) ON DELETE RESTRICT,
    reading_type TEXT        NOT NULL,
    weight_kg    NUMERIC(14, 3) NOT NULL,
    recorded_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    version      BIGINT,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT weighbridge_reading_type_valid CHECK (reading_type IN ('TARE', 'GROSS')),
    CONSTRAINT weighbridge_reading_positive CHECK (weight_kg > 0),
    -- One tare and one gross per trip. A second reading of the same kind is a
    -- correction, and a correction that silently overwrote the first would
    -- destroy the evidence the payload figure rests on -- so it is refused, and
    -- a genuine re-weigh is a new trip-level decision.
    CONSTRAINT weighbridge_reading_once UNIQUE (trip_id, reading_type)
);

CREATE INDEX weighbridge_reading_trip_idx ON weighbridge_reading (tenant_id, trip_id);

-- ---------------------------------------------------------------------------
-- Row-level security
-- ---------------------------------------------------------------------------

DO
$$
    DECLARE
        t TEXT;
    BEGIN
        FOREACH t IN ARRAY ARRAY [
            'trip', 'trip_document', 'gate_event', 'weighbridge_reading'
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
