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


-- Contract lanes and choosing the contract at order entry.
--
-- A client contract used to price every movement at one per-kilo rate and no
-- order ever named it. A version now carries LANES -- origin and destination
-- (a terminal, or an LTL rating zone), freight mode, load type, a rate basis
-- with weight slabs, transit days, allowed consignors and consignees -- and
-- allowances (whether direct orders outside the lanes are accepted, and the
-- load types the contract covers). An order names the contract version in
-- force on its date and, where one applies, the lane it travels on.
--
-- Lanes are part of the version. They are editable while it is a DRAFT,
-- frozen the moment it is not (PENDING_VERIFICATION or ACTIVE), and copied
-- into a charge-add successor: the one INSERT a version in force accepts, and
-- only inside a transaction that said so through app.contract_successor.
--
-- The contract "family" is the contract as a person means it: every version
-- of one contract (a charge-add successor, a replacement with the same code
-- for the same client) shares the family_id of the first. It is the key of
-- the CONTRACT settings scope, so a setting survives a new version. NULL
-- means "its own id" (a version written before this migration, or a family's
-- first), so no existing, immutable row is rewritten.

-- ---------------------------------------------------------------------------
-- The version's allowances
-- ---------------------------------------------------------------------------

ALTER TABLE client_contract
    ADD COLUMN family_id             UUID,
    ADD COLUMN direct_orders_allowed BOOLEAN NOT NULL DEFAULT true,
    -- Comma separated, upper case; NULL covers every load type.
    ADD COLUMN allowed_load_types    TEXT;

ALTER TABLE client_contract
    ADD CONSTRAINT client_contract_load_types_bounded CHECK (
        allowed_load_types IS NULL OR (btrim(allowed_load_types) <> '' AND length(allowed_load_types) <= 500));

CREATE INDEX client_contract_family_idx ON client_contract (tenant_id, (coalesce(family_id, id)));

-- ---------------------------------------------------------------------------
-- Lanes
-- ---------------------------------------------------------------------------

CREATE TABLE client_contract_lane
(
    id                      UUID PRIMARY KEY,
    tenant_id               UUID        NOT NULL REFERENCES tenant (id) ON DELETE CASCADE,
    contract_id             UUID        NOT NULL REFERENCES client_contract (id) ON DELETE CASCADE,
    -- Names the lane within the contract: unique per version.
    lane_code               TEXT        NOT NULL,
    -- An end is a terminal or an LTL rating zone, never both.
    origin_terminal_id      UUID REFERENCES terminal (id),
    origin_zone             TEXT,
    destination_terminal_id UUID REFERENCES terminal (id),
    destination_zone        TEXT,
    -- NULL covers every freight mode / load type.
    freight_mode            TEXT,
    load_type               TEXT,
    -- The price when no slab covers the weight (or there are no slabs).
    rate_basis              TEXT        NOT NULL,
    rate                    NUMERIC(14, 4),
    transit_days            INTEGER,
    version                 BIGINT,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT client_contract_lane_code_once UNIQUE (contract_id, lane_code),
    CONSTRAINT client_contract_lane_code_present CHECK (btrim(lane_code) <> '' AND length(lane_code) <= 60),
    CONSTRAINT client_contract_lane_origin_one CHECK (
        (origin_terminal_id IS NOT NULL) <> (origin_zone IS NOT NULL)),
    CONSTRAINT client_contract_lane_destination_one CHECK (
        (destination_terminal_id IS NOT NULL) <> (destination_zone IS NOT NULL)),
    CONSTRAINT client_contract_lane_basis_valid CHECK (rate_basis IN ('PER_KG', 'PER_TRIP', 'PER_TONNE')),
    CONSTRAINT client_contract_lane_mode_valid CHECK (freight_mode IS NULL OR freight_mode IN ('FTL', 'LTL')),
    CONSTRAINT client_contract_lane_rate_non_negative CHECK (rate IS NULL OR rate >= 0),
    CONSTRAINT client_contract_lane_transit_positive CHECK (transit_days IS NULL OR transit_days > 0)
);

CREATE INDEX client_contract_lane_contract_idx ON client_contract_lane (tenant_id, contract_id, lane_code);

