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
package com.lms.execution.command.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.lms.shared.error.BusinessRuleViolationException;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.relational.core.mapping.Table;

/**
 * One place a trip is to be at, in order (issue #223).
 *
 * <p>The state machine is small and explicit: PLANNED goes to ARRIVED or
 * SKIPPED, ARRIVED to DEPARTED or COMPLETED; the last three end it. COMPLETED
 * is the end of the trip's last stop -- the freight is handed over -- where
 * DEPARTED is leaving a stop on the way.
 *
 * <p>Order is a plan, not a rule. A vehicle that reaches the third stop while
 * the second is still waiting has done so; {@link #arrive} records it and
 * flags {@code outOfOrder} for whoever reads the trip, instead of refusing a
 * fact. Only a stop still PLANNED can be skipped, and with a reason.
 */
@Table("trip_stop")
public record TripStop(
        @Id UUID id,
        UUID tenantId,
        UUID tripId,
        int seq,
        Kind kind,
        UUID terminalId,
        Instant plannedArrivalAt,
        Status status,
        Instant arrivedAt,
        Instant departedAt,
        Instant completedAt,
        Instant skippedAt,
        String skipReason,
        boolean outOfOrder,
        Source arrivalSource,
        Source departureSource,
        @Version Long version,
        Instant createdAt,
        Instant updatedAt) {

    public enum Kind {
        PICKUP, DROP, CHECKPOINT
    }

    public enum Status {
        PLANNED, ARRIVED, DEPARTED, COMPLETED, SKIPPED
    }

    /** What recorded a timing: a person, a geofence crossing, or the trip's own milestone. */
    public enum Source {
        MANUAL, GEOFENCE, TRIP
    }

    /** A stop as asked for, before it has an identity or an order. */
    public record Draft(Kind kind, UUID terminalId, Instant plannedArrivalAt) {
    }

    public static TripStop planned(UUID id, UUID tenantId, UUID tripId, int seq, Draft draft) {
        if (draft == null || draft.kind() == null || draft.terminalId() == null) {
            throw new IllegalArgumentException("A stop has a kind and a terminal");
        }
        Instant now = Instant.now();
        return new TripStop(id, tenantId, tripId, seq, draft.kind(), draft.terminalId(),
                draft.plannedArrivalAt(), Status.PLANNED, null, null, null, null, null, false, null, null,
                null, now, now);
    }

    /**
     * The stops a trip starts with: the pickup at its origin, then a drop at
     * each place its load delivers to, in drop order, each place once. The
     * trip's destination is always the last, whether or not the load names it.
     */
    public static List<Draft> derive(UUID origin, List<UUID> drops, UUID destination) {
        List<Draft> drafts = new ArrayList<>();
        drafts.add(new Draft(Kind.PICKUP, origin, null));
        List<UUID> places = new ArrayList<>();
        if (drops != null) {
            drops.stream().filter(java.util.Objects::nonNull).filter(d -> !places.contains(d)).forEach(places::add);
        }
        if (destination != null) {
            places.remove(destination);
            places.add(destination);
        }
        places.forEach(place -> drafts.add(new Draft(Kind.DROP, place, null)));
        return drafts;
    }

    /** The whole ordered list or nothing: every stop has a terminal, none repeats back to back. */
    public static void requireValid(List<Draft> drafts) {
        if (drafts == null || drafts.isEmpty()) {
            throw new BusinessRuleViolationException("trip-stops-invalid", "A trip has at least one stop");
        }
        if (drafts.size() > 50) {
            throw new BusinessRuleViolationException("trip-stops-invalid", "A trip has at most 50 stops");
        }
        UUID previous = null;
        for (Draft draft : drafts) {
            if (draft.kind() == null || draft.terminalId() == null) {
                throw new BusinessRuleViolationException("trip-stops-invalid",
                        "Every stop has a kind and a terminal");
            }
            if (draft.terminalId().equals(previous)) {
                throw new BusinessRuleViolationException("trip-stops-invalid",
                        "A stop cannot follow another at the same terminal; make it one stop");
            }
            previous = draft.terminalId();
        }
    }

    /** Whether an arrival at {@code target} now would be ahead of a stop still waiting. */
    public static boolean arrivesOutOfOrder(List<TripStop> stops, TripStop target) {
        return stops.stream().anyMatch(other -> other.seq() < target.seq() && other.status() == Status.PLANNED);
    }

    public TripStop arrive(Instant at, Source source, boolean outOfOrderArrival) {
        if (status != Status.PLANNED) {
            throw illegal("arrive", "it is " + status);
        }
        return new TripStop(id, tenantId, tripId, seq, kind, terminalId, plannedArrivalAt, Status.ARRIVED, at,
                null, null, null, null, outOfOrderArrival, source, null, version, createdAt, Instant.now());
    }

    /** Leaving. {@code last} makes it the handing over of the trip's last stop: COMPLETED. */
    public TripStop depart(Instant at, Source source, boolean last) {
        if (status != Status.ARRIVED) {
            throw illegal(last ? "complete" : "depart", "it is " + status);
        }
        if (at.isBefore(arrivedAt)) {
            throw new BusinessRuleViolationException("trip-stop-time-invalid",
                    "Stop " + seq + " cannot be left before it was reached at " + arrivedAt);
        }
        return new TripStop(id, tenantId, tripId, seq, kind, terminalId, plannedArrivalAt,
                last ? Status.COMPLETED : Status.DEPARTED, arrivedAt, last ? null : at, last ? at : null, null,
                null, outOfOrder, arrivalSource, source, version, createdAt, Instant.now());
    }

    public TripStop skip(String reason, Instant at) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("Skipping a stop needs a reason");
        }
        if (status != Status.PLANNED) {
            throw illegal("skip", "it is " + status + "; only a stop not yet reached can be skipped");
        }
        return new TripStop(id, tenantId, tripId, seq, kind, terminalId, plannedArrivalAt, Status.SKIPPED, null,
                null, null, at, reason.strip(), false, null, null, version, createdAt, Instant.now());
    }

    /** The same stop at another place in a re-ordered plan. */
    public TripStop withPlan(Draft draft) {
        return new TripStop(id, tenantId, tripId, seq, draft.kind(), draft.terminalId(), draft.plannedArrivalAt(),
                status, arrivedAt, departedAt, completedAt, skippedAt, skipReason, outOfOrder, arrivalSource,
                departureSource, version, createdAt, Instant.now());
    }

    public boolean isPlanned() {
        return status == Status.PLANNED;
    }

    /** Whether the vehicle is at this stop now. */
    public boolean isHere() {
        return status == Status.ARRIVED;
    }

    private BusinessRuleViolationException illegal(String action, String why) {
        return new BusinessRuleViolationException("trip-stop-illegal-transition",
                "Stop " + seq + " (" + kind + ") cannot " + action + ": " + why);
    }
}
