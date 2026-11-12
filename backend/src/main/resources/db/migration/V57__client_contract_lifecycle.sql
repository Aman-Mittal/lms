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


-- Client contract lifecycle: draft, verification, end dates and extension.
--
-- A contract version used to be published the moment one person posted it, and
-- was final from then on. It now starts as a DRAFT that its maker can edit and
-- delete, is asked to be verified (PENDING_VERIFICATION, frozen, through the
-- approvals module) and only becomes ACTIVE -- the old "published" meaning,
-- immutable, closing the incumbent -- when somebody else approves it. A
-- rejection sends it back to DRAFT with the reason.
--
--   * status is stored: DRAFT, PENDING_VERIFICATION, ACTIVE. EXPIRED is not --
--     it is derived from valid_till on every read, so no job keeps it fresh and
--     nothing can go stale.
--   * valid_till is the optional last day of the contract. effective_to stays
--     what it was: the day before a successor starts. A contract is in force
--     on a day when it is ACTIVE, has started, is not closed by a successor
--     and has not passed valid_till.
--   * Every row that exists today was published, so it is ACTIVE.
--
-- What the immutability trigger now allows:
--   DRAFT                 any change (it has never applied to anything), and
--                         submission or direct activation;
--   PENDING_VERIFICATION  no change of content, only the way out (back to
--                         DRAFT, or forward to ACTIVE);
--   ACTIVE                what it always allowed (being closed by a successor)
--                         and one thing more: valid_till moved LATER, and only
--                         inside a transaction that said so through
--                         app.contract_extension, which only the approved
--                         extension path does.

ALTER TABLE client_contract
    ADD COLUMN status           TEXT NOT NULL DEFAULT 'ACTIVE',
    ADD COLUMN valid_till       DATE,
    ADD COLUMN rejection_reason TEXT;

-- From here on every insert names its status; nothing may default into force.
ALTER TABLE client_contract
    ALTER COLUMN status DROP DEFAULT;

ALTER TABLE client_contract
    ADD CONSTRAINT client_contract_status_valid CHECK (status IN ('DRAFT', 'PENDING_VERIFICATION', 'ACTIVE')),
    ADD CONSTRAINT client_contract_valid_till_sane CHECK (valid_till IS NULL OR valid_till >= effective_from),
    -- Only a contract that is in force can have been closed by a successor.
    ADD CONSTRAINT client_contract_closed_only_when_active CHECK (status = 'ACTIVE' OR effective_to IS NULL),
    ADD CONSTRAINT client_contract_rejection_reason_bounded CHECK (
        rejection_reason IS NULL OR length(rejection_reason) <= 500);

-- One open version per client counts only versions that are in force: any
-- number of drafts may wait beside it.
DROP INDEX client_contract_open_version_idx;
CREATE UNIQUE INDEX client_contract_open_version_idx
    ON client_contract (tenant_id, client_partner_id)
    WHERE effective_to IS NULL AND status = 'ACTIVE';

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
                NEW.warehouse_tax_rate_pct, NEW.storage_units_per_pallet, NEW.bill_on_dispatch)
            IS DISTINCT FROM
           ROW (OLD.org_unit_id, OLD.code, OLD.client_partner_id, OLD.currency, OLD.effective_from,
                OLD.effective_to, OLD.valid_till, OLD.transport_rate_per_kg, OLD.transport_minimum_charge,
                OLD.storage_rate_per_pallet_day, OLD.handling_in_rate_per_pallet,
                OLD.handling_out_rate_per_pallet, OLD.transport_tax_rate_pct,
                OLD.warehouse_tax_rate_pct, OLD.storage_units_per_pallet, OLD.bill_on_dispatch)
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
            NEW.warehouse_tax_rate_pct, NEW.storage_units_per_pallet, NEW.bill_on_dispatch)
        IS DISTINCT FROM
       ROW (OLD.org_unit_id, OLD.code, OLD.client_partner_id, OLD.currency,
            OLD.effective_from, OLD.transport_rate_per_kg, OLD.transport_minimum_charge,
            OLD.storage_rate_per_pallet_day, OLD.handling_in_rate_per_pallet,
            OLD.handling_out_rate_per_pallet, OLD.transport_tax_rate_pct,
            OLD.warehouse_tax_rate_pct, OLD.storage_units_per_pallet, OLD.bill_on_dispatch)
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

-- Only a draft may be deleted, together with its value-added service rates.
-- Everything else keeps the silent refusal that was there before (and the
-- demo reset's way past it).
CREATE OR REPLACE RULE client_contract_no_delete AS ON DELETE TO client_contract
    WHERE NOT demo_reset_active(OLD.tenant_id) AND OLD.status <> 'DRAFT' DO INSTEAD NOTHING;
CREATE OR REPLACE RULE client_contract_service_rate_no_delete AS ON DELETE TO client_contract_service_rate
    WHERE NOT demo_reset_active(OLD.tenant_id)
        AND NOT EXISTS (SELECT 1 FROM client_contract k WHERE k.id = OLD.contract_id AND k.status = 'DRAFT')
    DO INSTEAD NOTHING;

-- Contracts take a number from a CONTRACT series when the tenant has one.
ALTER TABLE number_series DROP CONSTRAINT number_series_type_valid;
ALTER TABLE number_series
    ADD CONSTRAINT number_series_type_valid CHECK (document_type IN
        ('ORDER', 'CONSIGNMENT_NOTE', 'DOCKET', 'TRIP', 'GATE_PASS', 'LOAD', 'INVOICE', 'CONTRACT'));

-- Verifying a contract is a right of its own: whoever drafts and asks
-- (CLIENT_CONTRACT_MANAGE) does not thereby approve.
INSERT INTO permission (id, code, resource, action)
VALUES (gen_random_uuid(), 'CLIENT_CONTRACT_APPROVE', 'CLIENT_CONTRACT', 'EXECUTE');
