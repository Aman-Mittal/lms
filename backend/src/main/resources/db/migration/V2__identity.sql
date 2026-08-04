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

-- Identity, access and multi-tenancy foundations (vision document 2.1, 3.1).
--
-- Read DOCS/adr/0005 before changing anything in this file. The row-level
-- security here is not decoration: it is what makes a forgotten WHERE clause
-- return zero rows instead of another tenant's data.

-- ---------------------------------------------------------------------------
-- Tenant scope helpers
-- ---------------------------------------------------------------------------

-- Reads the tenant published onto the connection by
-- TenantAwareTransactionManager.
--
-- NULLIF matters more than it looks. The transaction manager writes an empty
-- string when no tenant is bound, and ''::uuid raises rather than yielding
-- NULL. Mapping empty to NULL makes every policy below evaluate to NULL --
-- and therefore filter the row out -- which is the fail-safe direction.
CREATE OR REPLACE FUNCTION app_current_tenant() RETURNS uuid
    LANGUAGE sql
    STABLE
    AS $$
    SELECT NULLIF(current_setting('app.tenant_id', true), '')::uuid
$$;

-- ---------------------------------------------------------------------------
-- Tenant
-- ---------------------------------------------------------------------------

CREATE TABLE tenant
(
    id         UUID PRIMARY KEY,
    code       TEXT        NOT NULL,
    name       TEXT        NOT NULL,
    status     TEXT        NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT tenant_code_unique UNIQUE (code),
    CONSTRAINT tenant_status_valid CHECK (status IN ('ACTIVE', 'SUSPENDED', 'CLOSED'))
);

-- Login is a bootstrapping problem: the caller supplies a tenant code, but the
-- tenant is not yet known, so no scope is set and RLS would hide every row.
--
-- Rather than leaving the tenant table unprotected, this SECURITY DEFINER
-- function is the single, narrow, auditable escape hatch. It returns an id and
-- nothing else, and it is the only route into the table without a scope.
CREATE OR REPLACE FUNCTION resolve_tenant_by_code(p_code TEXT) RETURNS uuid
    LANGUAGE sql
    STABLE
    SECURITY DEFINER
    SET search_path = public, pg_temp
    AS $$
    SELECT id FROM tenant WHERE code = p_code AND status = 'ACTIVE'
$$;

-- ---------------------------------------------------------------------------
-- Organisational hierarchy: HQ -> LOB -> Region -> Branch -> Hub
-- ---------------------------------------------------------------------------

CREATE TABLE org_unit
(
    id         UUID PRIMARY KEY,
    tenant_id  UUID        NOT NULL REFERENCES tenant (id) ON DELETE CASCADE,
    parent_id  UUID REFERENCES org_unit (id) ON DELETE RESTRICT,
    slug       TEXT        NOT NULL,
    -- Materialised ancestry, e.g. '/hq/north/branch-7/'. Top-down visibility is
    -- a prefix match against this, which avoids both the ltree extension and a
    -- recursive CTE on the request path. See DOCS/adr/0005.
    path       TEXT        NOT NULL,
    name       TEXT        NOT NULL,
    unit_type  TEXT        NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT org_unit_path_unique UNIQUE (tenant_id, path),
    CONSTRAINT org_unit_type_valid CHECK (unit_type IN ('HQ', 'LOB', 'REGION', 'BRANCH', 'HUB')),
    -- The path separator is reserved, and LIKE gives '%' and '_' meaning.
    -- Restricting the character set is simpler and safer than escaping at every
    -- call site.
    CONSTRAINT org_unit_slug_safe CHECK (slug ~ '^[a-z0-9][a-z0-9-]{0,62}$'),
    CONSTRAINT org_unit_path_shape CHECK (path ~ '^/([a-z0-9][a-z0-9-]*/)+$')
);

-- text_pattern_ops so that `path LIKE :prefix || '%'` uses the index regardless
-- of the database's collation.
CREATE INDEX org_unit_path_prefix_idx ON org_unit (tenant_id, path text_pattern_ops);
CREATE INDEX org_unit_parent_idx ON org_unit (tenant_id, parent_id);

-- ---------------------------------------------------------------------------
-- Users
-- ---------------------------------------------------------------------------

CREATE TABLE app_user
(
    id            UUID PRIMARY KEY,
    tenant_id     UUID        NOT NULL REFERENCES tenant (id) ON DELETE CASCADE,
    org_unit_id   UUID        NOT NULL REFERENCES org_unit (id) ON DELETE RESTRICT,
    email         TEXT        NOT NULL,
    password_hash TEXT        NOT NULL,
    full_name     TEXT        NOT NULL,
    status        TEXT        NOT NULL DEFAULT 'ACTIVE',
    last_login_at TIMESTAMPTZ,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- Scoped to the tenant, not global: the same person may hold accounts with
    -- two different customers of the platform.
    CONSTRAINT app_user_email_unique UNIQUE (tenant_id, email),
    CONSTRAINT app_user_status_valid CHECK (status IN ('ACTIVE', 'SUSPENDED', 'LOCKED'))
);

