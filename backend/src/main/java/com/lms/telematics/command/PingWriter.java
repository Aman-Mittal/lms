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
package com.lms.telematics.command;

import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.lms.telematics.command.domain.GpsPing;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Writes and reads position points.
 *
 * <p>Plain SQL rather than a repository, for the one table where it is worth
 * it. A device flush is dozens of rows, and {@code saveAll} on a Spring Data
 * JDBC repository is a statement per row -- dozens of round trips per flush,
 * against a pool of five connections, on 0.1 of a CPU. A single multi-row
 * INSERT is one.
 *
 * <p>{@code Instant} is bound as {@code OffsetDateTime} throughout: the
 * PostgreSQL driver refuses to infer a SQL type for a bare Instant, and there
 * are no Spring Data converters on this path.
 */
@Component
public class PingWriter {

    private final JdbcClient jdbc;

    public PingWriter(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** One multi-row INSERT for the whole batch. */
    public void insertAll(List<GpsPing> batch) {
        if (batch.isEmpty()) {
            return;
        }

        StringBuilder sql = new StringBuilder("""
                INSERT INTO gps_ping
                    (id, tenant_id, vehicle_id, trip_id, recorded_at, lat, lon,
                     speed_kph, heading_deg, accuracy_m, ignition_on, source, created_at)
                VALUES
                """);

        for (int i = 0; i < batch.size(); i++) {
            sql.append(i == 0 ? "" : ",\n")
                    .append("(:id").append(i)
                    .append(", :tenantId").append(i)
                    .append(", :vehicleId").append(i)
                    .append(", :tripId").append(i)
                    .append(", :recordedAt").append(i)
                    .append(", :lat").append(i)
                    .append(", :lon").append(i)
                    .append(", :speed").append(i)
                    .append(", :heading").append(i)
                    .append(", :accuracy").append(i)
                    .append(", :ignition").append(i)
                    .append(", :source").append(i)
                    .append(", :createdAt").append(i)
                    .append(")");
        }

        var statement = jdbc.sql(sql.toString());
        for (int i = 0; i < batch.size(); i++) {
            GpsPing ping = batch.get(i);
            statement = statement
                    .param("id" + i, ping.id())
                    .param("tenantId" + i, ping.tenantId())
                    .param("vehicleId" + i, ping.vehicleId())
                    .param("tripId" + i, ping.tripId())
                    .param("recordedAt" + i, ping.recordedAt().atOffset(ZoneOffset.UTC))
                    .param("lat" + i, ping.lat())
                    .param("lon" + i, ping.lon())
                    .param("speed" + i, ping.speedKph())
                    .param("heading" + i, ping.headingDeg())
                    .param("accuracy" + i, ping.accuracyM())
                    .param("ignition" + i, ping.ignitionOn())
                    .param("source" + i, ping.source())
                    .param("createdAt" + i, ping.createdAt().atOffset(ZoneOffset.UTC));
        }
        statement.update();
    }

    /**
     * The most recent accepted position for a vehicle.
     *
     * <p>Served by {@code gps_ping_vehicle_idx}, whose descending time ordering
     * makes this a single index seek rather than a scan of the vehicle's
     * history.
     */
    public Optional<GpsPing> lastKnown(UUID tenantId, UUID vehicleId) {
        return jdbc.sql("""
                        SELECT * FROM gps_ping
                         WHERE tenant_id = :tenantId AND vehicle_id = :vehicleId
                         ORDER BY recorded_at DESC
                         LIMIT 1
                        """)
                .param("tenantId", tenantId)
                .param("vehicleId", vehicleId)
                .query(GpsPing.class)
                .optional();
    }

    /** Every point of a trip, oldest first, for building the simplified track. */
    public List<GpsPing> trackOf(UUID tenantId, UUID tripId) {
        return jdbc.sql("""
                        SELECT * FROM gps_ping
                         WHERE tenant_id = :tenantId AND trip_id = :tripId
                         ORDER BY recorded_at
                        """)
                .param("tenantId", tenantId)
                .param("tripId", tripId)
                .query(GpsPing.class)
                .list();
    }

    public int deleteForTrip(UUID tenantId, UUID tripId) {
        return jdbc.sql("DELETE FROM gps_ping WHERE tenant_id = :tenantId AND trip_id = :tripId")
                .param("tenantId", tenantId)
                .param("tripId", tripId)
                .update();
    }

    /**
     * Prunes points older than the cut-off that belong to no trip.
     *
     * <p>Trip-bound points are removed by the track simplifier instead, which
     * collapses them into a polyline first. Deleting them here would destroy
     * the shape before anything had recorded it.
     */
    public int pruneUnattached(UUID tenantId, java.time.Instant before) {
        return jdbc.sql("""
                        DELETE FROM gps_ping
                         WHERE tenant_id = :tenantId
                           AND trip_id IS NULL
                           AND recorded_at < :before
                        """)
                .param("tenantId", tenantId)
                .param("before", before.atOffset(ZoneOffset.UTC))
                .update();
    }
}
