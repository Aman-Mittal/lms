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

-- Telematics (vision document 3.7): the position stream that drives the trip
-- state machine.
--
-- This is the highest-volume table in the platform by two orders of magnitude,
-- and it lives on a 1 GB database. Every decision below is about that.

-- ---------------------------------------------------------------------------
-- 3.7.1 Position ingest
-- ---------------------------------------------------------------------------

CREATE TABLE gps_ping
(
    id          UUID PRIMARY KEY,
    tenant_id   UUID        NOT NULL REFERENCES tenant (id) ON DELETE CASCADE,
    vehicle_id  UUID        NOT NULL REFERENCES vehicle (id) ON DELETE CASCADE,
    -- Nullable: a vehicle reports position whether or not it is on a trip, and
    -- discarding those points would lose the approach to the yard, which is
    -- exactly the stretch a dispatcher wants to see.
    trip_id     UUID REFERENCES trip (id) ON DELETE SET NULL,
    -- When the device recorded it, not when it arrived. Devices buffer through
    -- tunnels and dead zones, so arrival order is not position order, and
    -- treating it as such produces a track that jumps backwards.
    recorded_at TIMESTAMPTZ NOT NULL,
    lat         NUMERIC(9, 6)  NOT NULL,
    lon         NUMERIC(9, 6)  NOT NULL,
    speed_kph   NUMERIC(6, 2),
    heading_deg NUMERIC(6, 2),
    accuracy_m  NUMERIC(8, 2),
    ignition_on BOOLEAN,
    source      TEXT        NOT NULL DEFAULT 'DEVICE',
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT gps_ping_lat_range CHECK (lat BETWEEN -90 AND 90),
    CONSTRAINT gps_ping_lon_range CHECK (lon BETWEEN -180 AND 180),
    CONSTRAINT gps_ping_source_valid CHECK (source IN ('DEVICE', 'MOBILE', 'MANUAL', 'IMPORT'))
);

-- The only two reads that matter: the latest position of a vehicle, and the
-- track of a trip. Both lead with tenant_id, and both are descending on time
-- because every question about a position stream is about the recent end of it.
CREATE INDEX gps_ping_vehicle_idx ON gps_ping (tenant_id, vehicle_id, recorded_at DESC);
CREATE INDEX gps_ping_trip_idx ON gps_ping (tenant_id, trip_id, recorded_at DESC);

-- No unique constraint on (vehicle, recorded_at). Two devices on one vehicle,
-- or a device replaying its buffer, both produce legitimate duplicates, and a
-- constraint here would reject a whole batch because of one repeated point.
-- Deduplication is the reader's problem, not the writer's.

-- ---------------------------------------------------------------------------
-- Geofence crossings
-- ---------------------------------------------------------------------------

-- Kept as their own record rather than inferred from the ping stream on demand.
-- Once gps_ping is pruned, the crossings are all that is left of why a trip
-- changed state, and that is the audit a customer dispute turns on.
CREATE TABLE geofence_event
(
    id          UUID PRIMARY KEY,
    tenant_id   UUID        NOT NULL REFERENCES tenant (id) ON DELETE CASCADE,
    trip_id     UUID        NOT NULL REFERENCES trip (id) ON DELETE CASCADE,
    terminal_id UUID        NOT NULL REFERENCES terminal (id) ON DELETE RESTRICT,
    event_type  TEXT        NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    -- The point that triggered it, so an operator can see where the boundary
    -- was actually crossed rather than being told only that it was.
    lat         NUMERIC(9, 6)  NOT NULL,
    lon         NUMERIC(9, 6)  NOT NULL,
    version     BIGINT,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT geofence_event_type_valid CHECK (event_type IN ('ENTERED', 'EXITED'))
);

CREATE INDEX geofence_event_trip_idx ON geofence_event (tenant_id, trip_id, occurred_at);

