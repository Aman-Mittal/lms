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

-- Issue #225: the money still missing around a freight bill. A per-tenant charge
-- catalogue, manual charges against a trip, consignment or order (approved
-- above a threshold, billed to the customer or paid to the vendor), vendor
-- invoices captured against approved freight bills with payments and an
-- overdue list, and vendor advances against a trip.
--
-- Nothing here re-prices anything. A tariff and a freight bill stay as they
-- were raised; a manual charge is its own record that is attached to a bill
-- only while the bill is an open draft, and otherwise waits for the vendor's
-- next bill.

-- ---------------------------------------------------------------------------
-- The charge catalogue
-- ---------------------------------------------------------------------------

CREATE TABLE charge_catalogue
(
    id           UUID PRIMARY KEY,
    tenant_id    UUID           NOT NULL REFERENCES tenant (id) ON DELETE CASCADE,
    -- Upper case, how a person finds it; unique per tenant and never changed.
    code         TEXT           NOT NULL,
    name         TEXT           NOT NULL,
    basis        TEXT           NOT NULL,
    default_rate NUMERIC(14, 4) NOT NULL,
    currency     TEXT           NOT NULL DEFAULT 'INR',
    -- Who the charge may be raised against: the customer, the vendor, or either.
    direction    TEXT           NOT NULL,
    active       BOOLEAN        NOT NULL DEFAULT true,
    version      BIGINT,
    created_at   TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ    NOT NULL DEFAULT now(),
    CONSTRAINT charge_catalogue_code_unique UNIQUE (tenant_id, code),
    CONSTRAINT charge_catalogue_basis_valid CHECK (basis IN ('FLAT', 'PER_KG', 'PER_HOUR', 'PER_DAY', 'PER_UNIT')),
    CONSTRAINT charge_catalogue_direction_valid CHECK (direction IN ('RECEIVABLE', 'PAYABLE', 'BOTH')),
    CONSTRAINT charge_catalogue_code_bounded CHECK (btrim(code) <> '' AND length(code) <= 40),
    CONSTRAINT charge_catalogue_name_bounded CHECK (btrim(name) <> '' AND length(name) <= 120),
    CONSTRAINT charge_catalogue_rate_nonnegative CHECK (default_rate >= 0)
);

-- ---------------------------------------------------------------------------
-- Manual charges
-- ---------------------------------------------------------------------------

