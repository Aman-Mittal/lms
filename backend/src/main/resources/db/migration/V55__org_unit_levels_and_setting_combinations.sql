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

-- Organisational levels, and setting profiles for a combination of scopes.
--
-- 1. Org units gain the COUNTRY and ZONE types (REGION stays and ranks as a
--    zone). The level order is HQ 0, LOB 1, COUNTRY 2, ZONE/REGION 3,
--    BRANCH 4, HUB 5, and a child must rank strictly below its parent. That
--    rule is enforced by the application on every new write (create, retype)
--    and deliberately NOT by a constraint here: trees written before this
--    migration were never held to it, and a migration must not fail on them.
--    A COUNTRY unit carries its ISO 3166-1 alpha-2 country_code (required);
--    a ZONE, REGION, BRANCH or HUB may carry a state_code. Both shapes are
--    constraints, and every existing row (no codes yet) satisfies them.
--    A unit's jurisdiction is the nearest country at or above it, and the
--    nearest state at or above it but beneath that country; the settings
--    resolution reads it through org_unit_path_unique (tenant_id, path).
ALTER TABLE org_unit DROP CONSTRAINT org_unit_type_valid;
ALTER TABLE org_unit ADD CONSTRAINT org_unit_type_valid
    CHECK (unit_type IN ('HQ', 'LOB', 'COUNTRY', 'ZONE', 'REGION', 'BRANCH', 'HUB'));

ALTER TABLE org_unit
    ADD COLUMN country_code TEXT,
    ADD COLUMN state_code   TEXT;

ALTER TABLE org_unit ADD CONSTRAINT org_unit_country_shape
    CHECK ((unit_type = 'COUNTRY') = (country_code IS NOT NULL)
        AND (country_code IS NULL OR country_code ~ '^[A-Z]{2}$'));
ALTER TABLE org_unit ADD CONSTRAINT org_unit_state_shape
    CHECK (state_code IS NULL
        OR (unit_type IN ('ZONE', 'REGION', 'BRANCH', 'HUB') AND state_code ~ '^[A-Z0-9]{1,3}$'));

-- Creating, renaming and retyping units, and setting their codes. Granted to
-- no tenant role here; the demo administrator receives it with every other
-- permission from the seeder.
INSERT INTO permission (id, code, resource, action)
VALUES (gen_random_uuid(), 'ORG_UNIT_MANAGE', 'ORG_UNIT', 'UPDATE');

-- 2. COMBINATION setting profiles: values that apply when every one of the
--    profile's conditions holds for the context -- an org unit's subtree, a
--    customer, a vendor, a vehicle, a freight mode; two of them at least. In
--    the fold COMBINATION sits above VEHICLE, CUSTOMER and VENDOR and below
--    USER:
--    PLATFORM < ORG_DEFAULT < ORG_UNIT < VEHICLE < CUSTOMER < VENDOR
--      < COMBINATION < USER.
--    Among the combinations that match, more conditions win, then the deeper
--    org unit, then the higher priority. A tenant keeps many combinations,
--    so scope_id is the profile's own id: the one-profile-per-scope index
--    stays as it is and still holds for every other scope type.
ALTER TABLE setting_profile DROP CONSTRAINT setting_profile_scope_type_valid;
ALTER TABLE setting_profile ADD CONSTRAINT setting_profile_scope_type_valid
    CHECK (scope_type IN ('PLATFORM', 'ORG_DEFAULT', 'ORG_UNIT', 'VEHICLE', 'CUSTOMER', 'VENDOR', 'COMBINATION',
                          'USER'));

-- Polymorphic like scope_id, so no foreign keys: each id is looked up under
-- row-level security before it is accepted.
ALTER TABLE setting_profile
    ADD COLUMN match_org_unit_id  UUID,
    ADD COLUMN match_customer_id  UUID,
    ADD COLUMN match_vendor_id    UUID,
    ADD COLUMN match_vehicle_id   UUID,
    ADD COLUMN match_freight_mode TEXT,
    ADD COLUMN priority           INTEGER NOT NULL DEFAULT 0;

ALTER TABLE setting_profile ADD CONSTRAINT setting_profile_combination_shape CHECK (
    CASE
        WHEN scope_type = 'COMBINATION' THEN
            scope_id = id
                AND (CASE WHEN match_org_unit_id IS NULL THEN 0 ELSE 1 END
                    + CASE WHEN match_customer_id IS NULL THEN 0 ELSE 1 END
                    + CASE WHEN match_vendor_id IS NULL THEN 0 ELSE 1 END
                    + CASE WHEN match_vehicle_id IS NULL THEN 0 ELSE 1 END
                    + CASE WHEN match_freight_mode IS NULL THEN 0 ELSE 1 END) >= 2
                AND priority BETWEEN -1000 AND 1000
        ELSE match_org_unit_id IS NULL AND match_customer_id IS NULL AND match_vendor_id IS NULL
            AND match_vehicle_id IS NULL AND match_freight_mode IS NULL AND priority = 0
    END);

-- The vocabulary of order::api FreightMode.
ALTER TABLE setting_profile ADD CONSTRAINT setting_profile_freight_mode_valid
    CHECK (match_freight_mode IS NULL OR match_freight_mode IN ('FTL', 'LTL', 'RAIL', 'MILK_RUN'));
