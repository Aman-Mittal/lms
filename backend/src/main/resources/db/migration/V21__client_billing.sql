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

-- Third-party logistics: client contracts and client billing.
--
-- Everything else in the schema is the shipper paying its carriers. This is the
-- other side of the ledger: a 3PL runs transport and warehousing for several
-- clients out of one tenant and invoices each of them for what it did. Three
-- rules shape the tables:
--
--   * a client contract is a version, immutable once published, exactly like a
--     carrier rate card -- an invoice argued in March is argued from the
--     contract that applied in January;
--   * a billable charge is captured once, whatever retries or redeliveries
--     happen upstream, and is billed once;
--   * a billed period is closed: a charge that arrives after the run goes into
--     the next open period, never into an invoice already sent.

-- ---------------------------------------------------------------------------
-- Client contracts
-- ---------------------------------------------------------------------------

CREATE TABLE client_contract
(
    id                           UUID PRIMARY KEY,
    tenant_id                    UUID        NOT NULL REFERENCES tenant (id) ON DELETE CASCADE,
    org_unit_id                  UUID        NOT NULL REFERENCES org_unit (id) ON DELETE RESTRICT,
    code                         TEXT        NOT NULL,
    -- The client: a business partner of type CUSTOMER. The type is checked by
    -- the publishing service; a foreign key cannot express it.
    client_partner_id            UUID        NOT NULL REFERENCES business_partner (id) ON DELETE RESTRICT,
    currency                     TEXT        NOT NULL DEFAULT 'INR',
    effective_from               DATE        NOT NULL,
    -- Inclusive. NULL is the version in force.
    effective_to                 DATE,

    -- Transport, charged on the client's chargeable weight on each completed
    -- movement, with a floor per movement.
    transport_rate_per_kg        NUMERIC(14, 4) NOT NULL DEFAULT 0,
    transport_minimum_charge     NUMERIC(14, 2) NOT NULL DEFAULT 0,
    -- Warehousing.
    storage_rate_per_pallet_day  NUMERIC(14, 4) NOT NULL DEFAULT 0,
    handling_in_rate_per_pallet  NUMERIC(14, 4) NOT NULL DEFAULT 0,
    handling_out_rate_per_pallet NUMERIC(14, 4) NOT NULL DEFAULT 0,

    version                      BIGINT,
    created_at                   TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT client_contract_period_sane CHECK (effective_to IS NULL OR effective_to >= effective_from),
    CONSTRAINT client_contract_rates_non_negative CHECK (
        transport_rate_per_kg >= 0 AND transport_minimum_charge >= 0
            AND storage_rate_per_pallet_day >= 0 AND handling_in_rate_per_pallet >= 0
            AND handling_out_rate_per_pallet >= 0)
);

-- One open version per client: "the contract in force on a date" must have
-- exactly one answer.
CREATE UNIQUE INDEX client_contract_open_version_idx
    ON client_contract (tenant_id, client_partner_id)
    WHERE effective_to IS NULL;

CREATE INDEX client_contract_lookup_idx
    ON client_contract (tenant_id, client_partner_id, effective_from DESC);

-- Value-added services priced per unit: labelling, kitting, shrink-wrapping.
-- A table rather than columns because the list is the client's, not ours.
CREATE TABLE client_contract_service_rate
(
    id           UUID PRIMARY KEY,
    tenant_id    UUID        NOT NULL REFERENCES tenant (id) ON DELETE CASCADE,
    contract_id  UUID        NOT NULL REFERENCES client_contract (id) ON DELETE CASCADE,
    service_code TEXT        NOT NULL,
    unit         TEXT        NOT NULL,
    rate         NUMERIC(14, 4) NOT NULL,
    version      BIGINT,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT client_contract_service_rate_once UNIQUE (contract_id, service_code),
    CONSTRAINT client_contract_service_rate_non_negative CHECK (rate >= 0)
);

CREATE INDEX client_contract_service_rate_contract_idx
    ON client_contract_service_rate (tenant_id, contract_id, service_code);

