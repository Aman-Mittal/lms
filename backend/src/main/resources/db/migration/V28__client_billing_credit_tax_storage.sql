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

-- Client billing, second pass: tax, credit notes, and storage derived from the
-- warehouse ledger.
--
--   * Tax is a percentage per service family on the contract version --
--     transport, and warehousing (storage, handling, value-added services) --
--     applied per invoice line, rounded half-up to two decimals per line.
--   * An issued invoice stays immutable. A correction is a credit note against
--     it, line by line, never more than what remains creditable on each line.
--   * Storage can be computed from the warehouse ledger: units of the client's
--     SKUs held at the end of each day, converted to pallets with the
--     contract's units-per-pallet, rounded up per day. One row per client and
--     day records the figure, which is also what makes the computation
--     idempotent.

-- ---------------------------------------------------------------------------
-- Contract: tax rates and the pallet definition
-- ---------------------------------------------------------------------------

ALTER TABLE client_contract
    ADD COLUMN transport_tax_rate_pct   NUMERIC(6, 3) NOT NULL DEFAULT 0,
    ADD COLUMN warehouse_tax_rate_pct   NUMERIC(6, 3) NOT NULL DEFAULT 0,
    -- How many units of the client's SKUs make one pallet position, for
    -- storage computed from the ledger. Part of the version like any rate.
    ADD COLUMN storage_units_per_pallet INTEGER       NOT NULL DEFAULT 1,
    ADD CONSTRAINT client_contract_tax_rates_sane CHECK (
        transport_tax_rate_pct BETWEEN 0 AND 100 AND warehouse_tax_rate_pct BETWEEN 0 AND 100),
    ADD CONSTRAINT client_contract_units_per_pallet_positive CHECK (storage_units_per_pallet > 0);

-- The immutability trigger compares every priced column; the new ones are
-- priced columns too.
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
            NEW.handling_out_rate_per_pallet, NEW.transport_tax_rate_pct,
            NEW.warehouse_tax_rate_pct, NEW.storage_units_per_pallet)
        IS DISTINCT FROM
       ROW (OLD.tenant_id, OLD.org_unit_id, OLD.code, OLD.client_partner_id, OLD.currency,
            OLD.effective_from, OLD.transport_rate_per_kg, OLD.transport_minimum_charge,
            OLD.storage_rate_per_pallet_day, OLD.handling_in_rate_per_pallet,
            OLD.handling_out_rate_per_pallet, OLD.transport_tax_rate_pct,
            OLD.warehouse_tax_rate_pct, OLD.storage_units_per_pallet)
    THEN
        RAISE EXCEPTION 'Client contract % cannot be edited; publish a new version instead', OLD.code
            USING ERRCODE = 'restrict_violation';
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- ---------------------------------------------------------------------------
-- Invoice tax
-- ---------------------------------------------------------------------------

-- client_invoice_line.amount stays what it was: the taxable amount of the
-- line. Invoices issued before this migration carried no tax, which is what
-- the zero defaults say about them.
ALTER TABLE client_invoice_line
    ADD COLUMN tax_rate   NUMERIC(6, 3)  NOT NULL DEFAULT 0,
    ADD COLUMN tax_amount NUMERIC(14, 2) NOT NULL DEFAULT 0;

-- total_amount stays the amount the client owes: taxable plus tax.
ALTER TABLE client_invoice
    ADD COLUMN tax_amount NUMERIC(14, 2) NOT NULL DEFAULT 0;

-- The statement reads a client's documents in issue order.
CREATE INDEX client_invoice_statement_idx
    ON client_invoice (tenant_id, client_partner_id, issued_at, id);

-- ---------------------------------------------------------------------------
-- Credit notes
-- ---------------------------------------------------------------------------