CREATE TABLE manual_charge
(
    id                  UUID PRIMARY KEY,
    tenant_id           UUID           NOT NULL REFERENCES tenant (id) ON DELETE CASCADE,
    charge_no           TEXT           NOT NULL,
    org_unit_id         UUID           NOT NULL REFERENCES org_unit (id) ON DELETE RESTRICT,
    catalogue_id        UUID           NOT NULL REFERENCES charge_catalogue (id) ON DELETE RESTRICT,
    -- The catalogue entry as it was when the charge was raised.
    charge_code         TEXT           NOT NULL,
    charge_name         TEXT           NOT NULL,
    basis               TEXT           NOT NULL,
    direction           TEXT           NOT NULL,
    subject_type        TEXT           NOT NULL,
    subject_id          UUID           NOT NULL,
    subject_ref         TEXT           NOT NULL,
    -- The trip the charge rides on: the subject itself for a trip. A payable
    -- charge always has one, because it is paid to the trip's vendor.
    trip_id             UUID REFERENCES trip (id) ON DELETE RESTRICT,
    customer_partner_id UUID REFERENCES business_partner (id) ON DELETE RESTRICT,
    vendor_partner_id   UUID REFERENCES business_partner (id) ON DELETE RESTRICT,
    quantity            NUMERIC(14, 3) NOT NULL,
    rate                NUMERIC(14, 4) NOT NULL,
    amount              NUMERIC(14, 2) NOT NULL,
    currency            TEXT           NOT NULL,
    reason              TEXT           NOT NULL,
    evidence_ref        TEXT,
    status              TEXT           NOT NULL,
    approval_id         UUID,
    -- The client charge a receivable became (clientbilling), when the customer
    -- has a client contract to bill under.
    client_charge_id    UUID,
    -- The freight bill a payable is a line of, once attached.
    freight_bill_id     UUID REFERENCES freight_bill (id) ON DELETE RESTRICT,
    attached_at         TIMESTAMPTZ,
    requested_by        UUID,
    decided_at          TIMESTAMPTZ,
    cancelled_at        TIMESTAMPTZ,
    cancel_reason       TEXT,
    version             BIGINT,
    created_at          TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ    NOT NULL DEFAULT now(),
    CONSTRAINT manual_charge_no_unique UNIQUE (tenant_id, charge_no),
    CONSTRAINT manual_charge_basis_valid CHECK (basis IN ('FLAT', 'PER_KG', 'PER_HOUR', 'PER_DAY', 'PER_UNIT')),
    CONSTRAINT manual_charge_direction_valid CHECK (direction IN ('RECEIVABLE', 'PAYABLE')),
    CONSTRAINT manual_charge_subject_valid CHECK (subject_type IN ('TRIP', 'CONSIGNMENT', 'ORDER')),
    CONSTRAINT manual_charge_status_valid CHECK (status IN ('PENDING_APPROVAL', 'APPROVED', 'REJECTED', 'CANCELLED')),
    CONSTRAINT manual_charge_amount_positive CHECK (amount > 0 AND quantity > 0 AND rate >= 0),
    CONSTRAINT manual_charge_party_matches_direction CHECK (
        (direction = 'RECEIVABLE' AND customer_partner_id IS NOT NULL AND vendor_partner_id IS NULL)
            OR (direction = 'PAYABLE' AND vendor_partner_id IS NOT NULL AND customer_partner_id IS NULL
                AND trip_id IS NOT NULL)),
    CONSTRAINT manual_charge_cancelled_whole CHECK (
        (status = 'CANCELLED') = (cancelled_at IS NOT NULL AND cancel_reason IS NOT NULL)),
    CONSTRAINT manual_charge_attached_whole CHECK ((freight_bill_id IS NULL) = (attached_at IS NULL)),
    CONSTRAINT manual_charge_reason_bounded CHECK (btrim(reason) <> '' AND length(reason) <= 500)
);

-- The list, newest first: keyset on (created_at, id).
CREATE INDEX manual_charge_list_idx ON manual_charge (tenant_id, created_at DESC, id DESC);
-- Everything raised against one subject (the trip, consignment or order view).
CREATE INDEX manual_charge_subject_idx ON manual_charge (tenant_id, subject_type, subject_id, created_at DESC, id DESC);
-- A trip's charges whichever subject they were raised against.
CREATE INDEX manual_charge_trip_idx ON manual_charge (tenant_id, trip_id) WHERE trip_id IS NOT NULL;
-- The payables waiting for the vendor's next freight bill.
CREATE INDEX manual_charge_waiting_idx ON manual_charge (tenant_id, vendor_partner_id, created_at, id)
    WHERE direction = 'PAYABLE' AND status = 'APPROVED' AND freight_bill_id IS NULL;
CREATE INDEX manual_charge_bill_idx ON manual_charge (tenant_id, freight_bill_id) WHERE freight_bill_id IS NOT NULL;

-- A payable charge becomes a line of the vendor's bill, and the line says which charge it is.
ALTER TABLE freight_bill_line
    ADD COLUMN manual_charge_id UUID REFERENCES manual_charge (id) ON DELETE RESTRICT;
CREATE UNIQUE INDEX freight_bill_line_manual_charge_idx ON freight_bill_line (tenant_id, manual_charge_id)
    WHERE manual_charge_id IS NOT NULL;
ALTER TABLE freight_bill_line DROP CONSTRAINT freight_bill_line_type_valid;
ALTER TABLE freight_bill_line
    ADD CONSTRAINT freight_bill_line_type_valid CHECK (charge_type IN
        ('BASE_FREIGHT', 'MINIMUM_CHARGE_UPLIFT', 'DETENTION',
         'ADDITIONAL_DROP', 'FUEL_SURCHARGE', 'COLLECTION_STOP', 'MANUAL_CHARGE'));

