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

-- Issue #247: switching configuration off and on, and voiding a vendor invoice.
--
-- Nothing here is deleted. An organisational unit, a warehouse bin, a SKU and a
-- routing-guide vendor gain the active flag the allocation rules, charge
-- catalogue, webhooks, custom fields and routing guides already had; each is
-- switched off with a reason (audited) and back on again, and every row stays
-- because orders, stock, trips and allocations keep pointing at them.
--
-- A vendor invoice gains a VOID status: voided with a reason before anything is
-- paid against it, which releases the bills and advances it held.

ALTER TABLE org_unit ADD COLUMN active BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE warehouse_bin ADD COLUMN active BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE sku ADD COLUMN active BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE routing_guide_entry ADD COLUMN active BOOLEAN NOT NULL DEFAULT TRUE;

ALTER TABLE vendor_invoice
    ADD COLUMN voided_at  TIMESTAMPTZ,
    ADD COLUMN void_reason TEXT,
    DROP CONSTRAINT vendor_invoice_status_valid,
    ADD CONSTRAINT vendor_invoice_status_valid
        CHECK (status IN ('OPEN', 'PARTIALLY_PAID', 'PAID', 'VOID')),
    ADD CONSTRAINT vendor_invoice_void_whole
        CHECK ((status = 'VOID') = (voided_at IS NOT NULL AND void_reason IS NOT NULL));

-- A voided invoice is not owed: out of the overdue list's index as a paid one is.
DROP INDEX vendor_invoice_due_idx;
CREATE INDEX vendor_invoice_due_idx ON vendor_invoice (tenant_id, due_date, id)
    WHERE status NOT IN ('PAID', 'VOID');
