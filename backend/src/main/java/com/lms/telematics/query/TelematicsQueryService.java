/*
 * Copyright 2026 Aman Mittal
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.lms.telematics.query;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.lms.shared.geo.GeoUtils;
import com.lms.shared.geo.LatLon;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read side for telematics.
 *
 * <p>The latest-position query uses {@code DISTINCT ON}, which is PostgreSQL's
 * answer to "the newest row per group" and is a single index scan against
 * {@code gps_ping_vehicle_idx}. The portable formulations -- a correlated
 * subquery, or a window function with an outer filter -- both read the whole
 * partition. On the highest-volume table in the platform that difference is the
 * whole cost of the screen.
 */
@Service
@Transactional(readOnly = true)
public class TelematicsQueryService {

    private final JdbcClient jdbc;

    /**
     * Average speed assumed for the straight-line ETA.
     *
     * <p>Forty kilometres per hour, which is a loaded lorry on mixed roads
     * including stops. It is a stand-in for a routing engine, not a prediction,
     * and every surface that shows it says so.
     */
    private final BigDecimal assumedSpeedKph;

    public TelematicsQueryService(JdbcClient jdbc,
                                  @Value("${lms.telematics.assumed-speed-kph:40}")
                                  BigDecimal assumedSpeedKph) {
        this.jdbc = jdbc;
        this.assumedSpeedKph = assumedSpeedKph;
    }

    /** Every vehicle's most recent fix, for the live map. */
    public List<VehiclePositionView> currentPositions() {
        return jdbc.sql("""
                        SELECT p.vehicle_id, v.registration_no,
                               p.trip_id, t.trip_no, t.status AS trip_status,
                               p.lat, p.lon, p.speed_kph, p.heading_deg, p.ignition_on,
                               p.recorded_at,
                               floor(extract(epoch FROM (now() - p.recorded_at)))::bigint
                                   AS age_seconds
                          FROM (
                                SELECT DISTINCT ON (vehicle_id) *
                                  FROM gps_ping
                                 ORDER BY vehicle_id, recorded_at DESC
                               ) p
                          JOIN vehicle v ON v.id = p.vehicle_id
                          LEFT JOIN trip t ON t.id = p.trip_id
                         ORDER BY v.registration_no
                        """)
                .query(VehiclePositionView.class)
                .list();
    }

    public Optional<VehiclePositionView> positionOf(UUID vehicleId) {
        return currentPositions().stream()
                .filter(position -> position.vehicleId().equals(vehicleId))
                .findFirst();
    }

    /** The stored geofence crossings for a trip: why it changed state, and when. */
    public List<GeofenceCrossingView> crossingsFor(UUID tripId) {
        return jdbc.sql("""
                        SELECT g.id, g.event_type, tm.code AS terminal_code,
                               g.occurred_at, g.lat, g.lon
                          FROM geofence_event g
                          JOIN terminal tm ON tm.id = g.terminal_id
                         WHERE g.trip_id = :tripId
                         ORDER BY g.occurred_at
                        """)
                .param("tripId", tripId)
                .query(GeofenceCrossingView.class)
                .list();
    }

    /**
     * The raw track while a trip is live, or the simplified one once it has
     * been compacted.
     *
     * <p>Callers do not need to know which, and must not: the raw points are
     * deleted by retention, so a screen that only knew how to read them would
     * quietly stop working a week after every trip.
     */
    public List<double[]> trackFor(UUID tripId) {
        List<double[]> raw = jdbc.sql("""
                        SELECT lon, lat FROM gps_ping
                         WHERE trip_id = :tripId
                         ORDER BY recorded_at
                        """)
                .param("tripId", tripId)
                .query((rs, rowNum) -> new double[] {rs.getDouble("lon"), rs.getDouble("lat")})
                .list();

        return raw.isEmpty() ? simplifiedTrack(tripId) : raw;
    }

    private List<double[]> simplifiedTrack(UUID tripId) {
        return jdbc.sql("""
                        SELECT jsonb_array_elements(points) ->> 0 AS lon,
                               jsonb_array_elements(points) ->> 1 AS lat
                          FROM trip_track
                         WHERE trip_id = :tripId
                        """)
                .param("tripId", tripId)
                .query((rs, rowNum) -> new double[] {
                        Double.parseDouble(rs.getString("lon")),
                        Double.parseDouble(rs.getString("lat"))})
                .list();
    }

