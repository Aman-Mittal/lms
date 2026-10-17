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
package com.lms.multimodal.command.domain;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;

import com.lms.shared.error.BusinessRuleViolationException;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.relational.core.mapping.Table;

/**
 * One leg of an itinerary: a road trip, or a rail booking.
 *
 * <p>Immutable, like the other aggregates here: every transition returns the
 * next state and the service saves it. A transition that changes nothing --
 * the same milestone reported again with the same time, a trip event
 * delivered twice -- returns {@code this}, which is how the service knows not
 * to write or publish anything.
 */
@Table("itinerary_leg")
public record ItineraryLeg(
        @Id UUID id,
        UUID tenantId,
        UUID itineraryId,
        int seq,
        Mode mode,
        Status status,
        UUID fromTerminalId,
        UUID toTerminalId,
        Instant plannedDepartureAt,
        Instant plannedArrivalAt,
        int transferDwellMinutes,
        UUID tripId,
        UnitType unitType,
        Integer unitCount,
        String bookingRef,
        String trainNo,
        UUID carrierPartnerId,
        BigDecimal costAmount,
        Instant placedAt,
        Instant loadedAt,
        Instant departedAt,
        Instant arrivedAt,
        Instant releasedAt,
        boolean startedEarly,
        Instant connectionMissedAt,
        Instant earliestStartAt,
        @Version Long version,
        Instant createdAt,
        Instant updatedAt) {

    public enum Mode {
        ROAD,
        RAIL
    }

    /** What a rail booking reserves. */
    public enum UnitType {
        /** A rake, or part of one, counted in wagons. */
        WAGON,
        /** Containers on flat wagons. */
        CONTAINER
    }

    /**
     * Terminal milestones, in order. A road leg uses only PLANNED, DEPARTED
     * and ARRIVED; the rest are rail's.
     */
    public enum Status {
        PLANNED,
        /** The rake is placed at the loading railhead. */
        PLACED,
        /** Loading finished. */
        LOADED,
        /** The train has left. This is when a rail leg starts. */
        DEPARTED,
        /** At the unloading railhead. This is when the goods may move on. */
        ARRIVED,
        /** Unloaded and released to whoever carries the next leg. */
        RELEASED
    }

    /**
     * The rail transition table. One step at a time: a railway reports every
     * milestone, and a booking that jumps from PLACED to ARRIVED has lost
     * a report somebody will later need -- the departure time is what a
     * demurrage dispute is argued over.
     */
    private static final Map<Status, Status> RAIL_NEXT = new EnumMap<>(Status.class);

    static {
        RAIL_NEXT.put(Status.PLANNED, Status.PLACED);
        RAIL_NEXT.put(Status.PLACED, Status.LOADED);
        RAIL_NEXT.put(Status.LOADED, Status.DEPARTED);
        RAIL_NEXT.put(Status.DEPARTED, Status.ARRIVED);
        RAIL_NEXT.put(Status.ARRIVED, Status.RELEASED);
    }

    /** What a caller supplies for one leg. */
    public record Plan(
            Mode mode,
            UUID fromTerminalId,
            UUID toTerminalId,
            Instant plannedDepartureAt,
            Instant plannedArrivalAt,
            Integer transferDwellMinutes,
            UUID tripId,
            UnitType unitType,
            Integer unitCount,
            String bookingRef,
            String trainNo,
            UUID carrierPartnerId,
            BigDecimal costAmount) {
    }

    /** Checks one leg's own shape; how legs fit together is the itinerary's. */
    public static ItineraryLeg plan(UUID id, UUID tenantId, UUID itineraryId, int seq, Plan plan,
                                    Instant at) {
        String leg = "Leg " + seq;
        if (plan.mode() == null) {
            throw new BusinessRuleViolationException("leg-mode-required", leg + " needs a mode");
        }
        if (plan.fromTerminalId() == null || plan.toTerminalId() == null) {
            throw new BusinessRuleViolationException("leg-terminals-required",
                    leg + " needs a terminal at each end");
        }
        if (plan.fromTerminalId().equals(plan.toTerminalId())) {
            throw new BusinessRuleViolationException("leg-goes-nowhere",
                    leg + " starts and ends at the same terminal");
        }
        if (plan.plannedDepartureAt() == null || plan.plannedArrivalAt() == null
                || !plan.plannedArrivalAt().isAfter(plan.plannedDepartureAt())) {
            throw new BusinessRuleViolationException("leg-window-invalid",
                    leg + " needs a planned departure before its planned arrival");
        }
        int dwell = plan.transferDwellMinutes() == null ? 0 : plan.transferDwellMinutes();
        if (dwell < 0) {
            throw new BusinessRuleViolationException("leg-dwell-invalid",
                    leg + " cannot have a negative transfer dwell");
        }
        if (plan.carrierPartnerId() == null) {
            throw new BusinessRuleViolationException("leg-carrier-required",
                    leg + " needs the carrier who will be paid for it");
        }
        if (plan.costAmount() == null || plan.costAmount().signum() < 0) {
            throw new BusinessRuleViolationException("leg-cost-invalid",
                    leg + " needs a cost of zero or more");
        }

        if (plan.mode() == Mode.ROAD) {
            if (plan.tripId() == null) {
                throw new BusinessRuleViolationException("road-leg-needs-trip",
                        leg + " is by road and needs the trip that runs it");
            }
            return new ItineraryLeg(id, tenantId, itineraryId, seq, Mode.ROAD, Status.PLANNED,
                    plan.fromTerminalId(), plan.toTerminalId(), plan.plannedDepartureAt(),
                    plan.plannedArrivalAt(), dwell, plan.tripId(), null, null, null, null,
                    plan.carrierPartnerId(), plan.costAmount(), null, null, null, null, null,
                    false, null, null, null, at, at);
        }

        if (plan.tripId() != null) {
            throw new BusinessRuleViolationException("rail-leg-has-no-trip",
                    leg + " is by rail; a trip is a road movement");
        }
        if (plan.unitType() == null || plan.unitCount() == null || plan.unitCount() < 1) {
            throw new BusinessRuleViolationException("rail-leg-needs-capacity",
                    leg + " is by rail and needs a wagon or container count");
        }
        if (plan.bookingRef() == null || plan.bookingRef().isBlank()) {
            throw new BusinessRuleViolationException("rail-leg-needs-booking",
                    leg + " is by rail and needs the railway's booking reference");
        }
        return new ItineraryLeg(id, tenantId, itineraryId, seq, Mode.RAIL, Status.PLANNED,
                plan.fromTerminalId(), plan.toTerminalId(), plan.plannedDepartureAt(),
                plan.plannedArrivalAt(), dwell, null, plan.unitType(), plan.unitCount(),
                plan.bookingRef().strip(), blankToNull(plan.trainNo()),
                plan.carrierPartnerId(), plan.costAmount(), null, null, null, null, null,
                false, null, null, null, at, at);
    }

    // ----------------------------------------------------------------- facts

    public boolean hasStarted() {
        return departedAt != null;
    }

    public boolean hasArrived() {
        return arrivedAt != null;
    }

    /**
     * The soonest this leg may start, given its predecessor; null while the
     * predecessor has not arrived.
     */
    public Instant earliestStartAfter(ItineraryLeg predecessor) {
        if (predecessor == null || !predecessor.hasArrived()) {
            return null;
        }
        return predecessor.arrivedAt().plus(Duration.ofMinutes(transferDwellMinutes));
    }

    // ------------------------------------------------------------ rail moves

    /**
     * Records a rail terminal event.
     *
     * <p>Departure is where the hand-off rule bites: the train may not leave
     * before the predecessor's arrival plus the transfer dwell, because the
     * goods it is meant to carry are not on it.
     *
     * @param predecessor the previous leg, or null for the first
     */
    public ItineraryLeg recordMilestone(Status milestone, Instant at, ItineraryLeg predecessor) {
        if (mode != Mode.RAIL) {
            throw new BusinessRuleViolationException("leg-not-rail",
                    "Leg " + seq + " is by road; it moves with its trip, not by terminal events");
        }
        if (milestone == null || milestone == Status.PLANNED) {
            throw new BusinessRuleViolationException("milestone-invalid",
                    "A terminal event is one of PLACED, LOADED, DEPARTED, ARRIVED, RELEASED");
        }
        if (milestone == status && at.equals(timeOf(milestone))) {
            // The same report again -- a retry that never learned the first
            // outcome. Already true; nothing to do.
            return this;
        }
        if (RAIL_NEXT.get(status) != milestone) {
            throw new BusinessRuleViolationException("leg-illegal-transition",
                    "Leg " + seq + " cannot move from " + status + " to " + milestone);
        }
        Instant previous = timeOf(status);
        if (previous != null && at.isBefore(previous)) {
            throw new BusinessRuleViolationException("milestone-out-of-order",
                    "Leg " + seq + " was " + status + " at " + previous + "; it cannot be "
                            + milestone + " earlier, at " + at);
        }
        if (milestone == Status.DEPARTED) {
            requireHandOff(predecessor, at);
        }

        return switch (milestone) {
            case PLACED -> with(Status.PLACED, at, loadedAt, departedAt, arrivedAt, releasedAt,
                    startedEarly, at);
            case LOADED -> with(Status.LOADED, placedAt, at, departedAt, arrivedAt, releasedAt,
                    startedEarly, at);
            case DEPARTED -> with(Status.DEPARTED, placedAt, loadedAt, at, arrivedAt, releasedAt,
                    startedEarly, at);
            case ARRIVED -> with(Status.ARRIVED, placedAt, loadedAt, departedAt, at, releasedAt,
                    startedEarly, at);
            case RELEASED -> with(Status.RELEASED, placedAt, loadedAt, departedAt, arrivedAt, at,
                    startedEarly, at);
            case PLANNED -> throw new IllegalStateException("unreachable");
        };
    }

    // ------------------------------------------------------------ road moves

    /**
     * The trip was dispatched.
     *
     * <p>Not refusable: dispatch is execution's gate and has already happened
     * by the time this hears of it. If it broke the hand-off rule the leg says
     * so, rather than pretending it did not start.
     */
    public ItineraryLeg tripDispatched(Instant at, ItineraryLeg predecessor) {
        if (mode != Mode.ROAD || status != Status.PLANNED) {
            return this;
        }
        return with(Status.DEPARTED, null, null, at, null, null,
                breaksHandOff(predecessor, at), at);
    }

    /**
     * The trip delivered.
     *
     * <p>Carries the dispatch time as well, so a completion delivered before
     * the dispatch -- which the outbox permits after a restart -- still leaves
     * a whole leg behind.
     */
    public ItineraryLeg tripCompleted(Instant dispatchedAt, Instant completedAt,
                                      ItineraryLeg predecessor) {
        if (mode != Mode.ROAD || status == Status.ARRIVED) {
            return this;
        }
        Instant departed = departedAt != null ? departedAt
                : dispatchedAt != null ? dispatchedAt : completedAt;
        boolean early = departedAt != null ? startedEarly : breaksHandOff(predecessor, departed);
        return with(Status.ARRIVED, null, null, departed, completedAt, null, early, completedAt);
    }

    // ------------------------------------------------------- the connection

    /**
     * Would the predecessor's actual arrival make this leg miss its planned
     * start?
     */
    public boolean missesConnectionAfter(ItineraryLeg predecessor) {
        Instant earliest = earliestStartAfter(predecessor);
        return earliest != null && earliest.isAfter(plannedDepartureAt);
    }

    /**
     * Marks the connection missed. Once: a second arrival report of the same
     * predecessor finds it already marked and returns {@code this}, which is
     * what keeps the event from being published twice.
     */
    public ItineraryLeg markConnectionMissed(Instant earliestStart, Instant at) {
        if (connectionMissedAt != null || hasStarted()) {
            return this;
        }
        return new ItineraryLeg(id, tenantId, itineraryId, seq, mode, status, fromTerminalId,
                toTerminalId, plannedDepartureAt, plannedArrivalAt, transferDwellMinutes, tripId,
                unitType, unitCount, bookingRef, trainNo, carrierPartnerId, costAmount, placedAt,
                loadedAt, departedAt, arrivedAt, releasedAt, startedEarly, at, earliestStart,
                version, createdAt, at);
    }

    // -------------------------------------------------------------- internals

    private void requireHandOff(ItineraryLeg predecessor, Instant at) {
        if (predecessor == null) {
            return;
        }
        if (!predecessor.hasArrived()) {
            throw new BusinessRuleViolationException("handoff-predecessor-not-arrived",
                    "Leg " + seq + " cannot depart: leg " + predecessor.seq()
                            + " has not arrived, so the goods are not here");
        }
        Instant earliest = earliestStartAfter(predecessor);
        if (at.isBefore(earliest)) {
            throw new BusinessRuleViolationException("handoff-too-early",
                    "Leg " + seq + " cannot depart at " + at + ": leg " + predecessor.seq()
                            + " arrived at " + predecessor.arrivedAt() + " and the transfer takes "
                            + transferDwellMinutes + " minutes, so the earliest is " + earliest);
        }
    }

    private boolean breaksHandOff(ItineraryLeg predecessor, Instant at) {
        if (predecessor == null) {
            return false;
        }
        Instant earliest = earliestStartAfter(predecessor);
        return earliest == null || at.isBefore(earliest);
    }

    private Instant timeOf(Status milestone) {
        return switch (milestone) {
            case PLANNED -> null;
            case PLACED -> placedAt;
            case LOADED -> loadedAt;
            case DEPARTED -> departedAt;
            case ARRIVED -> arrivedAt;
            case RELEASED -> releasedAt;
        };
    }

    private ItineraryLeg with(Status next, Instant placed, Instant loaded, Instant departed,
                              Instant arrived, Instant released, boolean early, Instant at) {
        return new ItineraryLeg(id, tenantId, itineraryId, seq, mode, next, fromTerminalId,
                toTerminalId, plannedDepartureAt, plannedArrivalAt, transferDwellMinutes, tripId,
                unitType, unitCount, bookingRef, trainNo, carrierPartnerId, costAmount, placed,
                loaded, departed, arrived, released, early, connectionMissedAt, earliestStartAt,
                version, createdAt, at);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