CREATE INDEX app_user_org_idx ON app_user (tenant_id, org_unit_id);

-- Login looks users up case-insensitively, so the index has to match the
-- expression. Without this the unique constraint on (tenant_id, email) still
-- serves the tenant predicate, but lower(email) degrades to a filter over every
-- user in the tenant -- fine at ten users, not at ten thousand.
CREATE INDEX app_user_email_lower_idx ON app_user (tenant_id, lower(email));

-- ---------------------------------------------------------------------------
-- RBAC
-- ---------------------------------------------------------------------------

-- Permissions are platform-wide vocabulary, not tenant data: deliberately not
-- tenant-scoped and deliberately without RLS.
CREATE TABLE permission
(
    id       UUID PRIMARY KEY,
    code     TEXT NOT NULL,
    resource TEXT NOT NULL,
    action   TEXT NOT NULL,
    CONSTRAINT permission_code_unique UNIQUE (code),
    CONSTRAINT permission_action_valid CHECK (action IN ('CREATE', 'READ', 'UPDATE', 'DELETE', 'EXECUTE'))
);

CREATE TABLE role
(
    id         UUID PRIMARY KEY,
    tenant_id  UUID        NOT NULL REFERENCES tenant (id) ON DELETE CASCADE,
    code       TEXT        NOT NULL,
    name       TEXT        NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT role_code_unique UNIQUE (tenant_id, code)
);

CREATE TABLE role_permission
(
    tenant_id     UUID NOT NULL REFERENCES tenant (id) ON DELETE CASCADE,
    role_id       UUID NOT NULL REFERENCES role (id) ON DELETE CASCADE,
    permission_id UUID NOT NULL REFERENCES permission (id) ON DELETE CASCADE,
    PRIMARY KEY (role_id, permission_id)
);

CREATE TABLE user_role
(
    tenant_id UUID NOT NULL REFERENCES tenant (id) ON DELETE CASCADE,
    user_id   UUID NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    role_id   UUID NOT NULL REFERENCES role (id) ON DELETE CASCADE,
    PRIMARY KEY (user_id, role_id)
);

CREATE INDEX user_role_role_idx ON user_role (tenant_id, role_id);

-- ---------------------------------------------------------------------------
-- Refresh tokens
-- ---------------------------------------------------------------------------

CREATE TABLE refresh_token
(
    id                 UUID PRIMARY KEY,
    tenant_id          UUID        NOT NULL REFERENCES tenant (id) ON DELETE CASCADE,
    user_id            UUID        NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    -- The token itself is never stored. A database disclosure must not hand the
    -- attacker usable credentials.
    token_hash         TEXT        NOT NULL,
    -- Vision document 3.1: driver refresh tokens are long-lived and bound to a
    -- device fingerprint, because drivers authenticate from low-connectivity
    -- areas and cannot re-authenticate on demand.
    device_fingerprint TEXT,
    issued_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at         TIMESTAMPTZ NOT NULL,
    revoked_at         TIMESTAMPTZ,
    CONSTRAINT refresh_token_hash_unique UNIQUE (token_hash)
);

CREATE INDEX refresh_token_user_idx ON refresh_token (tenant_id, user_id);
-- Named *_sweep_idx to mark it as a deliberate cross-tenant maintenance index.
-- It serves the housekeeping delete of expired tokens, which runs platform-wide
-- rather than per tenant, so leading with tenant_id would make it useless for
-- its only purpose. QueryPlanTest exempts this suffix and nothing else.
CREATE INDEX refresh_token_expiry_sweep_idx ON refresh_token (expires_at) WHERE revoked_at IS NULL;

-- ---------------------------------------------------------------------------
-- Audit log (vision document 3.1)
-- ---------------------------------------------------------------------------