    /**
     * Progress and a straight-line ETA.
     *
     * <p>The completion percentage is measured on distance covered towards the
     * destination, not on time elapsed. Time elapsed would show a lorry stuck in
     * a yard for four hours as most of the way there.
     */
    public Optional<TripProgressView> progressOf(UUID tripId) {
        return jdbc.sql("""
                        SELECT t.id AS trip_id, t.trip_no, t.status,
                               p.lat, p.lon, p.recorded_at AS last_fix_at,
                               ot.centre_lat AS origin_lat, ot.centre_lon AS origin_lon,
                               dt.centre_lat AS destination_lat, dt.centre_lon AS destination_lon,
                               d.distance_m AS deviation_m
                          FROM trip t
                          LEFT JOIN LATERAL (
                                SELECT lat, lon, recorded_at
                                  FROM gps_ping
                                 WHERE trip_id = t.id
                                 ORDER BY recorded_at DESC
                                 LIMIT 1
                               ) p ON true
                          LEFT JOIN LATERAL (
                                SELECT distance_m
                                  FROM route_deviation
                                 WHERE trip_id = t.id AND resolved_at IS NULL
                                 ORDER BY detected_at DESC
                                 LIMIT 1
                               ) d ON true
                          JOIN terminal ot ON ot.id = t.origin_terminal_id
                          LEFT JOIN terminal dt ON dt.id = t.destination_terminal_id
                         WHERE t.id = :tripId
                        """)
                .param("tripId", tripId)
                // listOfRows, not optionalValue: optionalValue is for a query
                // that returns one column, and this one returns eleven.
                .query()
                .listOfRows()
                .stream()
                .findFirst()
                .map(this::toProgress);
    }

    private TripProgressView toProgress(java.util.Map<String, Object> values) {

        BigDecimal lat = (BigDecimal) values.get("lat");
        BigDecimal lon = (BigDecimal) values.get("lon");
        BigDecimal destLat = (BigDecimal) values.get("destination_lat");
        BigDecimal destLon = (BigDecimal) values.get("destination_lon");
        BigDecimal originLat = (BigDecimal) values.get("origin_lat");
        BigDecimal originLon = (BigDecimal) values.get("origin_lon");
        BigDecimal deviation = (BigDecimal) values.get("deviation_m");

        BigDecimal remaining = null;
        Integer minutes = null;
        BigDecimal completion = null;

        if (lat != null && destLat != null) {
            LatLon here = new LatLon(lat.doubleValue(), lon.doubleValue());
            LatLon destination = new LatLon(destLat.doubleValue(), destLon.doubleValue());
            double toGo = GeoUtils.haversineMetres(here, destination);

            remaining = BigDecimal.valueOf(toGo).setScale(2, RoundingMode.HALF_UP);
            minutes = (int) Math.round(toGo / 1000.0 / assumedSpeedKph.doubleValue() * 60);

            if (originLat != null) {
                LatLon origin = new LatLon(originLat.doubleValue(), originLon.doubleValue());
                double total = GeoUtils.haversineMetres(origin, destination);
                if (total > 0) {
                    double done = Math.max(0, Math.min(1, (total - toGo) / total));
                    completion = BigDecimal.valueOf(done * 100).setScale(2, RoundingMode.HALF_UP);
                }
            }
        }

        return new TripProgressView((UUID) values.get("trip_id"), (String) values.get("trip_no"),
                (String) values.get("status"), lat, lon,
                // The raw driver type, not OffsetDateTime. This row is read as
                // a generic map rather than mapped to a record, so none of
                // Spring Data's conversions apply and TIMESTAMPTZ arrives as a
                // java.sql.Timestamp.
                toInstant(values.get("last_fix_at")),
                remaining, minutes, completion, deviation != null, deviation);
    }

    private static Instant toInstant(Object value) {
        return switch (value) {
            case null -> null;
            case java.sql.Timestamp timestamp -> timestamp.toInstant();
            case java.time.OffsetDateTime offset -> offset.toInstant();
            case Instant instant -> instant;
            default -> throw new IllegalStateException(
                    "Unexpected timestamp type " + value.getClass());
        };
    }

    /** Open route excursions across the fleet: the control tower's alert list. */
    public List<DeviationView> openDeviations() {
        return jdbc.sql("""
                        SELECT d.id, d.trip_id, t.trip_no, d.detected_at,
                               d.distance_m, d.corridor_m, d.lat, d.lon
                          FROM route_deviation d
                          JOIN trip t ON t.id = d.trip_id
                         WHERE d.resolved_at IS NULL
                         ORDER BY d.detected_at DESC
                         LIMIT 200
                        """)
                .query(DeviationView.class)
                .list();
    }
}
