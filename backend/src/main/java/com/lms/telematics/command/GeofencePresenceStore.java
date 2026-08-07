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

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Which terminals each trip is currently inside.
 *
 * <p>A join table with no state of its own beyond the moment of entry, so plain
 * SQL rather than an entity -- modelling it as one would mean inventing a
 * surrogate identifier nothing uses.
 *
 * <p>Its purpose is to make crossing detection a set comparison. Without it,
 * deciding whether a point represents an <em>entry</em> would mean querying the
 * previous point's containment on every ping received.
 */
@Component
public class GeofencePresenceStore {

    private final JdbcClient jdbc;

    public GeofencePresenceStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Set<UUID> terminalsFor(UUID tenantId, UUID tripId) {
        return new LinkedHashSet<>(jdbc.sql("""
                        SELECT terminal_id FROM geofence_presence
                         WHERE tenant_id = :tenantId AND trip_id = :tripId
                        """)
                .param("tenantId", tenantId)
                .param("tripId", tripId)
                .query(UUID.class)
                .list());
    }

    /**
     * Records entry.
     *
     * <p>{@code ON CONFLICT DO NOTHING} because two devices on one vehicle can
     * both report the crossing, and the second must be a no-op rather than a
     * constraint violation that aborts an otherwise good batch.
     */
    public void enter(UUID tenantId, UUID tripId, UUID terminalId, Instant at) {
        jdbc.sql("""
                        INSERT INTO geofence_presence (tenant_id, trip_id, terminal_id, entered_at)
                        VALUES (:tenantId, :tripId, :terminalId, :at)
                        ON CONFLICT (trip_id, terminal_id) DO NOTHING
                        """)
                .param("tenantId", tenantId)
                .param("tripId", tripId)
                .param("terminalId", terminalId)
                .param("at", at.atOffset(ZoneOffset.UTC))
                .update();
    }

    public void exit(UUID tenantId, UUID tripId, UUID terminalId) {
        jdbc.sql("""
                        DELETE FROM geofence_presence
                         WHERE tenant_id = :tenantId AND trip_id = :tripId
                           AND terminal_id = :terminalId
                        """)
                .param("tenantId", tenantId)
                .param("tripId", tripId)
                .param("terminalId", terminalId)
                .update();
    }

    /** Clears a finished trip's presence so the table stays proportional to live work. */
    public int clear(UUID tenantId, UUID tripId) {
        return jdbc.sql("DELETE FROM geofence_presence WHERE tenant_id = :tenantId AND trip_id = :tripId")
                .param("tenantId", tenantId)
                .param("tripId", tripId)
                .update();
    }
}