-- A receivable becomes a client charge priced by its own amount, not by the
-- contract's rates: the person who raised it chose the rate.
ALTER TABLE client_charge
    ADD COLUMN manual_rate   NUMERIC(14, 4),
    ADD COLUMN manual_amount NUMERIC(14, 2),
    ADD COLUMN narrative     TEXT;
ALTER TABLE client_charge DROP CONSTRAINT client_charge_type_valid;
ALTER TABLE client_charge
    ADD CONSTRAINT client_charge_type_valid CHECK (service_type IN
        ('TRANSPORT', 'STORAGE', 'HANDLING_IN', 'HANDLING_OUT', 'VALUE_ADDED', 'MANUAL')),
    ADD CONSTRAINT client_charge_manual_whole CHECK (
        (service_type = 'MANUAL') = (manual_rate IS NOT NULL AND manual_amount IS NOT NULL AND narrative IS NOT NULL));

-- ---------------------------------------------------------------------------
-- Vendor invoices and payments
-- ---------------------------------------------------------------------------

CREATE TABLE vendor_invoice
(
    id                  UUID PRIMARY KEY,
    tenant_id           UUID           NOT NULL REFERENCES tenant (id) ON DELETE CASCADE,
    -- Our reference: from a VENDOR_INVOICE series when one covers the unit.
    invoice_no          TEXT           NOT NULL,
    org_unit_id         UUID           NOT NULL REFERENCES org_unit (id) ON DELETE RESTRICT,
    vendor_partner_id   UUID           NOT NULL REFERENCES business_partner (id) ON DELETE RESTRICT,
    -- The vendor's own number, unique per vendor ignoring case.
    vendor_invoice_no   TEXT           NOT NULL,
    invoice_date        DATE           NOT NULL,
    amount              NUMERIC(14, 2) NOT NULL,
    tax_amount          NUMERIC(14, 2) NOT NULL DEFAULT 0,
    total_amount        NUMERIC(14, 2) NOT NULL,
    currency            TEXT           NOT NULL,
    -- What the bills it covers were approved at, and the claim's difference.
    approved_amount     NUMERIC(14, 2) NOT NULL,
    variance_amount     NUMERIC(14, 2) NOT NULL,
    document_ref        TEXT,
    remarks             TEXT,
    payment_terms_days  INT            NOT NULL,
    due_date            DATE           NOT NULL,
    -- Advances paid against the covered trips, taken off what is owed.
    advances_deducted   NUMERIC(14, 2) NOT NULL DEFAULT 0,
    paid_amount         NUMERIC(14, 2) NOT NULL DEFAULT 0,
    status              TEXT           NOT NULL DEFAULT 'OPEN',
    version             BIGINT,
    created_at          TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ    NOT NULL DEFAULT now(),
    CONSTRAINT vendor_invoice_no_unique UNIQUE (tenant_id, invoice_no),
    CONSTRAINT vendor_invoice_status_valid CHECK (status IN ('OPEN', 'PARTIALLY_PAID', 'PAID')),
    CONSTRAINT vendor_invoice_amount_positive CHECK (amount > 0 AND tax_amount >= 0
        AND total_amount = amount + tax_amount),
    CONSTRAINT vendor_invoice_balance_sane CHECK (advances_deducted >= 0 AND paid_amount >= 0
        AND advances_deducted + paid_amount <= total_amount),
    CONSTRAINT vendor_invoice_terms_valid CHECK (payment_terms_days BETWEEN 0 AND 365),
    CONSTRAINT vendor_invoice_vendor_no_bounded CHECK (btrim(vendor_invoice_no) <> '' AND length(vendor_invoice_no) <= 60)
);

CREATE UNIQUE INDEX vendor_invoice_vendor_no_idx
    ON vendor_invoice (tenant_id, vendor_partner_id, lower(btrim(vendor_invoice_no)));
-- The list, newest first.
CREATE INDEX vendor_invoice_list_idx ON vendor_invoice (tenant_id, created_at DESC, id DESC);
-- The overdue list: what is still owed, earliest due first. Keyset on (due_date, id).
CREATE INDEX vendor_invoice_due_idx ON vendor_invoice (tenant_id, due_date, id) WHERE status <> 'PAID';
CREATE INDEX vendor_invoice_vendor_idx ON vendor_invoice (tenant_id, vendor_partner_id, due_date, id);