-- The rule of the rate card, applied to the other side of the ledger. Closing
-- a version is the only permitted change, because superseding is how a new
-- contract is published.
CREATE OR REPLACE FUNCTION client_contract_refuse_repricing() RETURNS TRIGGER AS
$$
BEGIN
    IF OLD.effective_to IS NOT NULL AND NEW.effective_to IS DISTINCT FROM OLD.effective_to THEN
        RAISE EXCEPTION 'Client contract % is already closed on %; a contract that has been used cannot be reopened',
            OLD.code, OLD.effective_to
            USING ERRCODE = 'restrict_violation';
    END IF;

    IF ROW (NEW.tenant_id, NEW.org_unit_id, NEW.code, NEW.client_partner_id, NEW.currency,
            NEW.effective_from, NEW.transport_rate_per_kg, NEW.transport_minimum_charge,
            NEW.storage_rate_per_pallet_day, NEW.handling_in_rate_per_pallet,
            NEW.handling_out_rate_per_pallet)
        IS DISTINCT FROM
       ROW (OLD.tenant_id, OLD.org_unit_id, OLD.code, OLD.client_partner_id, OLD.currency,
            OLD.effective_from, OLD.transport_rate_per_kg, OLD.transport_minimum_charge,
            OLD.storage_rate_per_pallet_day, OLD.handling_in_rate_per_pallet,
            OLD.handling_out_rate_per_pallet)
    THEN
        RAISE EXCEPTION 'Client contract % cannot be edited; publish a new version instead', OLD.code
            USING ERRCODE = 'restrict_violation';
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER client_contract_immutable
    BEFORE UPDATE
    ON client_contract
    FOR EACH ROW
EXECUTE FUNCTION client_contract_refuse_repricing();

CREATE RULE client_contract_service_rate_no_update AS ON UPDATE TO client_contract_service_rate DO INSTEAD NOTHING;
CREATE RULE client_contract_no_delete AS ON DELETE TO client_contract DO INSTEAD NOTHING;
CREATE RULE client_contract_service_rate_no_delete AS ON DELETE TO client_contract_service_rate DO INSTEAD NOTHING;

-- ---------------------------------------------------------------------------
-- Client portal users
-- ---------------------------------------------------------------------------

-- A user bound here is a client's own user: every client-billing read and
-- command is confined to that one client. A user whose account carries an ABAC
-- partner (app_user.partner_id) is confined to that partner without a row here.
CREATE TABLE client_portal_user
(
    tenant_id         UUID        NOT NULL REFERENCES tenant (id) ON DELETE CASCADE,
    user_id           UUID        NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    client_partner_id UUID        NOT NULL REFERENCES business_partner (id) ON DELETE RESTRICT,
    granted_at        TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (tenant_id, user_id)
);

CREATE INDEX client_portal_user_client_idx ON client_portal_user (tenant_id, client_partner_id);

-- ---------------------------------------------------------------------------
-- Billable charges
-- ---------------------------------------------------------------------------

-- What was done for a client, in quantities, not money. Priced at the billing
-- run against the contract in force on the day it was done, so a charge
-- captured before a contract correction is still priced on the right version.
CREATE TABLE client_charge
(
    id                UUID PRIMARY KEY,
    tenant_id         UUID        NOT NULL REFERENCES tenant (id) ON DELETE CASCADE,
    client_partner_id UUID        NOT NULL REFERENCES business_partner (id) ON DELETE RESTRICT,
    service_type      TEXT        NOT NULL,
    -- The value-added service code; NULL for every other type.
    service_code      TEXT,
    quantity          NUMERIC(14, 3) NOT NULL,
    unit              TEXT        NOT NULL,
    occurred_on       DATE        NOT NULL,
    -- The first of the month this charge is billed in. Normally the month it
    -- occurred; the next open month when that one was already billed.
    billing_period    DATE        NOT NULL,
    -- The idempotency key: TRIP:<trip>:<client> for a movement, the caller's
    -- own key for recorded service usage. Unique, so a redelivery is a no-op.
    source_key        TEXT        NOT NULL,
    reference         TEXT,
    -- The movement behind a transport charge, and the client's share of its
    -- chargeable weight -- what the carrier cost is apportioned by.
    trip_id           UUID REFERENCES trip (id) ON DELETE RESTRICT,
    cost_share        NUMERIC(9, 6),
    invoice_id        UUID,
    version           BIGINT,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT client_charge_source_once UNIQUE (tenant_id, source_key),
    CONSTRAINT client_charge_type_valid CHECK (service_type IN
        ('TRANSPORT', 'STORAGE', 'HANDLING_IN', 'HANDLING_OUT', 'VALUE_ADDED')),
    CONSTRAINT client_charge_code_iff_value_added CHECK (
        (service_type = 'VALUE_ADDED') = (service_code IS NOT NULL)),
    CONSTRAINT client_charge_quantity_positive CHECK (quantity > 0),
    CONSTRAINT client_charge_period_is_first_of_month CHECK (EXTRACT(DAY FROM billing_period) = 1),
    CONSTRAINT client_charge_never_billed_early CHECK (billing_period >= date_trunc('month', occurred_on::timestamp)::date),
    CONSTRAINT client_charge_share_sane CHECK (cost_share IS NULL OR (cost_share > 0 AND cost_share <= 1))
);

-- The billing run's read, and the period listing: one client, one period.
CREATE INDEX client_charge_period_idx
    ON client_charge (tenant_id, client_partner_id, billing_period, id);

CREATE INDEX client_charge_invoice_idx ON client_charge (tenant_id, invoice_id);

