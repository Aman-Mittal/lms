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

-- Order numbers the platform assigns, and reading a record's history back.
--
-- An order raised without a number takes the next one from its tenant's
-- counter for the year: ORD-2026-000001, ORD-2026-000002, ... One row per
-- tenant and year, advanced with INSERT ... ON CONFLICT DO UPDATE ... RETURNING
-- inside the raising transaction. The row lock that statement takes is what
-- makes concurrent raises safe: the second waits for the first to commit or
-- roll back, so no number is handed out twice and a rolled-back raise gives
-- its number back.
CREATE TABLE order_number_counter
(
    tenant_id  UUID        NOT NULL REFERENCES tenant (id) ON DELETE CASCADE,
    year       INTEGER     NOT NULL,
    last_value BIGINT      NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, year),
    CONSTRAINT order_number_counter_positive CHECK (last_value > 0)
);

ALTER TABLE order_number_counter ENABLE ROW LEVEL SECURITY;
ALTER TABLE order_number_counter FORCE ROW LEVEL SECURITY;
CREATE POLICY order_number_counter_isolation ON order_number_counter
    USING (tenant_id = app_current_tenant())
    WITH CHECK (tenant_id = app_current_tenant());

-- GET /orders/{id}/history and its siblings read one record's audit rows
-- newest first, a page at a time, keyed on (occurred_at, id). The V2 index on
-- (tenant_id, resource_type, resource_id) finds the rows but has to sort them;
-- this one serves the seek and the order, and replaces it as a strict
-- extension of the same prefix.
CREATE INDEX audit_log_resource_history_idx
    ON audit_log (tenant_id, resource_type, resource_id, occurred_at DESC, id DESC);
DROP INDEX audit_log_resource_idx;