CREATE TABLE client_credit_note
(
    id                UUID PRIMARY KEY,
    tenant_id         UUID           NOT NULL REFERENCES tenant (id) ON DELETE CASCADE,
    client_partner_id UUID           NOT NULL REFERENCES business_partner (id) ON DELETE RESTRICT,
    invoice_id        UUID           NOT NULL REFERENCES client_invoice (id) ON DELETE RESTRICT,
    credit_note_no    TEXT           NOT NULL,
    -- The credited invoice's month: a credit note reduces the receivable of
    -- the period it corrects, not of the period it happens to be issued in.
    period_start      DATE           NOT NULL,
    reason            TEXT           NOT NULL,
    currency          TEXT           NOT NULL,
    taxable_amount    NUMERIC(14, 2) NOT NULL,
    tax_amount        NUMERIC(14, 2) NOT NULL,
    total_amount      NUMERIC(14, 2) NOT NULL,
    line_count        INT            NOT NULL,
    issued_at         TIMESTAMPTZ    NOT NULL,
    version           BIGINT,
    created_at        TIMESTAMPTZ    NOT NULL DEFAULT now(),
    CONSTRAINT client_credit_note_no_unique UNIQUE (tenant_id, credit_note_no),
    CONSTRAINT client_credit_note_reason_given CHECK (length(btrim(reason)) > 0),
    CONSTRAINT client_credit_note_amounts_sane CHECK (
        taxable_amount > 0 AND tax_amount >= 0 AND total_amount = taxable_amount + tax_amount)
);

CREATE INDEX client_credit_note_invoice_idx ON client_credit_note (tenant_id, invoice_id);
CREATE INDEX client_credit_note_statement_idx
    ON client_credit_note (tenant_id, client_partner_id, issued_at, id);

CREATE TABLE client_credit_note_line
(
    id              UUID PRIMARY KEY,
    tenant_id       UUID           NOT NULL REFERENCES tenant (id) ON DELETE CASCADE,
    credit_note_id  UUID           NOT NULL REFERENCES client_credit_note (id) ON DELETE CASCADE,
    line_no         INT            NOT NULL,
    invoice_line_id UUID           NOT NULL REFERENCES client_invoice_line (id) ON DELETE RESTRICT,
    service_type    TEXT           NOT NULL,
    narrative       TEXT           NOT NULL,
    taxable_amount  NUMERIC(14, 2) NOT NULL,
    tax_rate        NUMERIC(6, 3)  NOT NULL,
    tax_amount      NUMERIC(14, 2) NOT NULL,
    version         BIGINT,
    created_at      TIMESTAMPTZ    NOT NULL DEFAULT now(),
    CONSTRAINT client_credit_note_line_no_unique UNIQUE (credit_note_id, line_no),
    CONSTRAINT client_credit_note_line_amounts_sane CHECK (taxable_amount > 0 AND tax_amount >= 0)
);

CREATE INDEX client_credit_note_line_note_idx
    ON client_credit_note_line (tenant_id, credit_note_id, line_no);
CREATE INDEX client_credit_note_line_invoice_line_idx
    ON client_credit_note_line (tenant_id, invoice_line_id);

-- The limit, held by the database as well as the service: what has been
-- credited against an invoice line never exceeds what the line charged, in
-- taxable amount or in tax. The service serialises credit notes per client
-- under the billing lock and checks first, so a refusal from here means
-- something wrote around it.
CREATE OR REPLACE FUNCTION client_credit_note_line_within_invoice() RETURNS TRIGGER AS
$$
DECLARE
    charged_taxable NUMERIC;
    charged_tax     NUMERIC;
    credited_taxable NUMERIC;
    credited_tax    NUMERIC;
BEGIN
    SELECT amount, tax_amount INTO charged_taxable, charged_tax
      FROM client_invoice_line
     WHERE id = NEW.invoice_line_id;

    SELECT coalesce(sum(taxable_amount), 0), coalesce(sum(tax_amount), 0)
      INTO credited_taxable, credited_tax
      FROM client_credit_note_line
     WHERE tenant_id = NEW.tenant_id
       AND invoice_line_id = NEW.invoice_line_id;

    IF credited_taxable > charged_taxable OR credited_tax > charged_tax THEN
        RAISE EXCEPTION 'Credit notes against invoice line % would exceed what it charged (% + % tax)',
            NEW.invoice_line_id, charged_taxable, charged_tax
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER client_credit_note_line_within_invoice
    AFTER INSERT
    ON client_credit_note_line
    FOR EACH ROW
EXECUTE FUNCTION client_credit_note_line_within_invoice();

-- A credit note is a document that has been sent, exactly like an invoice.
CREATE RULE client_credit_note_no_update AS ON UPDATE TO client_credit_note DO INSTEAD NOTHING;
CREATE RULE client_credit_note_no_delete AS ON DELETE TO client_credit_note DO INSTEAD NOTHING;
CREATE RULE client_credit_note_line_no_update AS ON UPDATE TO client_credit_note_line DO INSTEAD NOTHING;
CREATE RULE client_credit_note_line_no_delete AS ON DELETE TO client_credit_note_line DO INSTEAD NOTHING;

