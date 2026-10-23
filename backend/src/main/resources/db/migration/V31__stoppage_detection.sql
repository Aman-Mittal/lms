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

-- Stoppage detection (vision document 3.8): a trip in transit whose vehicle has
-- not moved for longer than a threshold, outside every terminal, is an
-- exception. This table is the detector's memory: one row per trip holding the
-- fix the vehicle has been sitting at and since when, so that "has it moved"
-- is a comparison against one row instead of a scan back through the pings.
CREATE TABLE stoppage_watch
(
    id               UUID PRIMARY KEY,
    tenant_id        UUID           NOT NULL REFERENCES tenant (id) ON DELETE CASCADE,
    trip_id          UUID           NOT NULL REFERENCES trip (id) ON DELETE CASCADE,
    lat              NUMERIC(9, 6)  NOT NULL,
    lon              NUMERIC(9, 6)  NOT NULL,
    stationary_since TIMESTAMPTZ    NOT NULL,
    -- Set once, when the stoppage has been reported, so a vehicle that stays
    -- put for a day is one event and not one per ping.
    raised_at        TIMESTAMPTZ,
    version          BIGINT,
    created_at       TIMESTAMPTZ    NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX stoppage_watch_trip_idx ON stoppage_watch (tenant_id, trip_id);

ALTER TABLE stoppage_watch ENABLE ROW LEVEL SECURITY;
ALTER TABLE stoppage_watch FORCE ROW LEVEL SECURITY;
CREATE POLICY stoppage_watch_isolation ON stoppage_watch
    USING (tenant_id = app_current_tenant())
    WITH CHECK (tenant_id = app_current_tenant());

-- The new ticket source. The category is the existing EXTENDED_STOPPAGE.
ALTER TABLE exception_ticket
    DROP CONSTRAINT exception_ticket_source_valid;

ALTER TABLE exception_ticket
    ADD CONSTRAINT exception_ticket_source_valid CHECK (source IN (
        'MANUAL', 'ROUTE_DEVIATION',
        'MISSED_CONNECTION', 'MILK_RUN_STOP_SKIPPED', 'MILK_RUN_STOP_LATE',
        'RECEIPT_VARIANCE', 'PICK_SHORTFALL', 'STOPPAGE_DETECTED'));
