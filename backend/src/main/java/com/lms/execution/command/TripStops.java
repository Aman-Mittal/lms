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
import java.util.Optional;
import java.util.UUID;

import com.lms.execution.command.domain.Trip;
import com.lms.execution.command.domain.TripStop;
import com.lms.execution.events.TripStopArrived;
import com.lms.execution.events.TripStopDeparted;
import com.lms.execution.events.TripStopSkipped;
import com.lms.planning.api.LoadLifecyclePort;
import com.lms.shared.audit.AuditTrail;
import com.lms.shared.config.Json;
import com.lms.shared.tenant.TenantContext;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * A trip's ordered stops, moved by whoever has the news: a person through
 * {@link TripStopService}, the trip's own milestones (gate-in, dispatch,
 * arrival, completion) and a geofence crossing through
 * {@link TripTrackingAdapter}. Joins the caller's transaction and checks no
 * permission.
 *
 * <p>A stop is found by terminal for the automatic paths: an arrival is the
 * earliest stop there still PLANNED, a departure the earliest one ARRIVED.
 * The mirrored paths never refuse -- a trip's milestone is a fact and the
 * stops follow it, with times clamped where a back-dated milestone would
 * otherwise put a departure before its arrival.
 *
 * <p>Every move is audited on the trip (resource TRIP) and published, so the
 * trip's history and the event stream carry the stop timings.
 */
@Component
class TripStops {

    private final TripStopRepository stops;
    private final LoadLifecyclePort loads;
    private final AuditTrail audit;
    private final ApplicationEventPublisher events;

    TripStops(TripStopRepository stops, LoadLifecyclePort loads, AuditTrail audit,
              ApplicationEventPublisher events) {
        this.stops = stops;
        this.loads = loads;
        this.audit = audit;
        this.events = events;
    }

    /** A new trip's stops, from its load's drops: the origin pickup, then each drop in order. */
    void deriveFor(Trip trip) {
        List<TripStop.Draft> drafts = TripStop.derive(trip.originTerminalId(),
                loads.dropTerminalsOf(trip.loadId()), trip.destinationTerminalId());
        insert(trip, drafts);
    }

    /** Replaces the plan with {@code drafts}; the caller has checked that nothing has progressed. */
    List<TripStop> replace(Trip trip, List<TripStop.Draft> drafts) {
        stops.deleteByTrip(trip.tenantId(), trip.id());
        return insert(trip, drafts);
    }

    List<TripStop> of(Trip trip) {
        return stops.findByTrip(trip.tenantId(), trip.id());
    }

    private List<TripStop> insert(Trip trip, List<TripStop.Draft> drafts) {
        UUID tenantId = TenantContext.requireTenantId();
        java.util.ArrayList<TripStop> saved = new java.util.ArrayList<>();
        int seq = 1;
        for (TripStop.Draft draft : drafts) {
            saved.add(stops.save(TripStop.planned(UUID.randomUUID(), tenantId, trip.id(), seq++, draft)));
        }
        return saved;
    }

    // ---------------------------------------------------------- by terminal

    /** The earliest stop at the terminal still waiting is reached; nothing when there is none. */
    Optional<TripStop> arrivedAt(Trip trip, UUID terminalId, Instant at, TripStop.Source source) {
        List<TripStop> all = of(trip);
        return all.stream().filter(s -> s.isPlanned() && s.terminalId().equals(terminalId)).findFirst()
                .map(target -> arrive(trip, all, target, at, source));
    }

    /** The earliest stop at the terminal that has been reached is left; nothing when there is none. */
    Optional<TripStop> leftTerminal(Trip trip, UUID terminalId, Instant at, TripStop.Source source) {
        List<TripStop> all = of(trip);
        return all.stream().filter(s -> s.isHere() && s.terminalId().equals(terminalId)).findFirst()
                .map(target -> depart(trip, all, target, at.isBefore(target.arrivedAt()) ? target.arrivedAt() : at,
                        source));
    }

    // ------------------------------------------------------------- by stop

    TripStop arrive(Trip trip, List<TripStop> all, TripStop target, Instant at, TripStop.Source source) {
        boolean outOfOrder = TripStop.arrivesOutOfOrder(all, target);
        TripStop arrived = stops.save(target.arrive(at, source, outOfOrder));
        audit.recordAtomically("TRIP_STOP_ARRIVED", "TRIP", trip.id(), snapshot(trip, target),
                snapshot(trip, arrived, "at", at.toString(), "source", source.name()));
        events.publishEvent(new TripStopArrived(trip.tenantId(), trip.id(), trip.tripNo(), arrived.id(),
                arrived.seq(), arrived.kind().name(), arrived.terminalId(), arrived.plannedArrivalAt(), at,
                outOfOrder, source.name(), Instant.now()));
        return arrived;
    }

    TripStop depart(Trip trip, List<TripStop> all, TripStop target, Instant at, TripStop.Source source) {
        boolean last = all.stream().noneMatch(s -> s.seq() > target.seq() && s.status() != TripStop.Status.SKIPPED);
        TripStop left = stops.save(target.depart(at, source, last));
        audit.recordAtomically(last ? "TRIP_STOP_COMPLETED" : "TRIP_STOP_DEPARTED", "TRIP", trip.id(),
                snapshot(trip, target), snapshot(trip, left, "at", at.toString(), "source", source.name()));
        events.publishEvent(new TripStopDeparted(trip.tenantId(), trip.id(), trip.tripNo(), left.id(), left.seq(),
                left.kind().name(), left.terminalId(), left.arrivedAt(), at, last, source.name(), Instant.now()));
        return left;
    }

    TripStop skip(Trip trip, TripStop target, String reason, Instant at) {
        TripStop skipped = stops.save(target.skip(reason, at));
        audit.recordAtomically("TRIP_STOP_SKIPPED", "TRIP", trip.id(), snapshot(trip, target),
                snapshot(trip, skipped, "reason", skipped.skipReason()));
        events.publishEvent(new TripStopSkipped(trip.tenantId(), trip.id(), trip.tripNo(), skipped.id(),
                skipped.seq(), skipped.kind().name(), skipped.terminalId(), skipped.skipReason(), at,
                Instant.now()));
        return skipped;
    }

    private static Json snapshot(Trip trip, TripStop stop, String... extra) {
        String[] pairs = new String[10 + extra.length];
        int i = 0;
        pairs[i++] = "tripNo";
        pairs[i++] = trip.tripNo();
        pairs[i++] = "stop";
        pairs[i++] = String.valueOf(stop.seq());
        pairs[i++] = "kind";
        pairs[i++] = stop.kind().name();
        pairs[i++] = "terminalId";
        pairs[i++] = stop.terminalId().toString();
        pairs[i++] = "status";
        pairs[i++] = stop.status().name();
        for (String e : extra) {
            pairs[i++] = e;
        }
        return Json.object(pairs);
    }
}
