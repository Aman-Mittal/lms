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

-- Brings the identity aggregates in line with every other aggregate root in
-- the platform.
--
-- V2 created these four tables without a version column, and nothing has
-- broken, because identity only ever *updates* through its repositories --
-- rows are seeded by migration or by the test fixtures using raw SQL. That is
-- correct by accident. The first time anyone writes
-- `tenants.save(Tenant.create(...))` with a client-assigned UUID, Spring Data
-- JDBC will see an entity that looks like an existing row, issue an UPDATE
-- that matches nothing, and report success. Nothing persists and nothing
-- complains. That failure has already been hit three times on other
-- aggregates in this codebase.
--
-- With the column present, `.github/scripts/check-conventions.sh` can enforce
-- the rule uniformly instead of carrying a list of exemptions that quietly
-- grows.

-- NOT NULL DEFAULT 0, not a nullable column. A null version reads as "new" to
-- Spring Data, so backfilling existing rows with NULL would turn the next
-- update of every existing user into an attempted insert -- the exact bug this
-- migration exists to prevent, introduced by the fix for it.
ALTER TABLE tenant
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0;

ALTER TABLE org_unit
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0;

ALTER TABLE app_user
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0;

ALTER TABLE role
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