CREATE TABLE audit_log
(
    id            UUID PRIMARY KEY,
    tenant_id     UUID        NOT NULL REFERENCES tenant (id) ON DELETE CASCADE,
    actor_id      UUID,
    action        TEXT        NOT NULL,
    resource_type TEXT        NOT NULL,
    resource_id   UUID,
    ip_address    TEXT,
    correlation_id TEXT,
    -- Full JSON snapshots either side of the mutation, as the vision document
    -- requires. JSONB rather than TEXT so the log is queryable during an
    -- investigation.
    before_state  JSONB,
    after_state   JSONB,
    occurred_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX audit_log_resource_idx ON audit_log (tenant_id, resource_type, resource_id);
CREATE INDEX audit_log_occurred_idx ON audit_log (tenant_id, occurred_at DESC);
CREATE INDEX audit_log_actor_idx ON audit_log (tenant_id, actor_id, occurred_at DESC);

-- Append-only, as required. Revoking UPDATE and DELETE is the enforceable part;
-- a rule that only exists in application code is not an audit trail.
CREATE RULE audit_log_no_update AS ON UPDATE TO audit_log DO INSTEAD NOTHING;
CREATE RULE audit_log_no_delete AS ON DELETE TO audit_log DO INSTEAD NOTHING;

-- ---------------------------------------------------------------------------
-- Row-level security
-- ---------------------------------------------------------------------------

-- FORCE, not merely ENABLE.
--
-- Postgres exempts a table's owner from its own RLS policies. On Render's free
-- plan the application connects as the same role that owns the schema, so
-- ENABLE alone would leave every policy below silently inert -- the most
-- dangerous possible failure, because nothing misbehaves until the day two
-- tenants exist. FORCE applies the policies to the owner as well.
--
-- A superuser still bypasses RLS. The application must never connect as one,
-- and RowLevelSecurityIntegrationTest asserts that isolation actually holds
-- rather than trusting that it was configured.

DO
$$
    DECLARE
        t TEXT;
    BEGIN
        FOREACH t IN ARRAY ARRAY [
            'org_unit', 'app_user', 'role', 'role_permission',
            'user_role', 'refresh_token', 'audit_log'
            ]
            LOOP
                EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
                EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', t);
            END LOOP;
    END
$$;

-- The tenant table is ENABLEd but deliberately NOT FORCEd.
--
-- FORCE would apply the policy to the table owner as well, and the owner is
-- exactly who resolve_tenant_by_code() runs as. Forcing it would filter the
-- SECURITY DEFINER lookup by a tenant scope that does not exist yet, breaking
-- login entirely -- the function would return nothing, every login would fail,
-- and the cause would be several layers away from the symptom.
--
-- Isolation is not weakened. Application statements run as lms_app (see
-- V3__application_role.sql), which is neither superuser nor owner, so the
-- policy below applies to them in full. The owner identity is reachable only
-- by migrations and by that one narrow function.
ALTER TABLE tenant ENABLE ROW LEVEL SECURITY;

-- The tenant table keys on its own primary key; everything else on tenant_id.
CREATE POLICY tenant_isolation ON tenant
    USING (id = app_current_tenant())
    WITH CHECK (id = app_current_tenant());

DO
$$
    DECLARE
        t TEXT;
    BEGIN
        FOREACH t IN ARRAY ARRAY [
            'org_unit', 'app_user', 'role', 'role_permission',
            'user_role', 'refresh_token', 'audit_log'
            ]
            LOOP
                EXECUTE format(
                        'CREATE POLICY %I ON %I USING (tenant_id = app_current_tenant()) '
                            || 'WITH CHECK (tenant_id = app_current_tenant())',
                        t || '_isolation', t);
            END LOOP;
    END
$$;

-- ---------------------------------------------------------------------------
-- Permission vocabulary
-- ---------------------------------------------------------------------------

INSERT INTO permission (id, code, resource, action)
VALUES (gen_random_uuid(), 'PARTNER_CREATE', 'BUSINESS_PARTNER', 'CREATE'),
       (gen_random_uuid(), 'PARTNER_READ', 'BUSINESS_PARTNER', 'READ'),
       (gen_random_uuid(), 'PARTNER_UPDATE', 'BUSINESS_PARTNER', 'UPDATE'),
       (gen_random_uuid(), 'VEHICLE_CREATE', 'VEHICLE', 'CREATE'),
       (gen_random_uuid(), 'VEHICLE_READ', 'VEHICLE', 'READ'),
       (gen_random_uuid(), 'VEHICLE_UPDATE', 'VEHICLE', 'UPDATE'),
       (gen_random_uuid(), 'DRIVER_CREATE', 'DRIVER', 'CREATE'),
       (gen_random_uuid(), 'DRIVER_READ', 'DRIVER', 'READ'),
       (gen_random_uuid(), 'TERMINAL_CREATE', 'TERMINAL', 'CREATE'),
       (gen_random_uuid(), 'TERMINAL_READ', 'TERMINAL', 'READ'),
       (gen_random_uuid(), 'ORDER_CREATE', 'ORDER', 'CREATE'),
       (gen_random_uuid(), 'ORDER_READ', 'ORDER', 'READ'),
       (gen_random_uuid(), 'ORDER_UPDATE', 'ORDER', 'UPDATE'),
       (gen_random_uuid(), 'LOAD_CREATE', 'LOAD', 'CREATE'),
       (gen_random_uuid(), 'LOAD_READ', 'LOAD', 'READ'),
       (gen_random_uuid(), 'LOAD_ALLOCATE', 'LOAD', 'EXECUTE'),
       (gen_random_uuid(), 'TRIP_READ', 'TRIP', 'READ'),
       (gen_random_uuid(), 'TRIP_EXECUTE', 'TRIP', 'EXECUTE'),
       (gen_random_uuid(), 'TELEMATICS_INGEST', 'TELEMATICS', 'CREATE'),
       (gen_random_uuid(), 'INVOICE_READ', 'INVOICE', 'READ'),
       (gen_random_uuid(), 'INVOICE_APPROVE', 'INVOICE', 'EXECUTE'),
       (gen_random_uuid(), 'AUDIT_READ', 'AUDIT_LOG', 'READ');
