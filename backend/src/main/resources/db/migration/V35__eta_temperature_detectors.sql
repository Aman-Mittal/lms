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

-- Control tower ETA-slip and temperature-excursion detectors.

-- A reefer's probe reading rides on the ping. Nullable: most vehicles have no
-- probe, and a ping without one is still a position.
ALTER TABLE gps_ping
    ADD COLUMN temperature_c NUMERIC(5, 2),
    ADD CONSTRAINT gps_ping_temperature_range CHECK (
        temperature_c IS NULL OR temperature_c BETWEEN -60 AND 100);

-- What a trip was promised: the arrival it committed to and the temperature
-- band its cargo must stay in. Nothing upstream carries either today, so the
-- control tower desk records them per trip, and a trip with no row is simply
-- not watched.
CREATE TABLE trip_watch
(
    id                   UUID PRIMARY KEY,
    tenant_id            UUID          NOT NULL REFERENCES tenant (id) ON DELETE CASCADE,
    trip_id              UUID          NOT NULL REFERENCES trip (id) ON DELETE CASCADE,
    committed_arrival_at TIMESTAMPTZ,
    temp_min_c           NUMERIC(5, 2),
    temp_max_c           NUMERIC(5, 2),
    -- Since when the readings have been outside the band, kept from reading to
    -- reading so that "for longer than N minutes" is a comparison and not a
    -- scan of the track. Cleared by the first reading back inside.
    temp_out_since       TIMESTAMPTZ,
    -- The newest reading judged. A reading that is not newer is a late or
    -- redelivered one and says nothing about now.
    last_temp_at         TIMESTAMPTZ,
    version              BIGINT,
    created_at           TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT trip_watch_has_a_term CHECK (
        committed_arrival_at IS NOT NULL OR temp_min_c IS NOT NULL),
    CONSTRAINT trip_watch_band_whole CHECK ((temp_min_c IS NULL) = (temp_max_c IS NULL)),
    CONSTRAINT trip_watch_band_ordered CHECK (temp_min_c IS NULL OR temp_min_c <= temp_max_c)
);

CREATE UNIQUE INDEX trip_watch_trip_idx ON trip_watch (tenant_id, trip_id);

ALTER TABLE trip_watch ENABLE ROW LEVEL SECURITY;
ALTER TABLE trip_watch FORCE ROW LEVEL SECURITY;
CREATE POLICY trip_watch_isolation ON trip_watch
    USING (tenant_id = app_current_tenant())
    WITH CHECK (tenant_id = app_current_tenant());

-- A tenant's tolerances. At most one row; a tenant with none uses the
-- platform defaults, so nothing has to be seeded.
CREATE TABLE detector_setting
(
    id                        UUID PRIMARY KEY,
    tenant_id                 UUID        NOT NULL REFERENCES tenant (id) ON DELETE CASCADE,
    eta_slip_minutes          INTEGER     NOT NULL,
    temperature_excursion_minutes INTEGER NOT NULL,
    version                   BIGINT,
    created_at                TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT detector_setting_eta_range CHECK (eta_slip_minutes BETWEEN 1 AND 10080),
    CONSTRAINT detector_setting_temp_range CHECK (
        temperature_excursion_minutes BETWEEN 1 AND 10080)
);

CREATE UNIQUE INDEX detector_setting_tenant_idx ON detector_setting (tenant_id);

ALTER TABLE detector_setting ENABLE ROW LEVEL SECURITY;
ALTER TABLE detector_setting FORCE ROW LEVEL SECURITY;
CREATE POLICY detector_setting_isolation ON detector_setting
    USING (tenant_id = app_current_tenant())
    WITH CHECK (tenant_id = app_current_tenant());

-- Two more detectors. Their categories already exist.
ALTER TABLE exception_ticket
    DROP CONSTRAINT exception_ticket_source_valid,
    DROP CONSTRAINT exception_ticket_trip_sources_have_trip;

ALTER TABLE exception_ticket
    ADD CONSTRAINT exception_ticket_source_valid CHECK (source IN (
        'MANUAL', 'ROUTE_DEVIATION',
        'MISSED_CONNECTION', 'MILK_RUN_STOP_SKIPPED', 'MILK_RUN_STOP_LATE',
        'RECEIPT_VARIANCE', 'PICK_SHORTFALL', 'STOPPAGE_DETECTED',
        'ETA_SLIP_DETECTED', 'TEMPERATURE_EXCURSION_DETECTED')),
    ADD CONSTRAINT exception_ticket_trip_sources_have_trip CHECK (
        source NOT IN ('MANUAL', 'ROUTE_DEVIATION', 'ETA_SLIP_DETECTED',
                       'TEMPERATURE_EXCURSION_DETECTED') OR trip_id IS NOT NULL);

-- One ticket per trip and kind until it is closed, whichever delivery got
-- there first. The source key makes a redelivery a no-op; this is what holds
-- when two different readings race.
CREATE UNIQUE INDEX exception_ticket_open_detector_idx
    ON exception_ticket (tenant_id, trip_id, category)
    WHERE source IN ('ETA_SLIP_DETECTED', 'TEMPERATURE_EXCURSION_DETECTED')
        AND status <> 'CLOSED';
