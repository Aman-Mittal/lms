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
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.lms.execution.api.TripTrackingPort;
import com.lms.masterdata.api.FleetCapacityPort;
import com.lms.masterdata.api.TerminalGeofencePort;
import com.lms.shared.geo.GeoUtils;
import com.lms.shared.geo.LatLon;
import com.lms.shared.tenant.TenantContext;
import com.lms.telematics.api.PingIngestPort;
import com.lms.telematics.command.domain.GeofenceEvent;
import com.lms.telematics.command.domain.GpsPing;
import com.lms.telematics.events.GeofenceCrossed;
import com.lms.telematics.events.RouteDeviationDetected;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Turns a batch of coordinates into trip state changes.
 *
 * <p>This is the loop closing. Per point: sanity-filter it, persist it, work
 * out which trip it belongs to, test it against the geofences it might be
 * inside, and tell execution what changed.
 *
 * <p>Everything is done synchronously inside the ingest transaction. That is
 * affordable because the geometry is bounded -- a bounding-box index query
 * returns a handful of candidates and ray-casting a polygon of a few dozen
 * vertices is nanoseconds -- and it is necessary because a geofence crossing
 * drives a state machine that a customer is watching.
 */
@Service
public class PingIngestService implements PingIngestPort {

    private static final Logger log = LoggerFactory.getLogger(PingIngestService.class);

    /**
     * The largest batch that will be accepted in one call.
     *
     * <p>A device flushing a long outage can offer thousands of points, and a
     * single transaction that large would hold a connection from a pool of five
     * for long enough to stall every other request on the instance.
     */
    private static final int MAX_BATCH = 500;

    private final PingWriter pings;
    private final GeofencePresenceStore presence;
    private final GeofenceEventRepository geofenceEvents;
    private final RouteDeviationService deviations;
    private final TerminalGeofencePort terminals;
    private final FleetCapacityPort fleet;
    private final TripTrackingPort tripTracking;
    private final ApplicationEventPublisher events;

    public PingIngestService(PingWriter pings, GeofencePresenceStore presence,
                             GeofenceEventRepository geofenceEvents,
                             RouteDeviationService deviations,
                             TerminalGeofencePort terminals, FleetCapacityPort fleet,
                             TripTrackingPort tripTracking,
                             ApplicationEventPublisher events) {
        this.pings = pings;
        this.presence = presence;
        this.geofenceEvents = geofenceEvents;
        this.deviations = deviations;
        this.terminals = terminals;
        this.fleet = fleet;
        this.tripTracking = tripTracking;
        this.events = events;
    }

    @Override
    @Transactional
    @PreAuthorize("hasAuthority('TELEMATICS_INGEST')")
    public IngestResult ingest(List<PingReport> batch) {
        UUID tenantId = TenantContext.requireTenantId();

        if (batch == null || batch.isEmpty()) {
            return new IngestResult(0, 0, List.of());
        }
        if (batch.size() > MAX_BATCH) {
            throw new IllegalArgumentException(
                    "Batch of " + batch.size() + " exceeds the maximum of " + MAX_BATCH);
        }

        Instant now = Instant.now();
        List<Rejection> rejections = new ArrayList<>();
        List<GpsPing> accepted = new ArrayList<>();

        // Sorted by device time, not by arrival order. A device flushing a
        // buffer sends what it has, and processing a later point before an
        // earlier one would make the jump filter compare against the future and
        // the geofence state flap.
        List<PingReport> ordered = batch.stream()
                .filter(p -> p.recordedAt() != null)
                .sorted(Comparator.comparing(PingReport::recordedAt))
                .toList();

        batch.stream()
                .filter(p -> p.recordedAt() == null)
                .forEach(p -> rejections.add(
                        new Rejection(p.vehicleId(), null, "no recorded timestamp")));

        // The last accepted position per vehicle, seeded from the database and
        // then carried forward within the batch. Without carrying it forward,
        // every point in a flush would be compared against the same stale fix
        // and a genuine journey would look like a series of impossible jumps.
        Map<UUID, LatLon> lastPoint = new HashMap<>();
        Map<UUID, Instant> lastAt = new HashMap<>();
        Map<UUID, Boolean> knownVehicles = new HashMap<>();

        for (PingReport report : ordered) {
            UUID vehicleId = report.vehicleId();

            if (vehicleId != null && !lastPoint.containsKey(vehicleId)) {
                pings.lastKnown(tenantId, vehicleId).ifPresent(last -> {
                    lastPoint.put(vehicleId, last.point());
                    lastAt.put(vehicleId, last.recordedAt());
                });
            }

            Optional<String> refusal = PingSanityFilter.reject(report, now,
                    lastPoint.get(vehicleId), lastAt.get(vehicleId));

            if (refusal.isPresent()) {
                rejections.add(new Rejection(vehicleId, report.recordedAt(), refusal.get()));
                continue;
            }

            // The vehicle identifier comes from the caller, and row-level
            // security does not cover it: the foreign key to `vehicle` is
            // checked by the system and does not consult the policy, so an
            // unchecked identifier would let one tenant file positions against
            // another tenant's lorry. Cached per batch -- a flush is one
            // vehicle, so this is one lookup, not one per point.
            if (!knownVehicles.computeIfAbsent(vehicleId, fleet::isRegistered)) {
                rejections.add(new Rejection(vehicleId, report.recordedAt(),
                        "no such vehicle on this tenant's register"));
                continue;
            }

            Optional<TripTrackingPort.ActiveTrip> trip = tripTracking.activeTripFor(vehicleId);

            GpsPing ping = new GpsPing(UUID.randomUUID(), tenantId, vehicleId,
                    trip.map(TripTrackingPort.ActiveTrip::tripId).orElse(null),
                    report.recordedAt(), report.lat(), report.lon(), report.speedKph(),
                    report.headingDeg(), report.accuracyM(), report.ignitionOn(),
                    report.source() == null ? "DEVICE" : report.source(), now);

            accepted.add(ping);
            lastPoint.put(vehicleId, ping.point());
            lastAt.put(vehicleId, report.recordedAt());

            trip.ifPresent(active -> evaluate(tenantId, active, ping));
        }

        pings.insertAll(accepted);

        if (!rejections.isEmpty()) {
            log.info("Ingested {} points, rejected {}", accepted.size(), rejections.size());
        }
        return new IngestResult(accepted.size(), rejections.size(), rejections);
    }

