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

-- Outbound webhooks: who wants to be told about which events, and every
-- delivery made to them.

-- The signing secret is stored as issued, not hashed: the server has to sign
-- every delivery with it, so it must be able to read it back. It is protected
-- the way every other tenant row is (row-level security, forced) and is never
-- returned by the API after the response that issued or rotated it.
CREATE TABLE webhook_subscription
(
    id                   UUID PRIMARY KEY,
    tenant_id            UUID        NOT NULL REFERENCES tenant (id) ON DELETE CASCADE,
    name                 TEXT        NOT NULL,
    url                  TEXT        NOT NULL,
    event_types          TEXT[]      NOT NULL,
    secret               TEXT        NOT NULL,
    active               BOOLEAN     NOT NULL DEFAULT TRUE,
    -- Set when the platform paused the subscription itself, cleared when a
    -- person reactivates it.
    paused_reason        TEXT,
    consecutive_failures INTEGER     NOT NULL DEFAULT 0,
    version              BIGINT,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT webhook_subscription_has_types CHECK (cardinality(event_types) > 0),
    CONSTRAINT webhook_subscription_failures_non_negative CHECK (consecutive_failures >= 0)
);

CREATE UNIQUE INDEX webhook_subscription_name_idx ON webhook_subscription (tenant_id, lower(name));
CREATE INDEX webhook_subscription_listing_idx ON webhook_subscription (tenant_id, created_at, id);

ALTER TABLE webhook_subscription ENABLE ROW LEVEL SECURITY;
ALTER TABLE webhook_subscription FORCE ROW LEVEL SECURITY;
CREATE POLICY webhook_subscription_isolation ON webhook_subscription
    USING (tenant_id = app_current_tenant())
    WITH CHECK (tenant_id = app_current_tenant());

-- One row per event per subscription: the outbox the dispatcher drains. The
-- event id is derived from the event itself, so the same event announced twice
-- (a listener republished after a restart) is one delivery, not two.
CREATE TABLE webhook_delivery
(
    id               UUID PRIMARY KEY,
    tenant_id        UUID        NOT NULL REFERENCES tenant (id) ON DELETE CASCADE,
    subscription_id  UUID        NOT NULL REFERENCES webhook_subscription (id) ON DELETE CASCADE,
    event_id         UUID        NOT NULL,
    event_type       TEXT        NOT NULL,
    payload          JSONB       NOT NULL,
    status           TEXT        NOT NULL DEFAULT 'PENDING',
    attempts         INTEGER     NOT NULL DEFAULT 0,
    next_attempt_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_status_code INTEGER,
    -- A truncated reason, never the receiver's response body.
    last_error       TEXT,
    delivered_at     TIMESTAMPTZ,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT webhook_delivery_status_valid CHECK (status IN ('PENDING', 'DELIVERED', 'FAILED')),
    CONSTRAINT webhook_delivery_attempts_non_negative CHECK (attempts >= 0),
    CONSTRAINT webhook_delivery_once_per_event UNIQUE (subscription_id, event_id)
);

CREATE INDEX webhook_delivery_due_idx ON webhook_delivery (tenant_id, next_attempt_at)
    WHERE status = 'PENDING';
CREATE INDEX webhook_delivery_listing_idx ON webhook_delivery (tenant_id, subscription_id, created_at DESC, id);

ALTER TABLE webhook_delivery ENABLE ROW LEVEL SECURITY;
ALTER TABLE webhook_delivery FORCE ROW LEVEL SECURITY;
CREATE POLICY webhook_delivery_isolation ON webhook_delivery
    USING (tenant_id = app_current_tenant())
    WITH CHECK (tenant_id = app_current_tenant());

INSERT INTO permission (id, code, resource, action)
VALUES (gen_random_uuid(), 'WEBHOOK_MANAGE', 'WEBHOOK', 'UPDATE'),
       (gen_random_uuid(), 'WEBHOOK_READ', 'WEBHOOK', 'READ');