-- ---------------------------------------------------------------------------
-- Storage from the warehouse ledger
-- ---------------------------------------------------------------------------

-- One row per client and day of a computed month: the evidence behind a
-- ledger-derived storage line, and the idempotency of the computation. A
-- day that has a row has been charged (or found empty) and is never charged
-- again.
CREATE TABLE client_storage_day
(
    tenant_id         UUID        NOT NULL REFERENCES tenant (id) ON DELETE CASCADE,
    client_partner_id UUID        NOT NULL REFERENCES business_partner (id) ON DELETE RESTRICT,
    held_on           DATE        NOT NULL,
    period_start      DATE        NOT NULL,
    -- Units of the client's SKUs in any of the tenant's warehouses at the end
    -- of the day (UTC), wherever they stood: dock, bin, picked or packed.
    units_held        BIGINT      NOT NULL,
    units_per_pallet  INTEGER,
    -- ceil(units_held / units_per_pallet): a part pallet occupies a position.
    pallets           BIGINT      NOT NULL,
    contract_id       UUID REFERENCES client_contract (id) ON DELETE RESTRICT,
    charge_id         UUID REFERENCES client_charge (id) ON DELETE RESTRICT,
    computed_at       TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (tenant_id, client_partner_id, held_on),
    CONSTRAINT client_storage_day_period_matches CHECK (
        period_start = date_trunc('month', held_on::timestamp)::date),
    CONSTRAINT client_storage_day_figures_sane CHECK (units_held >= 0 AND pallets >= 0)
);

CREATE INDEX client_storage_day_period_idx
    ON client_storage_day (tenant_id, client_partner_id, period_start, held_on);

-- The ledger read behind it starts from a client's SKUs, which V24's
-- sku_owner_idx (tenant_id, owner_partner_id, code) already serves.

-- ---------------------------------------------------------------------------
-- Permission vocabulary
-- ---------------------------------------------------------------------------

-- Crediting a client is giving money back. It is not the same authority as
-- running the bill.
INSERT INTO permission (id, code, resource, action)
VALUES (gen_random_uuid(), 'CLIENT_CREDIT_NOTE_ISSUE', 'CLIENT_CREDIT_NOTE', 'CREATE');

-- ---------------------------------------------------------------------------
-- Where credit notes and ledger storage may be filed
-- ---------------------------------------------------------------------------

