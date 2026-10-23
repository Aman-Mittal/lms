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
-- Cancelling what a back-order still owes
-- ---------------------------------------------------------------------------

-- A customer who no longer wants the remainder used to leave the order
-- BACK_ORDERED for ever, still offered to every wave and still counted in the
-- back-order listing. BACK_ORDER_CANCELLED is the terminal state that says the
-- goods already dispatched are all the order will ever send. The unfilled
-- quantities stay on the lines as the record of what was asked for.
ALTER TABLE outbound_order
    DROP CONSTRAINT outbound_order_status_valid,
    ADD CONSTRAINT outbound_order_status_valid CHECK (status IN (
        'OPEN', 'PICKED', 'SHORT_PICKED', 'PACKED', 'DISPATCHED', 'BACK_ORDERED',
        'BACK_ORDER_CANCELLED'));

-- Wave listing: a warehouse's waves, newest first, narrowed by status.
CREATE INDEX pick_wave_warehouse_status_idx
    ON pick_wave (tenant_id, warehouse_id, status, created_at, id);
