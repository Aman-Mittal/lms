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

-- Whether this transaction is resetting the given demo tenant.
--
-- Several tables are append-only on purpose -- the audit log, tariffs and rate
-- cards, client contracts, invoices, credit notes and payments, the stock
-- ledger -- and refuse deletes through rules and triggers. A reset has to get
-- past them for the demo tenant's rows and for nothing else, so each refusal
-- below now asks this function first. It is true only when both hold:
--
--   * the transaction-local setting lms.demo_reset names exactly this tenant.
--     demo_reset_wipe sets it with set_config(..., true), so it vanishes at
--     commit or rollback and no other session or transaction ever sees it;
--   * the tenant is marked is_demo.
--
-- Nothing global is switched off: no ALTER TABLE, no DISABLE TRIGGER or RULE,
-- row-level security untouched, foreign keys checked throughout. A concurrent
-- request in another tenant runs exactly as before, and a request that set
-- the setting itself could still only reach the demo tenant's rows.
CREATE OR REPLACE FUNCTION demo_reset_active(p_tenant UUID) RETURNS BOOLEAN
    LANGUAGE sql
    STABLE
    SECURITY DEFINER
    SET search_path = public
AS
$$
SELECT p_tenant IS NOT NULL
   AND current_setting('lms.demo_reset', true) = p_tenant::text
   AND EXISTS (SELECT 1 FROM tenant WHERE id = p_tenant AND is_demo)
$$;

-- The append-only rules refuse a delete unless it is a demo reset's. A
-- conditional INSTEAD rule still runs the original statement for the rows
-- outside its condition, so a cascading foreign-key delete stays one plain
-- DELETE and the referential-integrity machinery accepts it.
CREATE OR REPLACE RULE tariff_no_delete AS ON DELETE TO tariff
    WHERE NOT demo_reset_active(OLD.tenant_id) DO INSTEAD NOTHING;
CREATE OR REPLACE RULE tariff_slab_no_delete AS ON DELETE TO tariff_slab
    WHERE NOT demo_reset_active(OLD.tenant_id) DO INSTEAD NOTHING;
CREATE OR REPLACE RULE ltl_rate_card_no_delete AS ON DELETE TO ltl_rate_card
    WHERE NOT demo_reset_active(OLD.tenant_id) DO INSTEAD NOTHING;
CREATE OR REPLACE RULE rail_tariff_no_delete AS ON DELETE TO rail_tariff
    WHERE NOT demo_reset_active(OLD.tenant_id) DO INSTEAD NOTHING;
CREATE OR REPLACE RULE client_contract_no_delete AS ON DELETE TO client_contract
    WHERE NOT demo_reset_active(OLD.tenant_id) DO INSTEAD NOTHING;
CREATE OR REPLACE RULE client_contract_service_rate_no_delete AS ON DELETE TO client_contract_service_rate
    WHERE NOT demo_reset_active(OLD.tenant_id) DO INSTEAD NOTHING;
CREATE OR REPLACE RULE client_invoice_no_delete AS ON DELETE TO client_invoice
    WHERE NOT demo_reset_active(OLD.tenant_id) DO INSTEAD NOTHING;
CREATE OR REPLACE RULE client_invoice_line_no_delete AS ON DELETE TO client_invoice_line
    WHERE NOT demo_reset_active(OLD.tenant_id) DO INSTEAD NOTHING;
CREATE OR REPLACE RULE client_credit_note_no_delete AS ON DELETE TO client_credit_note
    WHERE NOT demo_reset_active(OLD.tenant_id) DO INSTEAD NOTHING;
CREATE OR REPLACE RULE client_credit_note_line_no_delete AS ON DELETE TO client_credit_note_line
    WHERE NOT demo_reset_active(OLD.tenant_id) DO INSTEAD NOTHING;
CREATE OR REPLACE RULE client_payment_no_delete AS ON DELETE TO client_payment
    WHERE NOT demo_reset_active(OLD.tenant_id) DO INSTEAD NOTHING;
