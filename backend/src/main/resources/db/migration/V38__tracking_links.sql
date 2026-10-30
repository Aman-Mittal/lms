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

-- Customer tracking links: a token that lets someone with no account follow one
-- consignment.
--
-- Only a SHA-256 hash of the token is stored, so a leaked table hands out no
-- working link. The token is 256 bits of randomness, which is why a plain hash
-- (rather than a slow password hash) is the right tool: there is nothing to
-- brute-force.
CREATE TABLE tracking_link
(
    id             UUID PRIMARY KEY,
    tenant_id      UUID        NOT NULL REFERENCES tenant (id) ON DELETE CASCADE,
    consignment_id UUID        NOT NULL REFERENCES consignment (id) ON DELETE RESTRICT,
    token_hash     TEXT        NOT NULL,
    expires_at     TIMESTAMPTZ NOT NULL,
    revoked_at     TIMESTAMPTZ,
    version        BIGINT,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT tracking_link_expires_after_creation CHECK (expires_at > created_at)
);

-- Global, not per tenant: the public route knows the token and nothing else, so
-- the lookup cannot name a tenant first.
CREATE UNIQUE INDEX tracking_link_token_idx ON tracking_link (token_hash);
CREATE INDEX tracking_link_consignment_idx ON tracking_link (tenant_id, consignment_id, created_at);

-- ENABLEd but, like the tenant table, deliberately not FORCEd. The one lookup
-- that has no tenant yet -- resolve_tracking_link() below -- runs as the table
-- owner, and FORCE would filter it by a scope that does not exist. Isolation is
-- not weakened: every application statement runs as lms_app (see
-- V3__application_role.sql), to which this policy applies in full.
ALTER TABLE tracking_link ENABLE ROW LEVEL SECURITY;
CREATE POLICY tracking_link_isolation ON tracking_link
    USING (tenant_id = app_current_tenant())
    WITH CHECK (tenant_id = app_current_tenant());

-- The public route's single, narrow escape hatch, for the same reason
-- resolve_tenant_by_code() (V2) and app_active_tenant_ids() (V9) exist: finding
-- out which tenant a token belongs to is a precondition of establishing scope,
-- so it cannot itself require scope.
--
-- It returns two identifiers and only for a link that is neither revoked nor
-- expired, so an unknown, revoked and expired token are indistinguishable to the
-- caller. No business row becomes reachable through it: the caller still has to
-- bind the tenant and read through row-level security like everyone else.
CREATE OR REPLACE FUNCTION resolve_tracking_link(p_token_hash TEXT)
    RETURNS TABLE (tenant_id uuid, consignment_id uuid)
    LANGUAGE sql
    STABLE
    SECURITY DEFINER
    SET search_path = public, pg_temp
    AS $$
    SELECT l.tenant_id, l.consignment_id
      FROM tracking_link l
     WHERE l.token_hash = p_token_hash
       AND l.revoked_at IS NULL
       AND l.expires_at > now()
$$;

REVOKE ALL ON FUNCTION resolve_tracking_link(TEXT) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION resolve_tracking_link(TEXT) TO lms_app;

INSERT INTO permission (id, code, resource, action)
VALUES (gen_random_uuid(), 'TRACKING_LINK_MANAGE', 'TRACKING_LINK', 'UPDATE');
