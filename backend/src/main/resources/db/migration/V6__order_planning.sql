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

-- Demand and planning (vision document 3.3 and 3.4): commercial demand in,
-- executable physical loads out.
--
-- Every aggregate root carries `version` for the reason set out in V4: Spring
-- Data JDBC would otherwise treat a client-assigned @Id as an existing row and
-- turn the insert into an UPDATE that matches nothing.

-- ---------------------------------------------------------------------------
-- 3.3 Demand: orders and their line items
-- ---------------------------------------------------------------------------

CREATE TABLE sales_order
(
    id                  UUID PRIMARY KEY,
    tenant_id           UUID        NOT NULL REFERENCES tenant (id) ON DELETE CASCADE,
    org_unit_id         UUID        NOT NULL REFERENCES org_unit (id) ON DELETE RESTRICT,
    customer_partner_id UUID        NOT NULL REFERENCES business_partner (id) ON DELETE RESTRICT,
    order_no            TEXT        NOT NULL,
    -- DRAFT -> VALIDATED -> PARTIALLY_PLANNED -> FULLY_PLANNED -> FULFILLED,
    -- with CANCELLED reachable from anything not yet fulfilled.
    status              TEXT        NOT NULL DEFAULT 'DRAFT',
    origin_terminal_id  UUID REFERENCES terminal (id) ON DELETE RESTRICT,
    requested_pickup_at TIMESTAMPTZ,
    requested_delivery_at TIMESTAMPTZ,
    -- How the demand arrived: manual entry, bulk upload, or an ERP push.
    source              TEXT        NOT NULL DEFAULT 'MANUAL',
    version             BIGINT,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT sales_order_no_unique UNIQUE (tenant_id, order_no),
    CONSTRAINT sales_order_status_valid CHECK (status IN
        ('DRAFT', 'VALIDATED', 'PARTIALLY_PLANNED', 'FULLY_PLANNED', 'FULFILLED', 'CANCELLED')),
    CONSTRAINT sales_order_source_valid CHECK (source IN ('MANUAL', 'BULK_UPLOAD', 'API'))
);

CREATE INDEX sales_order_status_idx ON sales_order (tenant_id, status);
CREATE INDEX sales_order_customer_idx ON sales_order (tenant_id, customer_partner_id, status);

CREATE TABLE order_line
(
    id                    UUID PRIMARY KEY,
    tenant_id             UUID        NOT NULL REFERENCES tenant (id) ON DELETE CASCADE,
    order_id              UUID        NOT NULL REFERENCES sales_order (id) ON DELETE CASCADE,
    line_no               INT         NOT NULL,
    material_code         TEXT,
    material_description  TEXT        NOT NULL,
    -- Drives the incompatibility matrix of 3.3: FOOD must not travel with
    -- TOXIC, and so on.
    material_class        TEXT        NOT NULL DEFAULT 'GENERAL',
    -- A UN number marks the line as dangerous goods and forces a certified
    -- vehicle and an endorsed driver.
    hazmat_un_code        TEXT,
    quantity              NUMERIC(14, 3) NOT NULL,
    uom                   TEXT        NOT NULL DEFAULT 'EA',
    dead_weight_kg        NUMERIC(14, 3) NOT NULL,
    length_m              NUMERIC(8, 3),
    width_m               NUMERIC(8, 3),
    height_m              NUMERIC(8, 3),
    -- Carrier-specific constant used to convert volume into a billable weight.
    -- Stored per line because it is negotiated per contract and must not shift
    -- retrospectively when a default changes.
    volumetric_divisor    NUMERIC(10, 2),
    consignee_partner_id  UUID        NOT NULL REFERENCES business_partner (id) ON DELETE RESTRICT,
    destination_terminal_id UUID      NOT NULL REFERENCES terminal (id) ON DELETE RESTRICT,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT order_line_no_unique UNIQUE (order_id, line_no),
    CONSTRAINT order_line_quantity_positive CHECK (quantity > 0),
    CONSTRAINT order_line_weight_positive CHECK (dead_weight_kg > 0),
    CONSTRAINT order_line_class_valid CHECK (material_class IN
        ('GENERAL', 'FOOD', 'PHARMA', 'CHEMICAL', 'TOXIC', 'FLAMMABLE', 'FRAGILE', 'TEMPERATURE_CONTROLLED'))
);

CREATE INDEX order_line_order_idx ON order_line (tenant_id, order_id);
-- Consignment generation groups by exactly this pair, so it is the index that
-- serves planning's hottest read.
CREATE INDEX order_line_grouping_idx
    ON order_line (tenant_id, consignee_partner_id, destination_terminal_id);

-- ---------------------------------------------------------------------------
-- 3.4.1 Consignments
-- ---------------------------------------------------------------------------

