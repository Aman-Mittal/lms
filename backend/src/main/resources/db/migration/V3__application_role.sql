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

-- A restricted role for application queries, so that row-level security is
-- actually enforced.
--
-- Why this exists, and it is not a nicety:
--
-- Postgres exempts SUPERUSERs from row-level security unconditionally, and
-- exempts table OWNERs unless the table is marked FORCE. V2 handles the owner
-- case with FORCE. The superuser case cannot be handled that way.
--
-- The superuser case is not hypothetical. The official postgres container
-- creates POSTGRES_USER as a superuser, so both docker-compose and
-- Testcontainers connect as one. Left alone, every policy in V2 is silently
-- inert in development and in the test suite -- which is precisely where a
-- tenant-isolation defect must be caught. It was: the acceptance suite saw one
-- tenant's users while scoped to another.
--
-- The fix is to run application statements as a role that is neither superuser
-- nor owner. TenantAwareTransactionManager issues `SET LOCAL ROLE lms_app` at
-- transaction start, which drops both exemptions for the duration of the
-- transaction. Flyway keeps its own connections and is unaffected, so
-- migrations still run with the privileges they need.

DO
$$
    BEGIN
        IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'lms_app') THEN
            -- NOLOGIN: reached only via SET ROLE, never by connecting.
            CREATE ROLE lms_app NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOBYPASSRLS;
        END IF;
    END
$$;

GRANT USAGE ON SCHEMA public TO lms_app;

GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO lms_app;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO lms_app;

-- Tables created by later migrations must be reachable too, without every
-- migration having to remember to grant.
ALTER DEFAULT PRIVILEGES IN SCHEMA public
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO lms_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA public
    GRANT USAGE, SELECT ON SEQUENCES TO lms_app;

-- The connecting role must be a member of lms_app to SET ROLE to it.
DO
$$
    BEGIN
        EXECUTE format('GRANT lms_app TO %I', current_user);
    EXCEPTION
        WHEN insufficient_privilege THEN
            RAISE NOTICE 'Could not grant lms_app to %; SET ROLE will be skipped', current_user;
    END
$$;