-- Weight bands, as a rate card's: half-open [min, max), a null max being the
-- open top band, each with its own rate basis.
CREATE TABLE client_contract_lane_slab
(
    id            UUID PRIMARY KEY,
    tenant_id     UUID           NOT NULL REFERENCES tenant (id) ON DELETE CASCADE,
    lane_id       UUID           NOT NULL REFERENCES client_contract_lane (id) ON DELETE CASCADE,
    min_weight_kg NUMERIC(14, 3) NOT NULL,
    max_weight_kg NUMERIC(14, 3),
    rate_basis    TEXT           NOT NULL,
    rate          NUMERIC(14, 4) NOT NULL,
    version       BIGINT,
    created_at    TIMESTAMPTZ    NOT NULL DEFAULT now(),
    CONSTRAINT client_contract_lane_slab_min_once UNIQUE (lane_id, min_weight_kg),
    CONSTRAINT client_contract_lane_slab_band CHECK (
        min_weight_kg >= 0 AND (max_weight_kg IS NULL OR max_weight_kg > min_weight_kg)),
    CONSTRAINT client_contract_lane_slab_basis_valid CHECK (rate_basis IN ('PER_KG', 'PER_TRIP', 'PER_TONNE')),
    CONSTRAINT client_contract_lane_slab_rate_non_negative CHECK (rate >= 0)
);

CREATE INDEX client_contract_lane_slab_lane_idx ON client_contract_lane_slab (tenant_id, lane_id, min_weight_kg);