CREATE TABLE consignment
(
    id                      UUID PRIMARY KEY,
    tenant_id               UUID        NOT NULL REFERENCES tenant (id) ON DELETE CASCADE,
    order_id                UUID        NOT NULL REFERENCES sales_order (id) ON DELETE RESTRICT,
    -- The legally binding reference of 3.4.1: a Lorry Receipt or Air Waybill
    -- number. Unique per tenant and never reused.
    tracking_ref            TEXT        NOT NULL,
    consignee_partner_id    UUID        NOT NULL REFERENCES business_partner (id) ON DELETE RESTRICT,
    destination_terminal_id UUID        NOT NULL REFERENCES terminal (id) ON DELETE RESTRICT,
    total_dead_weight_kg    NUMERIC(14, 3) NOT NULL,
    total_volume_m3         NUMERIC(14, 3) NOT NULL,
    -- max(dead weight, volumetric weight), which is what actually gets billed.
    chargeable_weight_kg    NUMERIC(14, 3) NOT NULL,
    contains_hazmat         BOOLEAN     NOT NULL DEFAULT false,
    status                  TEXT        NOT NULL DEFAULT 'PLANNED',
    version                 BIGINT,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT consignment_ref_unique UNIQUE (tenant_id, tracking_ref),
    CONSTRAINT consignment_status_valid CHECK (status IN
        ('PLANNED', 'ASSIGNED_TO_LOAD', 'IN_TRANSIT', 'DELIVERED', 'CANCELLED'))
);

CREATE INDEX consignment_order_idx ON consignment (tenant_id, order_id);
CREATE INDEX consignment_status_idx ON consignment (tenant_id, status);

CREATE TABLE consignment_line
(
    tenant_id      UUID NOT NULL REFERENCES tenant (id) ON DELETE CASCADE,
    consignment_id UUID NOT NULL REFERENCES consignment (id) ON DELETE CASCADE,
    order_line_id  UUID NOT NULL REFERENCES order_line (id) ON DELETE RESTRICT,
    PRIMARY KEY (consignment_id, order_line_id)
);

CREATE INDEX consignment_line_order_line_idx ON consignment_line (tenant_id, order_line_id);

-- ---------------------------------------------------------------------------
-- 3.4.2 Loads
-- ---------------------------------------------------------------------------

CREATE TABLE load_unit
(
    id                   UUID PRIMARY KEY,
    tenant_id            UUID        NOT NULL REFERENCES tenant (id) ON DELETE CASCADE,
    org_unit_id          UUID        NOT NULL REFERENCES org_unit (id) ON DELETE RESTRICT,
    load_no              TEXT        NOT NULL,
    origin_terminal_id   UUID        NOT NULL REFERENCES terminal (id) ON DELETE RESTRICT,
    vehicle_type         TEXT        NOT NULL,
    -- Capacities copied from the vehicle type at build time rather than looked
    -- up later. A load planned against yesterday's fleet definition must stay
    -- judged against yesterday's numbers.
    capacity_weight_kg   NUMERIC(14, 3) NOT NULL,
    capacity_volume_m3   NUMERIC(14, 3) NOT NULL,
    planned_weight_kg    NUMERIC(14, 3) NOT NULL DEFAULT 0,
    planned_volume_m3    NUMERIC(14, 3) NOT NULL DEFAULT 0,
    requires_hazmat      BOOLEAN     NOT NULL DEFAULT false,
    status               TEXT        NOT NULL DEFAULT 'DRAFT',
    version              BIGINT,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT load_unit_no_unique UNIQUE (tenant_id, load_no),
    CONSTRAINT load_unit_status_valid CHECK (status IN
        ('DRAFT', 'PLANNED', 'AWARDED', 'DISPATCHED', 'COMPLETED', 'CANCELLED')),
    -- The 3.4.2 hard stop, enforced by the database as well as the domain.
    -- Application code is where the useful error message comes from; this is
    -- what makes the rule true regardless of which code path wrote the row.
    CONSTRAINT load_unit_within_weight CHECK (planned_weight_kg <= capacity_weight_kg),
    CONSTRAINT load_unit_within_volume CHECK (planned_volume_m3 <= capacity_volume_m3)
);

CREATE INDEX load_unit_status_idx ON load_unit (tenant_id, status);
CREATE INDEX load_unit_origin_idx ON load_unit (tenant_id, origin_terminal_id, status);

CREATE TABLE load_consignment
(
    tenant_id      UUID NOT NULL REFERENCES tenant (id) ON DELETE CASCADE,
    load_id        UUID NOT NULL REFERENCES load_unit (id) ON DELETE CASCADE,
    consignment_id UUID NOT NULL REFERENCES consignment (id) ON DELETE RESTRICT,
    -- Drop order for multi-drop runs (3.4.2 "milk runs").
    drop_sequence  INT  NOT NULL,
    PRIMARY KEY (load_id, consignment_id),
    -- A consignment belongs to at most one load. Without this a double-booking
    -- is a silent data error that surfaces as freight billed twice.
    CONSTRAINT load_consignment_unique_assignment UNIQUE (consignment_id)
);

CREATE INDEX load_consignment_load_idx ON load_consignment (tenant_id, load_id, drop_sequence);

-- ---------------------------------------------------------------------------
-- Row-level security
-- ---------------------------------------------------------------------------

DO
$$
    DECLARE
        t TEXT;
    BEGIN
        FOREACH t IN ARRAY ARRAY [
            'sales_order', 'order_line', 'consignment', 'consignment_line',
            'load_unit', 'load_consignment'
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
