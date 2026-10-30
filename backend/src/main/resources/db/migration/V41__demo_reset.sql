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

-- Putting the demo tenant back to its seeded state.

-- The tenant the demo seeder created, marked by the seeder itself. The reset
-- below refuses any tenant without this mark, so the guard lives in the
-- database and not only in the application: no configuration mistake can
-- point a reset at a real company's data.
ALTER TABLE tenant ADD COLUMN is_demo BOOLEAN NOT NULL DEFAULT false;

-- Clears one table of one demo tenant's rows.
--
-- Called once per table, children before parents; the application works out
-- the order from the foreign keys. A function rather than a plain DELETE
-- because several tables are append-only on purpose -- the audit log, tariffs,
-- client contracts and the stock ledger refuse deletes through rules and
-- triggers -- and only the tables' owner may switch those off. They are
-- switched off for the one statement and on again before the function returns;
-- the ALTERs are transactional, so a failure anywhere rolls them back with the
-- rest. Foreign-key checks are system triggers and stay on throughout.
--
-- SECURITY DEFINER, so it runs as the owner, with a fixed search_path. It
-- deletes only rows whose tenant_id is the tenant named, and only for a tenant
-- marked is_demo.
CREATE OR REPLACE FUNCTION demo_reset_wipe_table(p_tenant UUID, p_table TEXT) RETURNS BIGINT
    LANGUAGE plpgsql
    SECURITY DEFINER
    SET search_path = public
AS
$$
DECLARE
    deleted BIGINT;
    r       RECORD;
BEGIN
    IF NOT EXISTS (SELECT 1 FROM tenant WHERE id = p_tenant AND is_demo) THEN
        RAISE EXCEPTION 'tenant % is not the demo tenant', p_tenant USING ERRCODE = '42501';
    END IF;
    IF NOT EXISTS (SELECT 1
                     FROM information_schema.columns c
                     JOIN information_schema.tables t
                       ON t.table_schema = c.table_schema AND t.table_name = c.table_name
                    WHERE c.table_schema = 'public'
                      AND c.table_name = p_table
                      AND c.column_name = 'tenant_id'
                      AND t.table_type = 'BASE TABLE') THEN
        RAISE EXCEPTION 'table % has no tenant_id', p_table USING ERRCODE = '42P01';
    END IF;

    EXECUTE format('ALTER TABLE %I DISABLE TRIGGER USER', p_table);
    FOR r IN SELECT rulename FROM pg_rules WHERE schemaname = 'public' AND tablename = p_table
        LOOP
            EXECUTE format('ALTER TABLE %I DISABLE RULE %I', p_table, r.rulename);
        END LOOP;

    EXECUTE format('DELETE FROM %I WHERE tenant_id = $1', p_table) USING p_tenant;
    GET DIAGNOSTICS deleted = ROW_COUNT;

    EXECUTE format('ALTER TABLE %I ENABLE TRIGGER USER', p_table);
    FOR r IN SELECT rulename FROM pg_rules WHERE schemaname = 'public' AND tablename = p_table
        LOOP
            EXECUTE format('ALTER TABLE %I ENABLE RULE %I', p_table, r.rulename);
        END LOOP;

    RETURN deleted;
END
$$;

REVOKE ALL ON FUNCTION demo_reset_wipe_table(UUID, TEXT) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION demo_reset_wipe_table(UUID, TEXT) TO lms_app;

INSERT INTO permission (id, code, resource, action)
VALUES (gen_random_uuid(), 'DEMO_RESET', 'DEMO', 'EXECUTE');