-- The bills an invoice covers. A bill is invoiced once.
CREATE TABLE vendor_invoice_bill
(
    tenant_id       UUID           NOT NULL REFERENCES tenant (id) ON DELETE CASCADE,
    invoice_id      UUID           NOT NULL REFERENCES vendor_invoice (id) ON DELETE CASCADE,
    freight_bill_id UUID           NOT NULL REFERENCES freight_bill (id) ON DELETE RESTRICT,
    approved_amount NUMERIC(14, 2) NOT NULL,
    PRIMARY KEY (tenant_id, invoice_id, freight_bill_id),
    CONSTRAINT vendor_invoice_bill_once UNIQUE (tenant_id, freight_bill_id)
);

CREATE TABLE vendor_payment
(
    id                UUID PRIMARY KEY,
    tenant_id         UUID           NOT NULL REFERENCES tenant (id) ON DELETE CASCADE,
    invoice_id        UUID           NOT NULL REFERENCES vendor_invoice (id) ON DELETE RESTRICT,
    vendor_partner_id UUID           NOT NULL REFERENCES business_partner (id) ON DELETE RESTRICT,
    amount            NUMERIC(14, 2) NOT NULL,
    paid_on           DATE           NOT NULL,
    mode              TEXT           NOT NULL,
    reference         TEXT           NOT NULL,
    recorded_by       UUID,
    version           BIGINT,
    created_at        TIMESTAMPTZ    NOT NULL DEFAULT now(),
    CONSTRAINT vendor_payment_amount_positive CHECK (amount > 0),
    CONSTRAINT vendor_payment_mode_valid CHECK (mode IN ('BANK_TRANSFER', 'CHEQUE', 'CASH', 'OTHER')),
    CONSTRAINT vendor_payment_reference_bounded CHECK (btrim(reference) <> '' AND length(reference) <= 80)
);

-- A reference is recorded once per vendor, so a retried request is not a second payment.
CREATE UNIQUE INDEX vendor_payment_reference_idx
    ON vendor_payment (tenant_id, vendor_partner_id, lower(btrim(reference)));
CREATE INDEX vendor_payment_invoice_idx ON vendor_payment (tenant_id, invoice_id, paid_on, id);
CREATE INDEX vendor_payment_list_idx ON vendor_payment (tenant_id, created_at DESC, id DESC);

-- ---------------------------------------------------------------------------
-- Vendor advances
-- ---------------------------------------------------------------------------

CREATE TABLE vendor_advance
(
    id                  UUID PRIMARY KEY,
    tenant_id           UUID           NOT NULL REFERENCES tenant (id) ON DELETE CASCADE,
    advance_no          TEXT           NOT NULL,
    org_unit_id         UUID           NOT NULL REFERENCES org_unit (id) ON DELETE RESTRICT,
    trip_id             UUID           NOT NULL REFERENCES trip (id) ON DELETE RESTRICT,
    vendor_partner_id   UUID           NOT NULL REFERENCES business_partner (id) ON DELETE RESTRICT,
    mode                TEXT           NOT NULL,
    amount              NUMERIC(14, 2) NOT NULL,
    currency            TEXT           NOT NULL,
    reason              TEXT           NOT NULL,
    -- What the cap was measured against and how: the trip's freight bill, or
    -- the platform's estimate of the load on the rate card in force.
    freight_reference   NUMERIC(14, 2) NOT NULL,
    freight_source      TEXT           NOT NULL,
    max_percent         NUMERIC(5, 2)  NOT NULL,
    status              TEXT           NOT NULL,
    approval_id         UUID,
    requested_by        UUID,
    approved_at         TIMESTAMPTZ,
    paid_reference      TEXT,
    paid_on             DATE,
    paid_by             UUID,
    deducted_invoice_id UUID REFERENCES vendor_invoice (id) ON DELETE RESTRICT,
    deducted_at         TIMESTAMPTZ,
    cancelled_at        TIMESTAMPTZ,
    cancel_reason       TEXT,
    version             BIGINT,
    created_at          TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ    NOT NULL DEFAULT now(),
    CONSTRAINT vendor_advance_no_unique UNIQUE (tenant_id, advance_no),
    CONSTRAINT vendor_advance_mode_valid CHECK (mode IN ('BANK', 'CASH', 'FUEL_SLIP', 'FUEL_CARD')),
    CONSTRAINT vendor_advance_status_valid CHECK (status IN
        ('PENDING_APPROVAL', 'APPROVED', 'PAID', 'DEDUCTED', 'REJECTED', 'CANCELLED')),
    CONSTRAINT vendor_advance_source_valid CHECK (freight_source IN ('BILL', 'ESTIMATE')),
    CONSTRAINT vendor_advance_amount_positive CHECK (amount > 0),
    CONSTRAINT vendor_advance_paid_whole CHECK (
        (status IN ('PAID', 'DEDUCTED')) = (paid_reference IS NOT NULL AND paid_on IS NOT NULL)),
    CONSTRAINT vendor_advance_deducted_whole CHECK ((status = 'DEDUCTED') = (deducted_invoice_id IS NOT NULL)),
    CONSTRAINT vendor_advance_cancelled_whole CHECK (
        (status = 'CANCELLED') = (cancelled_at IS NOT NULL AND cancel_reason IS NOT NULL))
);

