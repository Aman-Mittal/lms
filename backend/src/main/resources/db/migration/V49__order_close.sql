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


-- Closing an order by hand.
--
-- Until now an order ended only by being cancelled or by having every
-- consignment cut from it delivered (FULFILLED). CLOSED is a third end: a
-- person (or, within the tenant's tolerance, the platform) decided that what
-- was not carried never will be. How it was closed is kept beside it, because
-- a normal close, a short close and a forced one mean different things to
-- whoever reads the order later.

ALTER TABLE sales_order
    DROP CONSTRAINT sales_order_status_valid,
    ADD CONSTRAINT sales_order_status_valid CHECK (status IN
        ('DRAFT', 'VALIDATED', 'PARTIALLY_PLANNED', 'FULLY_PLANNED', 'FULFILLED', 'CANCELLED', 'CLOSED')),
    ADD COLUMN close_kind   TEXT,
    ADD COLUMN close_reason TEXT,
    ADD COLUMN closed_at    TIMESTAMPTZ,
    ADD CONSTRAINT sales_order_close_kind_valid CHECK (
        close_kind IS NULL OR close_kind IN ('NORMAL', 'SHORT', 'FORCE', 'AUTO')),
    -- A closed order says how, and only a closed order does.
    ADD CONSTRAINT sales_order_closed_has_kind CHECK (
        (status = 'CLOSED') = (close_kind IS NOT NULL AND closed_at IS NOT NULL)),
    ADD CONSTRAINT sales_order_close_reason_bounded CHECK (
        close_reason IS NULL OR length(close_reason) <= 500);

-- A line is closed on its own (short-closing one line) or with its order. A
-- closed line that was never planned is never planned afterwards.
ALTER TABLE order_line
    ADD COLUMN close_kind   TEXT,
    ADD COLUMN close_reason TEXT,
    ADD COLUMN closed_at    TIMESTAMPTZ,
    ADD CONSTRAINT order_line_close_kind_valid CHECK (
        close_kind IS NULL OR close_kind IN ('NORMAL', 'SHORT', 'FORCE', 'AUTO')),
    ADD CONSTRAINT order_line_closed_has_kind CHECK (
        (close_kind IS NULL) = (closed_at IS NULL)),
    ADD CONSTRAINT order_line_close_reason_bounded CHECK (
        close_reason IS NULL OR length(close_reason) <= 500);

-- ---------------------------------------------------------------------------
-- A tenant's close policy
-- ---------------------------------------------------------------------------

-- One row per tenant that has set anything; a tenant with none gets the
-- defaults (no tolerance, automatic fulfilment on), so nothing is seeded.
CREATE TABLE order_close_setting
(
    id                UUID PRIMARY KEY,
    tenant_id         UUID          NOT NULL REFERENCES tenant (id) ON DELETE CASCADE,
    -- Share of an order's quantity that may be left unplanned when its last
    -- consignment is delivered, and the order still closes by itself.
    tolerance_percent NUMERIC(5, 2) NOT NULL DEFAULT 0,
    -- False: delivering the last consignment leaves the order open; a person closes it.
    auto_fulfil       BOOLEAN       NOT NULL DEFAULT TRUE,
    version           BIGINT,
    created_at        TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT order_close_setting_tolerance_range CHECK (tolerance_percent BETWEEN 0 AND 100)
);

CREATE UNIQUE INDEX order_close_setting_tenant_idx ON order_close_setting (tenant_id);

ALTER TABLE order_close_setting ENABLE ROW LEVEL SECURITY;
ALTER TABLE order_close_setting FORCE ROW LEVEL SECURITY;
CREATE POLICY order_close_setting_isolation ON order_close_setting
    USING (tenant_id = app_current_tenant())
    WITH CHECK (tenant_id = app_current_tenant());

-- Forcing a close ends an order whatever its freight is doing, so it is its
-- own right rather than part of ORDER_UPDATE.
INSERT INTO permission (id, code, resource, action)
VALUES (gen_random_uuid(), 'ORDER_FORCE_CLOSE', 'ORDER', 'EXECUTE');
