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

-- Issue #258: administering users and roles from the consoles.
--
-- * USER_MANAGE: create, edit, deactivate, reactivate and set the password of
--   users. ROLE_MANAGE: create custom roles, edit what a role grants, delete
--   an unused one. ROLE_READ: see the roles and the permission vocabulary.
-- * The standard roles every tenant starts with (Administrator, Planner,
--   Dispatcher, Finance, Viewer), seeded by seed_standard_roles(tenant) when the
--   application creates a tenant, and backfilled for the tenants that exist today. The Administrator code is ADMIN, which is
--   what the demo seeder has always used.

INSERT INTO permission (id, code, resource, action)
VALUES (gen_random_uuid(), 'USER_MANAGE', 'USER', 'UPDATE'),
       (gen_random_uuid(), 'ROLE_MANAGE', 'ROLE', 'UPDATE'),
       (gen_random_uuid(), 'ROLE_READ', 'ROLE', 'READ');

-- One role of the standard set: created if the tenant has no role with that
-- code, and granted its permissions only when it was created here, so a role
-- a tenant has since edited is never put back.
CREATE OR REPLACE FUNCTION seed_standard_role(p_tenant UUID, p_code TEXT, p_name TEXT, p_codes TEXT[],
                                              p_pattern TEXT) RETURNS void
    LANGUAGE plpgsql
    AS $$
DECLARE
    v_role UUID;
BEGIN
    INSERT INTO role (id, tenant_id, code, name)
    VALUES (gen_random_uuid(), p_tenant, p_code, p_name)
    ON CONFLICT (tenant_id, code) DO NOTHING
    RETURNING id INTO v_role;
    IF v_role IS NULL THEN
        RETURN;
    END IF;
    INSERT INTO role_permission (tenant_id, role_id, permission_id)
    SELECT p_tenant, v_role, p.id
      FROM permission p
     WHERE p.code = ANY (p_codes)
        OR (p_pattern IS NOT NULL AND p.code LIKE p_pattern AND p.code NOT IN ('ROLE_READ'))
    ON CONFLICT DO NOTHING;
END
$$;

CREATE OR REPLACE FUNCTION seed_standard_roles(p_tenant UUID) RETURNS void
    LANGUAGE plpgsql
    AS $$
BEGIN
    -- Administrator: everything in the vocabulary today. The demo seeder and
    -- the grant below keep it complete as later migrations add permissions.
    PERFORM seed_standard_role(p_tenant, 'ADMIN', 'Administrator', ARRAY(SELECT code FROM permission), NULL);
    PERFORM seed_standard_role(p_tenant, 'PLANNER', 'Planner', ARRAY [
        'ORDER_CREATE', 'ORDER_READ', 'ORDER_UPDATE',
        'LOAD_CREATE', 'LOAD_READ', 'LOAD_ALLOCATE',
        'TRIP_READ', 'TRIP_EXECUTE',
        'MILKRUN_PLAN', 'MILKRUN_READ', 'MULTIMODAL_PLAN', 'MULTIMODAL_READ',
        'PARTNER_READ', 'VEHICLE_READ', 'DRIVER_READ', 'TERMINAL_READ', 'ALLOCATION_RULE_READ'
        ], NULL);
    PERFORM seed_standard_role(p_tenant, 'DISPATCHER', 'Dispatcher', ARRAY [
        'TRIP_READ', 'TRIP_EXECUTE', 'MILKRUN_READ', 'MILKRUN_EXECUTE',
        'GATE_PASS_MANAGE', 'GATE_REGISTER', 'EWAY_BILL_MANAGE',
        'POD_READ', 'POD_RECORD', 'EXCEPTION_RAISE', 'EXCEPTION_READ',
        'ORDER_READ', 'LOAD_READ', 'PARTNER_READ', 'VEHICLE_READ', 'DRIVER_READ', 'TERMINAL_READ'
        ], NULL);
    PERFORM seed_standard_role(p_tenant, 'FINANCE', 'Finance', ARRAY [
        'INVOICE_READ', 'INVOICE_APPROVE',
        'CLIENT_BILLING_READ', 'CLIENT_BILLING_RUN', 'CLIENT_CREDIT_NOTE_ISSUE', 'CLIENT_MARGIN_READ',
        'PAYABLES_MANAGE', 'ADVANCE_REQUEST', 'ADVANCE_APPROVE',
        'MANUAL_CHARGE_RECORD', 'MANUAL_CHARGE_APPROVE', 'SETTLEMENT_HOLD_RELEASE',
        'CLAIM_READ', 'CLAIM_DECIDE', 'APPROVAL_READ',
        'ORDER_READ', 'PARTNER_READ'
        ], NULL);
    PERFORM seed_standard_role(p_tenant, 'VIEWER', 'Viewer', ARRAY []::TEXT[], '%\_READ');
END
$$;

-- There is no tenant-insert trigger here, unlike the default access policies:
-- test fixtures create tenants and then insert their own ADMIN role, and a
-- trigger would turn each of those into a unique violation. The one place the
-- application creates a tenant (DemoTenantSeeder) calls the function itself.

-- Tenants that already exist: each under its own scope, because the tables
-- are FORCEd. Their Administrator also gains the three new permissions.
DO
$$
    DECLARE
        t UUID;
    BEGIN
        FOR t IN SELECT id FROM tenant
            LOOP
                PERFORM set_config('app.tenant_id', t::text, true);
                PERFORM seed_standard_roles(t);
                INSERT INTO role_permission (tenant_id, role_id, permission_id)
                SELECT t, r.id, p.id
                  FROM role r
                  JOIN permission p ON p.code IN ('USER_MANAGE', 'ROLE_MANAGE', 'ROLE_READ')
                 WHERE r.tenant_id = t AND r.code = 'ADMIN'
                ON CONFLICT DO NOTHING;
            END LOOP;
        PERFORM set_config('app.tenant_id', '', true);
    END
$$;

-- The list reads a tenant's users by name and filters them by status.
CREATE INDEX app_user_status_name_idx ON app_user (tenant_id, status, full_name);
