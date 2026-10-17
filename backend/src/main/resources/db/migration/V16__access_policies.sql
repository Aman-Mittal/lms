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

-- Attribute-based access control, layered on the permission codes of V2
-- (vision document 3.1; general practice after NIST SP 800-162).
--
-- A permission says *what kind* of thing a user may do. It cannot say "up to
-- 50 000", "only in my branch" or "only my own company's bills". Those rules
-- are decided here from attributes of the user, of the record and of the
-- action, by policies that are rows a tenant administers rather than code a
-- release ships.

-- ---------------------------------------------------------------------------
-- Subject attributes
-- ---------------------------------------------------------------------------

-- On the user, carried into the access token at login and re-read on every
-- refresh, so a changed limit takes effect within one token lifetime without
-- anybody signing out.
--
-- Every column is nullable, and NULL means "not restricted along this
-- dimension". That is what keeps existing behaviour unchanged: every user
-- that exists today has no limit, no partner and no mode list, and the
-- default policies below only ever bite on a value that is present.
ALTER TABLE app_user
    ADD COLUMN region TEXT;

ALTER TABLE app_user
    ADD COLUMN freight_modes TEXT[];

-- Set for an external portal user -- a vendor's or a 3PL client's staff. Such
-- a user acts for that partner and sees only that partner's records.
ALTER TABLE app_user
    ADD COLUMN partner_id UUID REFERENCES business_partner (id) ON DELETE SET NULL;

-- The largest amount the user may approve. NULL is "no limit", which is not
-- the same as zero: zero means they may approve nothing that costs money.
ALTER TABLE app_user
    ADD COLUMN approval_limit NUMERIC(14, 2);

ALTER TABLE app_user
    ADD CONSTRAINT app_user_approval_limit_positive CHECK (approval_limit IS NULL OR approval_limit >= 0);

ALTER TABLE app_user
    ADD CONSTRAINT app_user_freight_modes_valid CHECK (
        freight_modes IS NULL OR freight_modes <@ ARRAY ['FTL', 'LTL', 'RAIL', 'WAREHOUSE']::TEXT[]);

ALTER TABLE app_user
    ADD CONSTRAINT app_user_region_safe CHECK (region IS NULL OR region ~ '^[A-Z0-9_-]{1,32}$');

-- ---------------------------------------------------------------------------
-- Policies as data
-- ---------------------------------------------------------------------------

CREATE TABLE access_policy
(
    id          UUID PRIMARY KEY,
    tenant_id   UUID        NOT NULL REFERENCES tenant (id) ON DELETE CASCADE,
    -- Stable, human-chosen name. What an administrator types and an audit
    -- entry quotes; the uuid is for URLs.
    code        TEXT        NOT NULL,
    -- The permission code the policy refines, e.g. INVOICE_APPROVE. A policy
    -- is consulted only after the permission itself has been granted, so it
    -- can narrow what RBAC allows and never widen it.
    action      TEXT        NOT NULL,
    -- DENY policies veto when their condition holds. PERMIT policies, when an
    -- action has any, turn it into an allow-list: at least one must hold.
    effect      TEXT        NOT NULL,
    description TEXT        NOT NULL,
    -- The condition, as a small JSON expression over subject.*, resource.*
    -- and env.* attributes. Validated by the application on every write; the
    -- CHECK only stops something that is not even an object.
    condition   JSONB       NOT NULL,
    enabled     BOOLEAN     NOT NULL DEFAULT TRUE,
    version     BIGINT,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT access_policy_code_unique UNIQUE (tenant_id, code),
    CONSTRAINT access_policy_code_safe CHECK (code ~ '^[a-z0-9][a-z0-9-]{0,62}$'),
    CONSTRAINT access_policy_action_safe CHECK (action ~ '^[A-Z][A-Z0-9_]{0,62}$'),
    CONSTRAINT access_policy_effect_valid CHECK (effect IN ('PERMIT', 'DENY')),
    CONSTRAINT access_policy_condition_object CHECK (jsonb_typeof(condition) = 'object')
);

