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

-- Master Data Management (vision document 3.2): the single source of truth for
-- partners, assets, personnel and the geospatial network.
--
-- Every aggregate root carries a `version` column, and that is load-bearing
-- rather than optional. Spring Data JDBC decides between INSERT and UPDATE by
-- asking whether the aggregate is new, and for a record with a client-assigned
-- @Id it concludes "not new" -- so save() issues an UPDATE that matches no rows
-- and persists nothing at all, silently and with no error. A @Version field
-- makes newness explicit (null version means new), and brings optimistic
-- locking with it, which matters once two dispatchers edit the same vehicle.

-- ---------------------------------------------------------------------------
-- 3.2.1 Business partner registry
-- ---------------------------------------------------------------------------

CREATE TABLE business_partner
(
    id             UUID PRIMARY KEY,
    tenant_id      UUID        NOT NULL REFERENCES tenant (id) ON DELETE CASCADE,
    org_unit_id    UUID        NOT NULL REFERENCES org_unit (id) ON DELETE RESTRICT,
    code           TEXT        NOT NULL,
    legal_name     TEXT        NOT NULL,
    partner_type   TEXT        NOT NULL,
    -- DRAFT -> PENDING_VERIFICATION -> ACTIVE -> SUSPENDED -> BLACKLISTED.
    -- Load allocation is blocked for SUSPENDED and BLACKLISTED.
    status         TEXT        NOT NULL DEFAULT 'DRAFT',
    tax_id         TEXT,
    -- Financial profile. Credit limit is nullable to mean "not assessed",
    -- which is different from a limit of zero.
    credit_limit   NUMERIC(18, 2),
    payment_terms  TEXT,
    billing_address TEXT,
    -- SLA scorecard, refreshed by a nightly batch. Nullable until enough
    -- history exists; a partner with no data must not read as 0% on-time.
    on_time_pct    NUMERIC(5, 2),
    claims_ratio   NUMERIC(5, 2),
    scorecard_at   TIMESTAMPTZ,
    version           BIGINT,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT business_partner_code_unique UNIQUE (tenant_id, code),
    CONSTRAINT business_partner_type_valid
        CHECK (partner_type IN ('CUSTOMER', 'VENDOR', 'BROKER', 'CONSIGNEE')),
    CONSTRAINT business_partner_status_valid
        CHECK (status IN ('DRAFT', 'PENDING_VERIFICATION', 'ACTIVE', 'SUSPENDED', 'BLACKLISTED'))
);

CREATE INDEX business_partner_status_idx ON business_partner (tenant_id, status);
CREATE INDEX business_partner_type_idx ON business_partner (tenant_id, partner_type, status);

-- Statutory documents, with expiry. Shared by partners, vehicles and drivers:
-- the compliance question is identical in all three cases, and duplicating the
-- table three times would let the three drift apart.
CREATE TABLE compliance_document
(
    id            UUID PRIMARY KEY,
    tenant_id     UUID        NOT NULL REFERENCES tenant (id) ON DELETE CASCADE,
    owner_type    TEXT        NOT NULL,
    owner_id      UUID        NOT NULL,
    document_type TEXT        NOT NULL,
    document_no   TEXT,
    issuing_authority TEXT,
    issued_on     DATE,
    -- NULL means "never expires" (a permanent registration, say). Queries for
    -- expiry must treat NULL as valid rather than as expired.
    expires_on    DATE,
    file_ref      TEXT,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT compliance_document_owner_valid
        CHECK (owner_type IN ('PARTNER', 'VEHICLE', 'DRIVER', 'TRIP'))
);

CREATE INDEX compliance_document_owner_idx
    ON compliance_document (tenant_id, owner_type, owner_id);
-- Partial index: the dispatch hard stop only ever asks about documents that
-- can expire.
CREATE INDEX compliance_document_expiry_idx
    ON compliance_document (tenant_id, expires_on) WHERE expires_on IS NOT NULL;

-- ---------------------------------------------------------------------------
-- 3.2.2 Vehicles -- the digital twin
-- ---------------------------------------------------------------------------

CREATE TABLE vehicle
(
    id                UUID PRIMARY KEY,
    tenant_id         UUID        NOT NULL REFERENCES tenant (id) ON DELETE CASCADE,
    org_unit_id       UUID        NOT NULL REFERENCES org_unit (id) ON DELETE RESTRICT,
    -- Owned by a vendor, or by the tenant's own fleet when null.
    owner_partner_id  UUID REFERENCES business_partner (id) ON DELETE RESTRICT,
    registration_no   TEXT        NOT NULL,
    category          TEXT        NOT NULL,
    vehicle_type      TEXT        NOT NULL,
    axle_config       TEXT,
    -- Hard dimensional capacities. Load building refuses to exceed these.
    gross_weight_kg   NUMERIC(12, 3) NOT NULL,
    tare_weight_kg    NUMERIC(12, 3) NOT NULL,
    max_volume_m3     NUMERIC(12, 3) NOT NULL,
    length_m          NUMERIC(8, 3),
    width_m           NUMERIC(8, 3),
    height_m          NUMERIC(8, 3),
    status            TEXT        NOT NULL DEFAULT 'AVAILABLE',
    hazmat_certified  BOOLEAN     NOT NULL DEFAULT false,
    reefer_capable    BOOLEAN     NOT NULL DEFAULT false,
    version           BIGINT,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT vehicle_registration_unique UNIQUE (tenant_id, registration_no),
    CONSTRAINT vehicle_status_valid
        CHECK (status IN ('AVAILABLE', 'ON_TRIP', 'MAINTENANCE', 'RETIRED')),
    -- Payload capacity is gross minus tare, so tare must be the smaller. A row
    -- violating this would compute a negative capacity and silently reject
    -- every load.
    CONSTRAINT vehicle_weights_sane CHECK (tare_weight_kg < gross_weight_kg),
    CONSTRAINT vehicle_volume_positive CHECK (max_volume_m3 > 0)
);

