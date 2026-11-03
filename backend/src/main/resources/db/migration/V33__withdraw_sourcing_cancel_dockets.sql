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

-- Withdrawing sourcing work, and cancelling docket receivables.

-- ---------------------------------------------------------------------------
-- One LIVE allocation per load
-- ---------------------------------------------------------------------------

-- V9 made (tenant_id, load_id) unique outright, so a load that had ever been
-- allocated could never be offered again: cancelling an award left the
-- cancelled row in the way. A cancelled allocation is history -- the offers
-- and the acceptance it holds answer "who was asked, and what happened" -- and
-- must stay, so the rule becomes what it was always for: two live allocations
-- for one load is how a trip is sold to two vendors. CANCELLED rows are
-- outside the rule; OFFERED, AWARDED and EXHAUSTED (which is terminal, and
-- needs a person) still block a second allocation.
ALTER TABLE allocation
    DROP CONSTRAINT allocation_load_unique;

CREATE UNIQUE INDEX allocation_live_load_unique
    ON allocation (tenant_id, load_id)
    WHERE status <> 'CANCELLED';

-- The history of a load's allocations, cancelled ones included, in the order
-- they were made. The unique index above is partial and cannot serve it.
CREATE INDEX allocation_load_idx
    ON allocation (tenant_id, load_id, created_at);

-- ---------------------------------------------------------------------------
-- A cancelled docket's receivable
-- ---------------------------------------------------------------------------

-- Planning announced a docket when it was booked and finance raised a
-- receivable from it, but nothing announced the cancellation, so a cancelled
-- shipment stayed billable. CANCELLED keeps the row and what was quoted on it,
-- so a dispute can still be argued from them; the reason (required for any
-- status but PRICED by docket_receivable_unpriced_explained) says why.
ALTER TABLE docket_receivable
    DROP CONSTRAINT docket_receivable_status_valid,
    ADD CONSTRAINT docket_receivable_status_valid
        CHECK (status IN ('PRICED', 'UNPRICED', 'CANCELLED'));
