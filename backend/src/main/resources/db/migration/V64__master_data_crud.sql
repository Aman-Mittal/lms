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

-- Issue #238: maintaining partners, vehicles, drivers and terminals.
--
-- Nothing in master data is hard-deleted. A vehicle, driver or terminal is
-- switched off with a reason and can be switched back on; the rows stay because
-- trips, loads and bills keep pointing at them.
--
-- * vehicle gains INACTIVE. RETIRED already exists but reads as permanent;
--   INACTIVE is the reversible "not in service" state the registry edits use.
-- * terminal gains a status of its own (it had none): ACTIVE or INACTIVE.
-- * DRIVER_UPDATE is the permission driver edits and deactivation need; the
--   vehicle and partner equivalents were seeded in V2.

ALTER TABLE vehicle
    DROP CONSTRAINT vehicle_status_valid,
    ADD CONSTRAINT vehicle_status_valid
        CHECK (status IN ('AVAILABLE', 'ON_TRIP', 'MAINTENANCE', 'RETIRED', 'INACTIVE'));

ALTER TABLE terminal
    ADD COLUMN status TEXT NOT NULL DEFAULT 'ACTIVE',
    ADD CONSTRAINT terminal_status_valid CHECK (status IN ('ACTIVE', 'INACTIVE'));

-- The default list of each register is "active", which is a status filter in
-- front of the code cursor.
CREATE INDEX terminal_status_idx ON terminal (tenant_id, status, code);

INSERT INTO permission (id, code, resource, action)
VALUES (gen_random_uuid(), 'DRIVER_UPDATE', 'DRIVER', 'UPDATE');
