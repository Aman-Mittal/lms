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

-- Idempotency for state-changing requests.
--
-- Three things make this necessary rather than nice to have:
--
--   * Render's free instance sleeps after 15 minutes and clients retry the
--     request that woke it. Without deduplication, a retried order becomes two
--     orders.
--   * Telematics ingest is a batch endpoint over unreliable mobile links,
--     where retries are the norm, not the exception.
--   * Spring Modulith's event registry delivers at least once and republishes
--     incomplete events on restart, so any handler with a side effect must be
--     safe to run twice.
--
-- The stored response matters as much as the deduplication. A retry must
-- receive the original outcome, not 409; the client is retrying precisely
-- because it never learned what happened the first time.

CREATE TABLE idempotency_key
(
    tenant_id     UUID        NOT NULL REFERENCES tenant (id) ON DELETE CASCADE,
    -- Client-supplied via the Idempotency-Key header.
    key           TEXT        NOT NULL,
    -- SHA-256 of method + path + body. Guards against a client reusing a key
    -- for a different request, which is a client bug that would otherwise
    -- return someone else's response.
    request_hash  TEXT        NOT NULL,
    http_status   INT,
    response_body TEXT,
    -- IN_PROGRESS lets a concurrent duplicate be rejected while the first
    -- request is still running, rather than both proceeding.
    state         TEXT        NOT NULL DEFAULT 'IN_PROGRESS',
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at  TIMESTAMPTZ,
    PRIMARY KEY (tenant_id, key),
    CONSTRAINT idempotency_state_valid CHECK (state IN ('IN_PROGRESS', 'COMPLETED'))
);

-- Supports the retention sweep. Partial: only completed rows are ever pruned,
-- and an in-progress row that outlives its request is worth investigating.
--
-- Named *_sweep_idx because pruning runs platform-wide, not per tenant, so this
-- index deliberately does not lead with tenant_id. QueryPlanTest enforces that
-- rule for every other index and exempts only this suffix.
CREATE INDEX idempotency_key_created_sweep_idx
    ON idempotency_key (created_at) WHERE state = 'COMPLETED';

ALTER TABLE idempotency_key ENABLE ROW LEVEL SECURITY;
ALTER TABLE idempotency_key FORCE ROW LEVEL SECURITY;
CREATE POLICY idempotency_key_isolation ON idempotency_key
    USING (tenant_id = app_current_tenant())
    WITH CHECK (tenant_id = app_current_tenant());