-- ---------------------------------------------------------------------------
-- Client invoices
-- ---------------------------------------------------------------------------

CREATE TABLE client_invoice
(
    id                UUID PRIMARY KEY,
    tenant_id         UUID        NOT NULL REFERENCES tenant (id) ON DELETE CASCADE,
    client_partner_id UUID        NOT NULL REFERENCES business_partner (id) ON DELETE RESTRICT,
    invoice_no        TEXT        NOT NULL,
    period_start      DATE        NOT NULL,
    period_end        DATE        NOT NULL,
    status            TEXT        NOT NULL DEFAULT 'ISSUED',
    currency          TEXT        NOT NULL DEFAULT 'INR',
    total_amount      NUMERIC(14, 2) NOT NULL,
    line_count        INT         NOT NULL,
    issued_at         TIMESTAMPTZ NOT NULL,
    version           BIGINT,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT client_invoice_no_unique UNIQUE (tenant_id, invoice_no),
    -- The idempotency of the run, and the period close, in one constraint: a
    -- client and month have at most one invoice, and once it exists the month
    -- is closed to new charges.
    CONSTRAINT client_invoice_period_once UNIQUE (tenant_id, client_partner_id, period_start),
    CONSTRAINT client_invoice_status_valid CHECK (status IN ('ISSUED')),
    CONSTRAINT client_invoice_period_is_month CHECK (
        EXTRACT(DAY FROM period_start) = 1
            AND period_end = (period_start + INTERVAL '1 month' - INTERVAL '1 day')::date)
);

-- The margin view: every client invoiced for one period.
CREATE INDEX client_invoice_period_idx ON client_invoice (tenant_id, period_start, client_partner_id);

CREATE TABLE client_invoice_line
(
    id           UUID PRIMARY KEY,
    tenant_id    UUID        NOT NULL REFERENCES tenant (id) ON DELETE CASCADE,
    invoice_id   UUID        NOT NULL REFERENCES client_invoice (id) ON DELETE CASCADE,
    line_no      INT         NOT NULL,
    charge_id    UUID        NOT NULL REFERENCES client_charge (id) ON DELETE RESTRICT,
    contract_id  UUID        NOT NULL REFERENCES client_contract (id) ON DELETE RESTRICT,
    service_type TEXT        NOT NULL,
    narrative    TEXT        NOT NULL,
    quantity     NUMERIC(14, 3) NOT NULL,
    unit         TEXT        NOT NULL,
    rate         NUMERIC(14, 4) NOT NULL,
    amount       NUMERIC(14, 2) NOT NULL,
    version      BIGINT,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT client_invoice_line_no_unique UNIQUE (invoice_id, line_no),
    -- A charge is billed once. Two lines for one charge is invoicing the same
    -- work twice, which is what this whole module must not do.
    CONSTRAINT client_invoice_line_charge_once UNIQUE (charge_id)
);

CREATE INDEX client_invoice_line_invoice_idx ON client_invoice_line (tenant_id, invoice_id, line_no);

-- Invoices are documents that have been sent. Corrections are credit notes
-- (not built), never edits.
CREATE RULE client_invoice_line_no_update AS ON UPDATE TO client_invoice_line DO INSTEAD NOTHING;
CREATE RULE client_invoice_line_no_delete AS ON DELETE TO client_invoice_line DO INSTEAD NOTHING;
CREATE RULE client_invoice_no_delete AS ON DELETE TO client_invoice DO INSTEAD NOTHING;

-- Freight bills are looked up by trip for the margin view; the unique
-- (tenant_id, trip_id) constraint on freight_bill already serves that.

-- ---------------------------------------------------------------------------
-- Permission vocabulary
-- ---------------------------------------------------------------------------

-- Setting what a client pays, running the bill, reading it, and seeing what we
-- make on it are four different authorities. The last is the 3PL's own number
-- and is never shown to a client portal user, whatever they hold.
INSERT INTO permission (id, code, resource, action)
VALUES (gen_random_uuid(), 'CLIENT_CONTRACT_MANAGE', 'CLIENT_CONTRACT', 'UPDATE'),
       (gen_random_uuid(), 'CLIENT_BILLING_RUN', 'CLIENT_INVOICE', 'EXECUTE'),
       (gen_random_uuid(), 'CLIENT_BILLING_READ', 'CLIENT_INVOICE', 'READ'),
       (gen_random_uuid(), 'CLIENT_MARGIN_READ', 'CLIENT_MARGIN', 'READ');

-- ---------------------------------------------------------------------------
-- Row-level security
-- ---------------------------------------------------------------------------

DO
$$
    DECLARE
        t TEXT;
    BEGIN
        FOREACH t IN ARRAY ARRAY [
            'client_contract', 'client_contract_service_rate', 'client_portal_user',
            'client_charge', 'client_invoice', 'client_invoice_line'
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