-- One advance per mode per trip while it is alive; a withdrawn or rejected one
-- gives the mode back.
CREATE UNIQUE INDEX vendor_advance_mode_once_idx ON vendor_advance (tenant_id, trip_id, mode)
    WHERE status IN ('PENDING_APPROVAL', 'APPROVED', 'PAID', 'DEDUCTED');
CREATE INDEX vendor_advance_list_idx ON vendor_advance (tenant_id, created_at DESC, id DESC);
CREATE INDEX vendor_advance_trip_idx ON vendor_advance (tenant_id, trip_id, created_at, id);
CREATE INDEX vendor_advance_vendor_idx ON vendor_advance (tenant_id, vendor_partner_id, created_at DESC, id DESC);

-- ---------------------------------------------------------------------------
-- Number series for the new documents
-- ---------------------------------------------------------------------------

ALTER TABLE number_series DROP CONSTRAINT number_series_type_valid;
ALTER TABLE number_series
    ADD CONSTRAINT number_series_type_valid CHECK (document_type IN
        ('ORDER', 'CONSIGNMENT_NOTE', 'DOCKET', 'TRIP', 'GATE_PASS', 'LOAD', 'INVOICE', 'CONTRACT',
         'MANUAL_CHARGE', 'VENDOR_INVOICE', 'VENDOR_ADVANCE'));

-- ---------------------------------------------------------------------------
-- Permissions
-- ---------------------------------------------------------------------------

-- Keeping the catalogue, raising a charge and approving it are three rights:
-- the person who prices a charge is not the person who decides it stands.
-- Capturing vendor invoices and recording payments is one desk. A requester of
-- an advance is not the person who approves it.
INSERT INTO permission (id, code, resource, action)
VALUES (gen_random_uuid(), 'CHARGE_CATALOGUE_MANAGE', 'CHARGE_CATALOGUE', 'UPDATE'),
       (gen_random_uuid(), 'MANUAL_CHARGE_RECORD', 'MANUAL_CHARGE', 'CREATE'),
       (gen_random_uuid(), 'MANUAL_CHARGE_APPROVE', 'MANUAL_CHARGE', 'EXECUTE'),
       (gen_random_uuid(), 'PAYABLES_MANAGE', 'VENDOR_PAYABLE', 'UPDATE'),
       (gen_random_uuid(), 'ADVANCE_REQUEST', 'VENDOR_ADVANCE', 'CREATE'),
       (gen_random_uuid(), 'ADVANCE_APPROVE', 'VENDOR_ADVANCE', 'EXECUTE');

-- ---------------------------------------------------------------------------
-- Row-level security
-- ---------------------------------------------------------------------------

DO
$$
    DECLARE
        t TEXT;
    BEGIN
        FOREACH t IN ARRAY ARRAY [
            'charge_catalogue', 'manual_charge', 'vendor_invoice', 'vendor_invoice_bill', 'vendor_payment',
            'vendor_advance'
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