CREATE OR REPLACE RULE audit_log_no_delete AS ON DELETE TO audit_log
    WHERE NOT demo_reset_active(OLD.tenant_id) DO INSTEAD NOTHING;

-- The two ledger triggers refused deletes along with other writes; the delete
-- case moves to its own trigger, which steps aside for a demo reset. Inserts
-- and updates are refused exactly as before.
DROP TRIGGER stock_movement_immutable ON stock_movement;
CREATE TRIGGER stock_movement_immutable
    BEFORE UPDATE
    ON stock_movement
    FOR EACH ROW
EXECUTE FUNCTION stock_movement_refuse_change();
CREATE TRIGGER stock_movement_immutable_delete
    BEFORE DELETE
    ON stock_movement
    FOR EACH ROW
    WHEN (NOT demo_reset_active(OLD.tenant_id))
EXECUTE FUNCTION stock_movement_refuse_change();

DROP TRIGGER stock_balance_derived_only ON stock_balance;
CREATE TRIGGER stock_balance_derived_only
    BEFORE INSERT OR UPDATE
    ON stock_balance
    FOR EACH ROW
EXECUTE FUNCTION stock_balance_refuse_direct_write();
CREATE TRIGGER stock_balance_derived_only_delete
    BEFORE DELETE
    ON stock_balance
    FOR EACH ROW
    WHEN (NOT demo_reset_active(OLD.tenant_id))
EXECUTE FUNCTION stock_balance_refuse_direct_write();

-- Clears a demo tenant's rows from the tables named, in the order named.
--
-- The application works out the order from the foreign keys, children before
-- parents, and passes every table at once. SECURITY DEFINER, with a fixed
-- search_path, only so that the setting above is raised and lowered in one
-- place. It deletes only rows whose tenant_id is the tenant named, only from
-- tables with a tenant_id, and only for a tenant marked is_demo; row-level
-- security applies to it as to anything else.
CREATE OR REPLACE FUNCTION demo_reset_wipe(p_tenant UUID, p_tables TEXT[]) RETURNS BIGINT
    LANGUAGE plpgsql
    SECURITY DEFINER
    SET search_path = public
AS
$$
DECLARE
    total   BIGINT := 0;
    deleted BIGINT;
    t       TEXT;
BEGIN
    IF NOT EXISTS (SELECT 1 FROM tenant WHERE id = p_tenant AND is_demo) THEN
        RAISE EXCEPTION 'tenant % is not the demo tenant', p_tenant;
    END IF;
    FOREACH t IN ARRAY p_tables
        LOOP
            IF NOT EXISTS (SELECT 1
                             FROM information_schema.columns c
                             JOIN information_schema.tables x
                               ON x.table_schema = c.table_schema AND x.table_name = c.table_name
                            WHERE c.table_schema = 'public'
                              AND c.table_name = t
                              AND c.column_name = 'tenant_id'
                              AND x.table_type = 'BASE TABLE') THEN
                RAISE EXCEPTION 'table % has no tenant_id', t;
            END IF;
        END LOOP;

    -- Transaction-local (the third argument): gone at commit or rollback.
    PERFORM set_config('lms.demo_reset', p_tenant::text, true);
    FOREACH t IN ARRAY p_tables
        LOOP
            EXECUTE format('DELETE FROM %I WHERE tenant_id = $1', t) USING p_tenant;
            GET DIAGNOSTICS deleted = ROW_COUNT;
            total := total + deleted;
        END LOOP;
    PERFORM set_config('lms.demo_reset', '', true);

    RETURN total;
END
$$;

REVOKE ALL ON FUNCTION demo_reset_wipe(UUID, TEXT[]) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION demo_reset_wipe(UUID, TEXT[]) TO lms_app;

INSERT INTO permission (id, code, resource, action)
VALUES (gen_random_uuid(), 'DEMO_RESET', 'DEMO', 'EXECUTE');