-- Which terminals a trip is currently inside. A tiny table -- one row per trip
-- per terminal it is in, which is almost always zero or one -- and it is what
-- makes crossing detection a comparison rather than a scan back through the
-- ping history on every point received.
CREATE TABLE geofence_presence
(
    tenant_id   UUID        NOT NULL REFERENCES tenant (id) ON DELETE CASCADE,
    trip_id     UUID        NOT NULL REFERENCES trip (id) ON DELETE CASCADE,
    terminal_id UUID        NOT NULL REFERENCES terminal (id) ON DELETE CASCADE,
    entered_at  TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (trip_id, terminal_id)
);

CREATE INDEX geofence_presence_trip_idx ON geofence_presence (tenant_id, trip_id);

-- ---------------------------------------------------------------------------
-- 3.7.2 Route deviation
-- ---------------------------------------------------------------------------

CREATE TABLE route_deviation
(
    id          UUID PRIMARY KEY,
    tenant_id   UUID        NOT NULL REFERENCES tenant (id) ON DELETE CASCADE,
    trip_id     UUID        NOT NULL REFERENCES trip (id) ON DELETE CASCADE,
    detected_at TIMESTAMPTZ NOT NULL,
    lat         NUMERIC(9, 6)  NOT NULL,
    lon         NUMERIC(9, 6)  NOT NULL,
    distance_m  NUMERIC(12, 2) NOT NULL,
    corridor_m  NUMERIC(12, 2) NOT NULL,
    -- Raised once per excursion, not once per ping. A lorry twenty kilometres
    -- off route reports every thirty seconds; an alert per ping is an alert
    -- nobody reads.
    resolved_at TIMESTAMPTZ,
    version     BIGINT,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX route_deviation_trip_idx ON route_deviation (tenant_id, trip_id, detected_at DESC);
CREATE INDEX route_deviation_open_idx ON route_deviation (tenant_id, detected_at DESC)
    WHERE resolved_at IS NULL;

-- ---------------------------------------------------------------------------
-- Retention: the simplified track that survives pruning
-- ---------------------------------------------------------------------------

-- A completed trip's ping history is collapsed to a Douglas-Peucker simplified
-- polyline and the raw points are deleted. On a 1 GB database a single lorry
-- reporting every thirty seconds for a week is roughly twenty thousand rows;
-- the simplified line is a few hundred points and is all anyone ever looks at
-- after the trip is over.
CREATE TABLE trip_track
(
    id            UUID PRIMARY KEY,
    tenant_id     UUID        NOT NULL REFERENCES tenant (id) ON DELETE CASCADE,
    trip_id       UUID        NOT NULL REFERENCES trip (id) ON DELETE CASCADE,
    -- [[lon, lat], ...] in GeoJSON order, so it can be handed to a map library
    -- without transformation. See DOCS/adr/0004 for why this is JSONB and not
    -- PostGIS geometry.
    points        JSONB       NOT NULL,
    point_count   INT         NOT NULL,
    raw_count     INT         NOT NULL,
    distance_m    NUMERIC(12, 2),
    simplified_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    version       BIGINT,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT trip_track_trip_unique UNIQUE (trip_id)
);

CREATE INDEX trip_track_trip_idx ON trip_track (tenant_id, trip_id);

-- ---------------------------------------------------------------------------
-- Row-level security
-- ---------------------------------------------------------------------------

DO
$$
    DECLARE
        t TEXT;
    BEGIN
        FOREACH t IN ARRAY ARRAY [
            'gps_ping', 'geofence_event', 'geofence_presence',
            'route_deviation', 'trip_track'
            ]
            LOOP
                EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
                EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', t);
                EXECUTE format(
                        'CREATE POLICY %I ON %I USING (tenant_id = app_current_tenant()) '
                            || 'WITH CHECK (tenant_id = app_current_tenant())',
                        t || '_isolation', t);
            END LOOP;
    END
$$;