-- Billing a client, and crediting one, write on the client's behalf, so they
-- are held to the caller's subtree like every other write (V24). The billing
-- run, recorded usage and a storage computation share one rule on
-- CLIENT_BILLING_RUN; a credit note has its own. None has a unit of its own;
-- each is filed where the client partner is. Replaces
-- V27's definition with two rules added; the V16 trigger calls the function by
-- name, so new tenants get them.
CREATE OR REPLACE FUNCTION seed_default_access_policies(p_tenant UUID) RETURNS void
    LANGUAGE sql
    AS $$
    INSERT INTO access_policy (id, tenant_id, code, action, effect, description, condition, version)
    SELECT gen_random_uuid(), p_tenant, rule.code, rule.action, 'DENY', rule.description,
           -- NULL stands for the subtree rule, written once rather than
           -- repeated per rule: refuse when the record's unit is not the
           -- caller's own or beneath it.
           coalesce(rule.condition,
                    '{"allOf": [{"attr": "resource.orgPath", "op": "present"},
                                {"attr": "subject.orgPath", "op": "present"},
                                {"not": {"attr": "resource.orgPath", "op": "startsWith",
                                         "ref": "subject.orgPath"}}]}'::jsonb),
           0
      FROM (VALUES
        ('invoice-approval-limit', 'INVOICE_APPROVE',
         'An invoice may be approved, or a dispute settled, only up to the approver''s limit.',
         '{"attr": "resource.amount", "op": "gt", "ref": "subject.approvalLimit"}'::jsonb),
        ('exception-close-holding-limit', 'EXCEPTION_CLOSE',
         'Closing a ticket that is holding settlement releases money, so it needs an approval limit of at least 100000.',
         '{"allOf": [{"attr": "resource.suspendsSettlement", "op": "eq", "value": true},
                     {"attr": "subject.approvalLimit", "op": "lt", "value": 100000}]}'::jsonb),
        ('partner-write-org-subtree', 'PARTNER_CREATE',
         'A partner may be registered only under the caller''s own organisational unit or one beneath it.',
         NULL),
        ('vehicle-write-org-subtree', 'VEHICLE_CREATE',
         'A vehicle may be registered only under the caller''s own organisational unit or one beneath it.',
         NULL),
        ('driver-write-org-subtree', 'DRIVER_CREATE',
         'A driver may be registered only under the caller''s own organisational unit or one beneath it.',
         NULL),
        ('terminal-write-org-subtree', 'TERMINAL_CREATE',
         'A terminal may be registered only under the caller''s own organisational unit or one beneath it.',
         NULL),
        ('order-write-org-subtree', 'ORDER_CREATE',
         'An order may be raised only under the caller''s own organisational unit or one beneath it.',
         NULL),
        ('load-write-org-subtree', 'LOAD_CREATE',
         'A load may be opened only under the caller''s own organisational unit or one beneath it.',
         NULL),
        ('trip-write-org-subtree', 'TRIP_EXECUTE',
         'A trip may be raised only against a load in the caller''s own organisational unit or one beneath it.',
         NULL),
        ('routing-guide-write-org-subtree', 'ROUTING_GUIDE_MANAGE',
         'A routing guide may be defined only under the caller''s own organisational unit or one beneath it.',
         NULL),
        ('milk-run-write-org-subtree', 'MILKRUN_PLAN',
         'A milk run may be published only from a plant in the caller''s own organisational unit or one beneath it.',
         NULL),
        ('itinerary-write-org-subtree', 'MULTIMODAL_PLAN',
         'An itinerary may be planned only from a terminal in the caller''s own organisational unit or one beneath it.',
         NULL),
        ('warehouse-setup-org-subtree', 'WAREHOUSE_SETUP',
         'Bins may be laid out, and SKUs catalogued for an owner, only in the caller''s own organisational unit or one beneath it.',
         NULL),
        ('asn-write-org-subtree', 'WAREHOUSE_OPERATE',
         'An ASN may be filed only at a warehouse in the caller''s own organisational unit or one beneath it.',
         NULL),
        ('client-contract-write-org-subtree', 'CLIENT_CONTRACT_MANAGE',
         'A client contract may be published only under the caller''s own organisational unit or one beneath it.',
         NULL),
        ('inventory-adjust-org-subtree', 'INVENTORY_ADJUST',
         'Stock may be adjusted, and quarantined units released or written off, only at a warehouse in the caller''s own organisational unit or one beneath it.',
         NULL),
        ('rail-tariff-write-org-subtree', 'TARIFF_MANAGE',
         'A rail rate card may be published only from a railhead in the caller''s own organisational unit or one beneath it.',
         NULL),
        ('client-credit-note-write-org-subtree', 'CLIENT_CREDIT_NOTE_ISSUE',
         'A credit note may be issued only against the invoice of a client in the caller''s own organisational unit or one beneath it.',
         NULL),
        ('client-billing-write-org-subtree', 'CLIENT_BILLING_RUN',
         'A client may be billed -- run, usage recorded, storage computed -- only in the caller''s own organisational unit or one beneath it.',
         NULL)
      ) AS rule (code, action, description, condition)
    ON CONFLICT (tenant_id, code) DO NOTHING
$$;

-- Existing tenants, as V24, V26 and V27 seeded them: each under its own scope, scope
-- cleared after. ON CONFLICT leaves every existing row alone.
DO
$$
    DECLARE
        t UUID;
    BEGIN
        FOR t IN SELECT id FROM tenant
            LOOP
                PERFORM set_config('app.tenant_id', t::text, true);
                PERFORM seed_default_access_policies(t);
            END LOOP;
        PERFORM set_config('app.tenant_id', '', true);
    END
$$;

-- ---------------------------------------------------------------------------
-- Row-level security
-- ---------------------------------------------------------------------------

DO
$$
    DECLARE
        t TEXT;
    BEGIN
        FOREACH t IN ARRAY ARRAY ['client_credit_note', 'client_credit_note_line', 'client_storage_day']
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
