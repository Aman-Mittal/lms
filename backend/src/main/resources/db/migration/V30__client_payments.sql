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
-- ---------------------------------------------------------------------------
-- Payments received from a client
-- ---------------------------------------------------------------------------

-- A client statement showed what had been invoiced less what had been
-- credited, which is only what the client owed on the day each document was
-- issued. A receipt is the third document on that ledger. It is recorded
-- against the client rather than against one invoice: clients pay lump sums
-- and remittance advice is rarely precise, and matching a receipt to the
-- invoices it settles is a reconciliation step this model does not take. The
-- balance is therefore the client's whole position, which is what a statement
-- is for.
--
-- Like an invoice and a credit note it is a record of something that
-- happened, so it is never edited or removed; a receipt entered in error is
-- reversed by a later entry, not erased.
CREATE TABLE client_payment
(
    id                UUID PRIMARY KEY,
    tenant_id         UUID           NOT NULL REFERENCES tenant (id) ON DELETE CASCADE,
    client_partner_id UUID           NOT NULL REFERENCES business_partner (id) ON DELETE RESTRICT,
    payment_no        TEXT           NOT NULL,
    currency          TEXT           NOT NULL,
    amount            NUMERIC(14, 2) NOT NULL,
    -- When the money arrived, as the bank reports it: a date, not the moment
    -- somebody typed it in.
    received_on       DATE           NOT NULL,
    -- The client's own remittance or bank reference.
    reference         TEXT           NOT NULL,
    recorded_by       TEXT           NOT NULL,
    recorded_at       TIMESTAMPTZ    NOT NULL,
    version           BIGINT,
    created_at        TIMESTAMPTZ    NOT NULL DEFAULT now(),
    CONSTRAINT client_payment_no_unique UNIQUE (tenant_id, payment_no),
    CONSTRAINT client_payment_amount_positive CHECK (amount > 0),
    CONSTRAINT client_payment_reference_given CHECK (length(btrim(reference)) > 0)
);

-- The same remittance must not be entered twice: a retry after a timeout, or
-- two clerks working one bank statement, would otherwise credit the client
-- twice.
CREATE UNIQUE INDEX client_payment_reference_unique
    ON client_payment (tenant_id, client_partner_id, reference);
CREATE INDEX client_payment_statement_idx
    ON client_payment (tenant_id, client_partner_id, recorded_at, id);

CREATE RULE client_payment_no_update AS ON UPDATE TO client_payment DO INSTEAD NOTHING;
CREATE RULE client_payment_no_delete AS ON DELETE TO client_payment DO INSTEAD NOTHING;

ALTER TABLE client_payment ENABLE ROW LEVEL SECURITY;
ALTER TABLE client_payment FORCE ROW LEVEL SECURITY;
CREATE POLICY client_payment_isolation ON client_payment
    USING (tenant_id = app_current_tenant())
    WITH CHECK (tenant_id = app_current_tenant());