-- The decision point asks one question, once per action per request: which
-- enabled policies refine this action in this tenant? (tenant_id, action)
-- answers it from the index; code rides along for a stable order.
CREATE INDEX access_policy_action_idx ON access_policy (tenant_id, action, code);

ALTER TABLE access_policy ENABLE ROW LEVEL SECURITY;
ALTER TABLE access_policy FORCE ROW LEVEL SECURITY;
CREATE POLICY access_policy_isolation ON access_policy
    USING (tenant_id = app_current_tenant())
    WITH CHECK (tenant_id = app_current_tenant());

-- ---------------------------------------------------------------------------
-- Default policies
-- ---------------------------------------------------------------------------

-- What every tenant starts with. Each one only restricts a user who carries
-- the attribute it reads, so for every user that exists today the outcome is
-- exactly what RBAC alone decided. A tenant may edit, disable or delete them.
CREATE OR REPLACE FUNCTION seed_default_access_policies(p_tenant UUID) RETURNS void
    LANGUAGE sql
    AS $$
    INSERT INTO access_policy (id, tenant_id, code, action, effect, description, condition, version)
    VALUES
        (gen_random_uuid(), p_tenant, 'invoice-approval-limit', 'INVOICE_APPROVE', 'DENY',
         'An invoice may be approved, or a dispute settled, only up to the approver''s limit.',
         '{"attr": "resource.amount", "op": "gt", "ref": "subject.approvalLimit"}'::jsonb, 0),
        (gen_random_uuid(), p_tenant, 'exception-close-holding-limit', 'EXCEPTION_CLOSE', 'DENY',
         'Closing a ticket that is holding settlement releases money, so it needs an approval limit of at least 100000.',
         '{"allOf": [{"attr": "resource.suspendsSettlement", "op": "eq", "value": true},
                     {"attr": "subject.approvalLimit", "op": "lt", "value": 100000}]}'::jsonb, 0),
        (gen_random_uuid(), p_tenant, 'partner-write-org-subtree', 'PARTNER_CREATE', 'DENY',
         'A partner may be registered only under the caller''s own organisational unit or one beneath it.',
         '{"allOf": [{"attr": "resource.orgPath", "op": "present"},
                     {"attr": "subject.orgPath", "op": "present"},
                     {"not": {"attr": "resource.orgPath", "op": "startsWith", "ref": "subject.orgPath"}}]}'::jsonb, 0)
    ON CONFLICT (tenant_id, code) DO NOTHING
$$;

-- New tenants get the defaults in the same transaction that creates them.
-- Inserting a tenant already requires the scope to be that tenant (the
-- tenant table's own policy), so the rows below pass row-level security
-- under exactly the conditions the tenant row did.
CREATE OR REPLACE FUNCTION seed_default_access_policies_for_new_tenant() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    PERFORM seed_default_access_policies(NEW.id);
    RETURN NEW;
END
$$;

CREATE TRIGGER tenant_default_access_policies
    AFTER INSERT ON tenant
    FOR EACH ROW
EXECUTE FUNCTION seed_default_access_policies_for_new_tenant();

-- Tenants that already exist. The table is FORCEd, so even the migration's
-- own role is filtered: each tenant's rows are written under that tenant's
-- scope, and the scope is cleared afterwards.
DO
$$
    DECLARE
        t UUID;
    BEGIN
        FOR t IN SELECT id FROM tenant
            LOOP
                PERFORM set_config('app.tenant_id', t::text, true);
                PERFORM seed_default_access_policies(t);
            END LOOP;
        PERFORM set_config('app.tenant_id', '', true);
    END
$$;

-- ---------------------------------------------------------------------------
-- Permission vocabulary
-- ---------------------------------------------------------------------------

-- Administering policies, and the attributes they read, is one authority.
-- Whoever can set a user's approval limit can already decide what that user
-- may approve; splitting the two would protect nothing.
INSERT INTO permission (id, code, resource, action)
VALUES (gen_random_uuid(), 'ACCESS_POLICY_MANAGE', 'ACCESS_POLICY', 'UPDATE');