    // ------------------------------------------------------------- geofencing

    /**
     * Compares where the vehicle is now with where it was, and reports the
     * difference.
     *
     * <p>Crossings are derived from a stored presence set rather than by
     * scanning back through the ping history. The set is one row per trip per
     * terminal it is inside -- almost always zero or one -- which turns
     * "did it just enter" into a comparison instead of a query per point.
     */
    private void evaluate(UUID tenantId, TripTrackingPort.ActiveTrip trip, GpsPing ping) {
        LatLon point = ping.point();

        Set<UUID> inside = new LinkedHashSet<>();
        for (TerminalGeofencePort.TerminalMatch match : terminals.terminalsContaining(point)) {
            inside.add(match.terminalId());
        }

        Set<UUID> wasInside = presence.terminalsFor(tenantId, trip.tripId());

        for (UUID terminalId : inside) {
            if (!wasInside.contains(terminalId)) {
                presence.enter(tenantId, trip.tripId(), terminalId, ping.recordedAt());
                record(tenantId, trip, terminalId, GeofenceEvent.EventType.ENTERED, ping);
                tripTracking.observeGeofenceEntry(trip.tripId(), terminalId, ping.recordedAt());
            }
        }

        for (UUID terminalId : wasInside) {
            if (!inside.contains(terminalId)) {
                presence.exit(tenantId, trip.tripId(), terminalId);
                record(tenantId, trip, terminalId, GeofenceEvent.EventType.EXITED, ping);
                tripTracking.observeGeofenceExit(trip.tripId(), terminalId, ping.recordedAt());
            }
        }

        deviations.evaluate(tenantId, trip, ping)
                .ifPresent(deviation -> events.publishEvent(new RouteDeviationDetected(
                        tenantId, trip.tripId(), trip.tripNo(), deviation.distanceM(),
                        deviation.corridorM(), ping.lat(), ping.lon(),
                        ping.recordedAt(), Instant.now())));
    }

    private void record(UUID tenantId, TripTrackingPort.ActiveTrip trip, UUID terminalId,
                        GeofenceEvent.EventType type, GpsPing ping) {
        geofenceEvents.save(GeofenceEvent.of(UUID.randomUUID(), tenantId, trip.tripId(),
                terminalId, type, ping.recordedAt(), ping.lat(), ping.lon()));

        events.publishEvent(new GeofenceCrossed(tenantId, trip.tripId(), trip.tripNo(),
                terminalId, type.name(), ping.recordedAt(), Instant.now()));
    }

    /** How far apart two coordinates are, exposed for the query side's ETA. */
    static BigDecimal metresBetween(LatLon a, LatLon b) {
        return BigDecimal.valueOf(GeoUtils.haversineMetres(a, b)).setScale(2, java.math.RoundingMode.HALF_UP);
    }
}
