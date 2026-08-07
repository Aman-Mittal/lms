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

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import com.lms.shared.config.Json;
import com.lms.shared.geo.GeoUtils;
import com.lms.shared.geo.LatLon;
import com.lms.shared.geo.Simplify;
import com.lms.shared.tenant.TenantSweep;
import com.lms.telematics.command.domain.GpsPing;
import com.lms.telematics.command.domain.TripTrack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Collapses finished trips into a simplified track and deletes the raw points.
 *
 * <p>The free database is 1 GB. One lorry reporting every thirty seconds for a
 * week is roughly twenty thousand rows, and a modest fleet fills the database
 * in weeks. Without this job the platform stops working, and it stops working
 * by running out of space rather than by throwing anything a log would show.
 *
 * <p>Also runs shortly after startup, not only on a fixed delay. The instance
 * sleeps after fifteen idle minutes, so a purely wall-clock schedule would
 * simply not fire -- the job would exist and never run, which is the worst kind
 * of retention policy because it looks like one.
 */
@Component
public class TrackRetentionJob {

    private static final Logger log = LoggerFactory.getLogger(TrackRetentionJob.class);

    /**
     * How far a point may sit from the line between its retained neighbours
     * before it is kept.
     *
     * <p>Twenty metres keeps every turn and junction while discarding the long
     * straight runs, which is where nearly all the volume is.
     */
    private static final double SIMPLIFY_TOLERANCE_M = 20;

    private final PingWriter pings;
    private final TripTrackRepository tracks;
    private final GeofencePresenceStore presence;
    private final JdbcClient jdbc;
    private final TenantSweep tenantSweep;

    /** How long raw points survive after a trip finishes. */
    private final int retentionDays;

    public TrackRetentionJob(PingWriter pings, TripTrackRepository tracks,
                             GeofencePresenceStore presence, JdbcClient jdbc,
                             TenantSweep tenantSweep,
                             @Value("${lms.telematics.retention-days:7}") int retentionDays) {
        this.pings = pings;
        this.tracks = tracks;
        this.presence = presence;
        this.jdbc = jdbc;
        this.tenantSweep = tenantSweep;
        this.retentionDays = retentionDays;
    }

    @Scheduled(initialDelay = 45_000, fixedDelay = 6 * 60 * 60 * 1000)
    public void compactFinishedTrips() {
        Instant before = Instant.now().minus(retentionDays, ChronoUnit.DAYS);

        tenantSweep.forEachTenant("telematics-retention", tenantId -> {
            int compacted = 0;
            for (UUID tripId : finishedTripsWithRawPoints(tenantId, before)) {
                try {
                    compacted += compact(tenantId, tripId);
                } catch (RuntimeException e) {
                    // One malformed track must not stop the rest. The alternative
                    // is a single bad trip freezing retention for the whole
                    // tenant, and nobody noticing until the disk is full.
                    log.error("Could not compact track for trip {}", tripId, e);
                }
            }
            compacted += pings.pruneUnattached(tenantId, before);
            return compacted;
        });
    }

    /**
     * Simplifies one trip's track and removes its raw points.
     *
     * @return how many raw rows were removed
     */
    int compact(UUID tenantId, UUID tripId) {
        List<GpsPing> raw = pings.trackOf(tenantId, tripId);
        if (raw.isEmpty()) {
            return 0;
        }

        List<LatLon> points = raw.stream().map(GpsPing::point).toList();
        List<LatLon> simplified = Simplify.douglasPeucker(points, SIMPLIFY_TOLERANCE_M);

        // Distance is measured on the full track, not the simplified one.
        // Simplification removes points that are close to a straight line, but
        // the small deviations it discards still add up over a long journey, and
        // a distance figure that shrank when the data was tidied would be wrong
        // in a way somebody eventually bills on.
        BigDecimal distance = BigDecimal.valueOf(Simplify.lengthMetres(points))
                .setScale(2, RoundingMode.HALF_UP);

        tracks.save(new TripTrack(UUID.randomUUID(), tenantId, tripId,
                Json.of(toGeoJson(simplified)), simplified.size(), raw.size(),
                distance, Instant.now(), null, Instant.now()));

        presence.clear(tenantId, tripId);
        int removed = pings.deleteForTrip(tenantId, tripId);

        log.debug("Compacted trip {}: {} points to {}, {} m", tripId, raw.size(),
                simplified.size(), distance);
        return removed;
    }

    /**
     * Trips that have finished, are older than the cut-off, and still hold raw
     * points.
     *
     * <p>The {@code NOT EXISTS} on {@code trip_track} is what makes the job
     * idempotent: a trip already compacted is not a candidate, so a restart
     * mid-sweep resumes rather than redoing work or duplicating tracks.
     */
    private List<UUID> finishedTripsWithRawPoints(UUID tenantId, Instant before) {
        return jdbc.sql("""
                        SELECT DISTINCT t.id
                          FROM trip t
                         WHERE t.tenant_id = :tenantId
                           AND t.status IN ('COMPLETED', 'CANCELLED')
                           AND t.updated_at < :before
                           AND EXISTS (SELECT 1 FROM gps_ping p WHERE p.trip_id = t.id)
                           AND NOT EXISTS (SELECT 1 FROM trip_track k WHERE k.trip_id = t.id)
                         LIMIT 50
                        """)
                .param("tenantId", tenantId)
                .param("before", before.atOffset(java.time.ZoneOffset.UTC))
                .query(UUID.class)
                .list();
    }

    /**
     * Serialised by hand into {@code [[lon, lat], ...]}.
     *
     * <p>GeoJSON order, which is longitude first -- the opposite of how every
     * human writes a coordinate, and the single most common way to put a map
     * pin in the wrong hemisphere. Hand-rolled rather than routed through
     * Jackson because the payload is a fixed array of numeric pairs, and this
     * keeps a reflective serialiser out of the native image.
     */
    private static String toGeoJson(List<LatLon> points) {
        StringBuilder json = new StringBuilder("[");
        for (int i = 0; i < points.size(); i++) {
            LatLon point = points.get(i);
            json.append(i == 0 ? "" : ",")
                    .append('[').append(GeoUtils.round6(point.lon()))
                    .append(',').append(GeoUtils.round6(point.lat())).append(']');
        }
        return json.append(']').toString();
    }
}