CREATE INDEX vehicle_status_idx ON vehicle (tenant_id, status);
CREATE INDEX vehicle_owner_idx ON vehicle (tenant_id, owner_partner_id);

-- ---------------------------------------------------------------------------
-- 3.2.2 Drivers
-- ---------------------------------------------------------------------------

CREATE TABLE driver
(
    id                 UUID PRIMARY KEY,
    tenant_id          UUID        NOT NULL REFERENCES tenant (id) ON DELETE CASCADE,
    org_unit_id        UUID        NOT NULL REFERENCES org_unit (id) ON DELETE RESTRICT,
    employer_partner_id UUID REFERENCES business_partner (id) ON DELETE RESTRICT,
    full_name          TEXT        NOT NULL,
    phone              TEXT,
    licence_no         TEXT        NOT NULL,
    licence_class      TEXT        NOT NULL,
    licence_authority  TEXT,
    licence_expires_on DATE,
    hazmat_endorsed    BOOLEAN     NOT NULL DEFAULT false,
    status             TEXT        NOT NULL DEFAULT 'AVAILABLE',
    -- Hours of Service, to prevent fatigue. Kept as a rolling counter rather
    -- than a full log: the MVP needs the limit enforced, not the audit trail.
    hos_minutes_today  INT         NOT NULL DEFAULT 0,
    hos_reset_at       TIMESTAMPTZ,
    version           BIGINT,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT driver_licence_unique UNIQUE (tenant_id, licence_no),
    CONSTRAINT driver_status_valid
        CHECK (status IN ('AVAILABLE', 'ON_TRIP', 'REST', 'INACTIVE')),
    CONSTRAINT driver_hos_non_negative CHECK (hos_minutes_today >= 0)
);

CREATE INDEX driver_status_idx ON driver (tenant_id, status);

-- ---------------------------------------------------------------------------
-- 3.2.3 Geospatial network -- terminals
-- ---------------------------------------------------------------------------

CREATE TABLE terminal
(
    id                UUID PRIMARY KEY,
    tenant_id         UUID        NOT NULL REFERENCES tenant (id) ON DELETE CASCADE,
    org_unit_id       UUID        NOT NULL REFERENCES org_unit (id) ON DELETE RESTRICT,
    code              TEXT        NOT NULL,
    name              TEXT        NOT NULL,
    -- The "functional category" of 3.2.3. Overlap is rejected only within the
    -- same category: a warehouse inside a port is legitimate, two overlapping
    -- warehouses are not.
    functional_category TEXT      NOT NULL,
    geofence_type     TEXT        NOT NULL,
    -- GeoJSON-order [lon, lat] ring. JSONB rather than a PostGIS geometry:
    -- PostGIS is GPLv2 and would compromise the Apache-2.0 distribution
    -- (DOCS/adr/0004). NULL for POINT_RADIUS geofences.
    polygon           JSONB,
    centre_lat        NUMERIC(10, 7),
    centre_lon        NUMERIC(10, 7),
    radius_m          NUMERIC(10, 2),
    -- Precomputed bounding box: the substitute for a spatial index. Every
    -- spatial query filters on these four columns first, then runs exact
    -- geometry in Java over the handful of survivors.
    min_lat           NUMERIC(10, 7) NOT NULL,
    max_lat           NUMERIC(10, 7) NOT NULL,
    min_lon           NUMERIC(10, 7) NOT NULL,
    max_lon           NUMERIC(10, 7) NOT NULL,
    -- Operational metadata (3.2.3).
    dock_count        INT,
    opens_at          TIME,
    closes_at         TIME,
    avg_dwell_minutes INT,
    permitted_vehicle_types TEXT[],
    version           BIGINT,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT terminal_code_unique UNIQUE (tenant_id, code),
    CONSTRAINT terminal_category_valid
        CHECK (functional_category IN ('PLANT', 'WAREHOUSE', 'PORT', 'HUB', 'TOLL_PLAZA', 'CUSTOMER_SITE')),
    CONSTRAINT terminal_geofence_type_valid
        CHECK (geofence_type IN ('POLYGON', 'POINT_RADIUS')),
    -- Each geofence type needs its own fields present. Enforced here so a
    -- half-specified terminal cannot reach the geofence evaluator, where the
    -- failure would be a silently missed trip transition.
    CONSTRAINT terminal_shape_complete CHECK (
        (geofence_type = 'POLYGON' AND polygon IS NOT NULL)
            OR (geofence_type = 'POINT_RADIUS'
                AND centre_lat IS NOT NULL AND centre_lon IS NOT NULL AND radius_m > 0)
        ),
    CONSTRAINT terminal_bbox_sane CHECK (min_lat <= max_lat AND min_lon <= max_lon)
);

-- Serves the candidate filter: "which terminals could contain this point".
CREATE INDEX terminal_bbox_idx ON terminal (tenant_id, min_lat, max_lat, min_lon, max_lon);
CREATE INDEX terminal_category_idx ON terminal (tenant_id, functional_category);

-- ---------------------------------------------------------------------------
-- Row-level security
-- ---------------------------------------------------------------------------

DO
$$
    DECLARE
        t TEXT;
    BEGIN
        FOREACH t IN ARRAY ARRAY [
            'business_partner', 'compliance_document', 'vehicle', 'driver', 'terminal'
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