-- The optional lists of who may consign, and who may receive, on a lane.
CREATE TABLE client_contract_lane_party
(
    tenant_id  UUID        NOT NULL REFERENCES tenant (id) ON DELETE CASCADE,
    lane_id    UUID        NOT NULL REFERENCES client_contract_lane (id) ON DELETE CASCADE,
    role       TEXT        NOT NULL,
    partner_id UUID        NOT NULL REFERENCES business_partner (id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (lane_id, role, partner_id),
    CONSTRAINT client_contract_lane_party_role_valid CHECK (role IN ('CONSIGNOR', 'CONSIGNEE'))
);

CREATE INDEX client_contract_lane_party_lane_idx ON client_contract_lane_party (tenant_id, lane_id, role);

DO
$$
    DECLARE
        t TEXT;
    BEGIN
        FOREACH t IN ARRAY ARRAY ['client_contract_lane', 'client_contract_lane_slab', 'client_contract_lane_party']
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

-- Lanes follow their version's immutability. Any write is allowed while the
-- version is a DRAFT; none once it is not, except the copy into a successor
-- (an INSERT, inside a transaction that raised app.contract_successor). A demo
-- reset steps aside, as it does for the other append-only tables.
CREATE OR REPLACE FUNCTION client_contract_lane_refuse_change() RETURNS TRIGGER AS
$$
DECLARE
    v_tenant   UUID;
    v_contract UUID;
    v_status   TEXT;
BEGIN
    IF TG_OP = 'DELETE' THEN
        v_tenant := OLD.tenant_id;
    ELSE
        v_tenant := NEW.tenant_id;
    END IF;
    IF demo_reset_active(v_tenant) THEN
        RETURN CASE WHEN TG_OP = 'DELETE' THEN OLD ELSE NEW END;
    END IF;

    IF TG_TABLE_NAME = 'client_contract_lane' THEN
        v_contract := CASE WHEN TG_OP = 'DELETE' THEN OLD.contract_id ELSE NEW.contract_id END;
    ELSE
        SELECT l.contract_id INTO v_contract FROM client_contract_lane l
         WHERE l.id = CASE WHEN TG_OP = 'DELETE' THEN OLD.lane_id ELSE NEW.lane_id END;
    END IF;
    SELECT k.status INTO v_status FROM client_contract k WHERE k.id = v_contract;

    IF v_status = 'DRAFT' THEN
        RETURN CASE WHEN TG_OP = 'DELETE' THEN OLD ELSE NEW END;
    END IF;
    IF TG_OP = 'INSERT' AND v_status IS NOT NULL
        AND coalesce(current_setting('app.contract_successor', true), '') = 'on' THEN
        RETURN NEW;
    END IF;
    RAISE EXCEPTION 'The lanes of a client contract that is not a draft cannot be changed; publish a new version instead'
        USING ERRCODE = 'restrict_violation';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER client_contract_lane_frozen
    BEFORE INSERT OR UPDATE OR DELETE
    ON client_contract_lane
    FOR EACH ROW
EXECUTE FUNCTION client_contract_lane_refuse_change();
CREATE TRIGGER client_contract_lane_slab_frozen
    BEFORE INSERT OR UPDATE OR DELETE
    ON client_contract_lane_slab
    FOR EACH ROW
EXECUTE FUNCTION client_contract_lane_refuse_change();
CREATE TRIGGER client_contract_lane_party_frozen
    BEFORE INSERT OR UPDATE OR DELETE
    ON client_contract_lane_party
    FOR EACH ROW
EXECUTE FUNCTION client_contract_lane_refuse_change();

-- The version's allowances are content like any other column.
CREATE OR REPLACE FUNCTION client_contract_refuse_repricing() RETURNS TRIGGER AS
$$
BEGIN
    IF NEW.id IS DISTINCT FROM OLD.id OR NEW.tenant_id IS DISTINCT FROM OLD.tenant_id THEN
        RAISE EXCEPTION 'Client contract % cannot change its identity', OLD.code
            USING ERRCODE = 'restrict_violation';
    END IF;

    -- A draft has never applied to anything: edit it freely.
    IF OLD.status = 'DRAFT' THEN
        RETURN NEW;
    END IF;

    IF OLD.status = 'PENDING_VERIFICATION' THEN
        IF ROW (NEW.org_unit_id, NEW.code, NEW.client_partner_id, NEW.currency, NEW.effective_from,
                NEW.effective_to, NEW.valid_till, NEW.transport_rate_per_kg, NEW.transport_minimum_charge,
                NEW.storage_rate_per_pallet_day, NEW.handling_in_rate_per_pallet,
                NEW.handling_out_rate_per_pallet, NEW.transport_tax_rate_pct,
                NEW.warehouse_tax_rate_pct, NEW.storage_units_per_pallet, NEW.bill_on_dispatch,
                NEW.family_id, NEW.direct_orders_allowed, NEW.allowed_load_types)
            IS DISTINCT FROM
           ROW (OLD.org_unit_id, OLD.code, OLD.client_partner_id, OLD.currency, OLD.effective_from,
                OLD.effective_to, OLD.valid_till, OLD.transport_rate_per_kg, OLD.transport_minimum_charge,
                OLD.storage_rate_per_pallet_day, OLD.handling_in_rate_per_pallet,
                OLD.handling_out_rate_per_pallet, OLD.transport_tax_rate_pct,
                OLD.warehouse_tax_rate_pct, OLD.storage_units_per_pallet, OLD.bill_on_dispatch,
                OLD.family_id, OLD.direct_orders_allowed, OLD.allowed_load_types)
        THEN
            RAISE EXCEPTION 'Client contract % is awaiting verification and cannot be edited', OLD.code
                USING ERRCODE = 'restrict_violation';
        END IF;
        RETURN NEW;
    END IF;

    -- ACTIVE from here on.
    IF NEW.status IS DISTINCT FROM OLD.status THEN
        RAISE EXCEPTION 'Client contract % is in force and cannot go back to %', OLD.code, NEW.status
            USING ERRCODE = 'restrict_violation';
    END IF;

    IF OLD.effective_to IS NOT NULL AND NEW.effective_to IS DISTINCT FROM OLD.effective_to THEN
        RAISE EXCEPTION 'Client contract % is already closed on %; a contract that has been used cannot be reopened',
            OLD.code, OLD.effective_to
            USING ERRCODE = 'restrict_violation';
    END IF;

    IF ROW (NEW.org_unit_id, NEW.code, NEW.client_partner_id, NEW.currency,
            NEW.effective_from, NEW.transport_rate_per_kg, NEW.transport_minimum_charge,
            NEW.storage_rate_per_pallet_day, NEW.handling_in_rate_per_pallet,
            NEW.handling_out_rate_per_pallet, NEW.transport_tax_rate_pct,
            NEW.warehouse_tax_rate_pct, NEW.storage_units_per_pallet, NEW.bill_on_dispatch,
            NEW.family_id, NEW.direct_orders_allowed, NEW.allowed_load_types)
        IS DISTINCT FROM
       ROW (OLD.org_unit_id, OLD.code, OLD.client_partner_id, OLD.currency,
            OLD.effective_from, OLD.transport_rate_per_kg, OLD.transport_minimum_charge,
            OLD.storage_rate_per_pallet_day, OLD.handling_in_rate_per_pallet,
            OLD.handling_out_rate_per_pallet, OLD.transport_tax_rate_pct,
            OLD.warehouse_tax_rate_pct, OLD.storage_units_per_pallet, OLD.bill_on_dispatch,
            OLD.family_id, OLD.direct_orders_allowed, OLD.allowed_load_types)
    THEN
        RAISE EXCEPTION 'Client contract % cannot be edited; publish a new version instead', OLD.code
            USING ERRCODE = 'restrict_violation';
    END IF;

    IF NEW.valid_till IS DISTINCT FROM OLD.valid_till THEN
        IF coalesce(current_setting('app.contract_extension', true), '') <> 'on' THEN
            RAISE EXCEPTION 'Client contract % changes its end date only through an approved extension', OLD.code
                USING ERRCODE = 'restrict_violation';
        END IF;
        IF OLD.valid_till IS NULL OR NEW.valid_till IS NULL OR NEW.valid_till <= OLD.valid_till THEN
            RAISE EXCEPTION 'Client contract % can only be extended to a later end date than %',
                OLD.code, OLD.valid_till
                USING ERRCODE = 'restrict_violation';
        END IF;
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- ---------------------------------------------------------------------------
-- The order names its contract, lane and load type
-- ---------------------------------------------------------------------------

-- contract_id is the version in force on the order's date, contract_lane_id
-- the lane it travels on (NULL for a direct order or one outside any
-- contract), contract_family_id the contract those belong to, kept so the
-- CONTRACT scope of the settings is resolved without a second lookup.
-- transit_days is the promise the order was resolved with: the lane's days,
-- else the distance divided by the tenant's kilometres per day.
ALTER TABLE sales_order
    ADD COLUMN contract_id        UUID REFERENCES client_contract (id),
    ADD COLUMN contract_lane_id   UUID REFERENCES client_contract_lane (id),
    ADD COLUMN contract_family_id UUID,
    -- True when a person named the lane: it is then held to when the order is
    -- judged again, not replaced by whichever lane serves it.
    ADD COLUMN lane_chosen          BOOLEAN NOT NULL DEFAULT false,
    ADD COLUMN load_type          TEXT,
    ADD COLUMN transit_days       INTEGER;

ALTER TABLE sales_order
    ADD CONSTRAINT sales_order_transit_days_positive CHECK (transit_days IS NULL OR transit_days > 0),
    ADD CONSTRAINT sales_order_load_type_bounded CHECK (
        load_type IS NULL OR (btrim(load_type) <> '' AND length(load_type) <= 60)),
    ADD CONSTRAINT sales_order_lane_needs_contract CHECK (contract_lane_id IS NULL OR contract_id IS NOT NULL);

CREATE INDEX sales_order_contract_idx ON sales_order (tenant_id, contract_id) WHERE contract_id IS NOT NULL;
CREATE INDEX sales_order_contract_lane_idx ON sales_order (tenant_id, contract_lane_id)
    WHERE contract_lane_id IS NOT NULL;

-- The lane a charge was captured under, so pricing it never depends on which
-- version happens to be in force when the invoice is raised.
ALTER TABLE client_charge
    ADD COLUMN contract_lane_id UUID REFERENCES client_contract_lane (id);

-- ---------------------------------------------------------------------------
-- The CONTRACT settings scope
-- ---------------------------------------------------------------------------

-- scope_id names a contract family (see above), looked up under row-level
-- security by the application. In the fold it sits after COMBINATION:
-- PLATFORM < ORG_DEFAULT < ORG_UNIT < VEHICLE < CUSTOMER < VENDOR
--   < COMBINATION < CONTRACT < USER.
ALTER TABLE setting_profile DROP CONSTRAINT setting_profile_scope_type_valid;
ALTER TABLE setting_profile ADD CONSTRAINT setting_profile_scope_type_valid
    CHECK (scope_type IN ('PLATFORM', 'ORG_DEFAULT', 'ORG_UNIT', 'VEHICLE', 'CUSTOMER', 'VENDOR', 'COMBINATION',
                          'CONTRACT', 'USER'));
