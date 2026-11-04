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


-- A proof of delivery decides money as well as status.
--
-- Completing a trip still does not wait for a proof. What waits is money: the
-- carrier is not paid for a trip until every consignment on it has a clean
-- proof (or somebody with the right releases the hold), and a client is not
-- invoiced for a consignment until it has a confirmed proof (unless its
-- contract bills on dispatch, or somebody marks it billable by hand).

-- ---------------------------------------------------------------------------
-- The goods' condition on the proof
-- ---------------------------------------------------------------------------

-- CLEAN for every proof recorded before this, which is what they were taken to
-- be. A shortage or damage needs a remark saying what was wrong.
ALTER TABLE delivery_proof
    ADD COLUMN goods_condition TEXT NOT NULL DEFAULT 'CLEAN',
    ADD COLUMN remark          TEXT,
    ADD CONSTRAINT delivery_proof_condition_valid CHECK (goods_condition IN ('CLEAN', 'SHORTAGE', 'DAMAGE')),
    ADD CONSTRAINT delivery_proof_exception_has_remark CHECK (
        goods_condition = 'CLEAN' OR (remark IS NOT NULL AND length(btrim(remark)) > 0)),
    ADD CONSTRAINT delivery_proof_remark_bounded CHECK (remark IS NULL OR length(remark) <= 500);

-- ---------------------------------------------------------------------------
-- A settlement hold for outstanding proofs
-- ---------------------------------------------------------------------------

-- The same table as the control tower's holds, so one question -- is anything
-- holding this trip? -- still answers for both. A proof hold is keyed on an id
-- derived from the trip, so a trip has at most one, however often its
-- completion is delivered. Released either when the last proof arrives clean
-- or by a person, who must say why.
ALTER TABLE settlement_hold
    ADD COLUMN reason       TEXT NOT NULL DEFAULT 'EXCEPTION',
    ADD COLUMN released_by  UUID,
    ADD COLUMN release_note TEXT,
    ADD CONSTRAINT settlement_hold_reason_valid CHECK (reason IN ('EXCEPTION', 'PROOF_OF_DELIVERY'));

-- Is a proof hold still open on this trip? Asked whenever a proof arrives.
CREATE INDEX settlement_hold_open_proof_idx
    ON settlement_hold (tenant_id, trip_id)
    WHERE released_at IS NULL AND reason = 'PROOF_OF_DELIVERY';

-- Releasing a proof hold by hand is its own right: it pays a carrier for a
-- delivery nobody has proved clean.
INSERT INTO permission (id, code, resource, action)
VALUES (gen_random_uuid(), 'SETTLEMENT_HOLD_RELEASE', 'FREIGHT_BILL', 'EXECUTE');

-- ---------------------------------------------------------------------------
-- When a client may be invoiced
-- ---------------------------------------------------------------------------

-- False: a client is invoiced for a consignment once it has a confirmed proof.
-- True: as soon as it is dispatched (advance billing).
ALTER TABLE client_contract
    ADD COLUMN bill_on_dispatch BOOLEAN NOT NULL DEFAULT FALSE;

-- The billing rule is a term of the contract like any rate, so a published
-- version cannot change it either.
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
            NEW.warehouse_tax_rate_pct, NEW.storage_units_per_pallet, NEW.bill_on_dispatch)
        IS DISTINCT FROM
       ROW (OLD.tenant_id, OLD.org_unit_id, OLD.code, OLD.client_partner_id, OLD.currency,
            OLD.effective_from, OLD.transport_rate_per_kg, OLD.transport_minimum_charge,
            OLD.storage_rate_per_pallet_day, OLD.handling_in_rate_per_pallet,
            OLD.handling_out_rate_per_pallet, OLD.transport_tax_rate_pct,
            OLD.warehouse_tax_rate_pct, OLD.storage_units_per_pallet, OLD.bill_on_dispatch)
    THEN
        RAISE EXCEPTION 'Client contract % cannot be edited; publish a new version instead', OLD.code
            USING ERRCODE = 'restrict_violation';
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- A consignment someone marked billable by hand, without waiting for its
-- proof. One row per consignment: marking it again changes nothing. Owned by
-- client billing; no foreign key to the consignment beyond the tenant's, so a
-- mark is never what stops a consignment's history from being cleaned up.
CREATE TABLE consignment_billing_mark
(
    tenant_id      UUID        NOT NULL REFERENCES tenant (id) ON DELETE CASCADE,
    consignment_id UUID        NOT NULL REFERENCES consignment (id) ON DELETE CASCADE,
    marked_at      TIMESTAMPTZ NOT NULL,
    marked_by      UUID,
    reason         TEXT        NOT NULL,
    PRIMARY KEY (tenant_id, consignment_id),
    CONSTRAINT consignment_billing_mark_reason_present CHECK (length(btrim(reason)) > 0),
    CONSTRAINT consignment_billing_mark_reason_bounded CHECK (length(reason) <= 500)
);

ALTER TABLE consignment_billing_mark ENABLE ROW LEVEL SECURITY;
ALTER TABLE consignment_billing_mark FORCE ROW LEVEL SECURITY;
CREATE POLICY consignment_billing_mark_isolation ON consignment_billing_mark
    USING (tenant_id = app_current_tenant())
    WITH CHECK (tenant_id = app_current_tenant());
