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
package com.lms.execution.command;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.lms.execution.command.domain.Trip;
import com.lms.execution.command.domain.TripStop;
import com.lms.masterdata.api.TerminalOrgUnitPort;
import com.lms.shared.audit.AuditTrail;
import com.lms.shared.config.Json;
import com.lms.shared.error.BusinessRuleViolationException;
import com.lms.shared.error.ResourceNotFoundException;
import com.lms.shared.web.ConditionalGet;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Planning a trip's stops and recording them by hand (issue #223); the
 * automatic paths are in {@link TripStops}.
 *
 * <p>The plan is replaced whole, with {@code If-Match} carrying the tag the
 * list was read with ({@link #tag}), and only while no stop has been reached
 * or skipped: a stop with a history is a fact, not a plan. Arrival, departure
 * and skipping are each recorded once and refused by name when the stop is not
 * in the state that allows them ({@code trip-stop-illegal-transition}); a
 * departure cannot precede its arrival. Like the trip's own milestones they
 * take no {@code If-Match}: the state machine is the guard.
 */
@Service
public class TripStopService {

    private final TripRepository trips;
    private final TripStopRepository stopRows;
    private final TripStops stops;
    private final TerminalOrgUnitPort terminals;
    private final AuditTrail audit;

    public TripStopService(TripRepository trips, TripStopRepository stopRows, TripStops stops,
                           TerminalOrgUnitPort terminals, AuditTrail audit) {
        this.trips = trips;
        this.stopRows = stopRows;
        this.stops = stops;
        this.terminals = terminals;
        this.audit = audit;
    }

    /** The tag of a list of stops: what a read hands out as its ETag and a replacement must send back. */
    public static String tag(List<?> identityParts) {
        return ConditionalGet.tag(identityParts);
    }

    @Transactional
    @PreAuthorize("hasAuthority('TRIP_EXECUTE') and @abac.permits('TRIP_EXECUTE', 'TRIP', #tripId)")
    public void replace(UUID tripId, String ifMatch, List<TripStop.Draft> drafts) {
        if (ifMatch == null || ifMatch.isBlank()) {
            throw new IllegalArgumentException("An If-Match header carrying the stops' ETag is required");
        }
        Trip trip = requireLive(tripId);
        List<TripStop> current = stops.of(trip);
        String expected = tag(current.stream().flatMap(s -> java.util.stream.Stream.of(s.id(), s.version())).toList());
        if (!normalise(ifMatch).equals(normalise(expected))) {
            throw new OptimisticLockingFailureException("The stops of trip " + trip.tripNo()
                    + " were changed since they were read");
        }
        if (current.stream().anyMatch(s -> !s.isPlanned())) {
            throw new BusinessRuleViolationException("trip-stops-in-progress",
                    "Trip " + trip.tripNo() + " has stops already reached or skipped; its plan is no longer replaced");
        }
        TripStop.requireValid(drafts);
        for (TripStop.Draft draft : drafts) {
            if (terminals.orgUnitOf(draft.terminalId()).isEmpty()) {
                throw new ResourceNotFoundException("Terminal", draft.terminalId());
            }
        }
        List<TripStop> replaced = stops.replace(trip, drafts);
        audit.recordAtomically("TRIP_STOPS_PLANNED", "TRIP", tripId, Json.object("stops", String.valueOf(current.size())),
                Json.object("stops", String.valueOf(replaced.size()), "tripNo", trip.tripNo()));
    }

    @Transactional
    @PreAuthorize("hasAuthority('TRIP_EXECUTE')")
    public void arrive(UUID tripId, UUID stopId, Instant at) {
        Trip trip = requireLive(tripId);
        List<TripStop> all = stops.of(trip);
        stops.arrive(trip, all, find(all, stopId), at == null ? Instant.now() : at, TripStop.Source.MANUAL);
    }

    @Transactional
    @PreAuthorize("hasAuthority('TRIP_EXECUTE')")
    public void depart(UUID tripId, UUID stopId, Instant at) {
        Trip trip = requireLive(tripId);
        List<TripStop> all = stops.of(trip);
        stops.depart(trip, all, find(all, stopId), at == null ? Instant.now() : at, TripStop.Source.MANUAL);
    }

    @Transactional
    @PreAuthorize("hasAuthority('TRIP_EXECUTE')")
    public void skip(UUID tripId, UUID stopId, String reason) {
        Trip trip = requireLive(tripId);
        stops.skip(trip, find(stops.of(trip), stopId), reason, Instant.now());
    }

    private Trip requireLive(UUID tripId) {
        Trip trip = trips.findById(tripId).orElseThrow(() -> new ResourceNotFoundException("Trip", tripId));
        if (trip.status() == Trip.TripStatus.CANCELLED || trip.status() == Trip.TripStatus.COMPLETED) {
            throw new BusinessRuleViolationException("trip-stops-trip-closed",
                    "Trip " + trip.tripNo() + " is " + trip.status() + "; its stops are no longer changed");
        }
        return trip;
    }

    private static TripStop find(List<TripStop> all, UUID stopId) {
        return all.stream().filter(s -> s.id().equals(stopId)).findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("TripStop", stopId));
    }

    private static String normalise(String tag) {
        String raw = tag.strip();
        if (raw.startsWith("W/")) {
            raw = raw.substring(2);
        }
        return raw.startsWith("\"") && raw.endsWith("\"") && raw.length() >= 2 ? raw.substring(1, raw.length() - 1) : raw;
    }
}
