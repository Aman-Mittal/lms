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
package com.lms.multimodal.command;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.lms.execution.api.TripLookupPort;
import com.lms.masterdata.api.PartnerStandingPort;
import com.lms.masterdata.api.TerminalGeofencePort;
import com.lms.multimodal.command.domain.Itinerary;
import com.lms.multimodal.command.domain.ItineraryLeg;
import com.lms.multimodal.events.ConnectionMissed;
import com.lms.shared.error.BusinessRuleViolationException;
import com.lms.shared.error.ResourceNotFoundException;
import com.lms.shared.tenant.TenantContext;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Plans itineraries and moves their legs (vision document 3.4).
 *
 * <p>Two ways in. A person plans an itinerary and reports rail terminal
 * events; both are guarded. Execution's trip events move road legs; those
 * paths are unguarded, because the actor is the platform reacting to a fact
 * that has already happened, and idempotent, because the outbox delivers at
 * least once.
 */
@Service
public class ItineraryCommandService {

    static final String SYSTEM_ACTOR = "system";

    /** Statuses in which a trip has already left, and a leg would miss it. */
    private static final Set<String> TRIP_ALREADY_MOVING =
            Set.of("DISPATCHED", "IN_TRANSIT", "AT_DESTINATION", "COMPLETED");

    /**
     * How far ahead of the server's clock a reported event may be. A yard
     * clerk's handset drifts; a report from tomorrow is a typo.
     */
    private static final Duration CLOCK_SKEW = Duration.ofMinutes(5);

    private final ItineraryRepository itineraries;
    private final ItineraryLegRepository legs;
    private final TripLookupPort trips;
    private final TerminalGeofencePort terminals;
    private final PartnerStandingPort partners;
    private final ApplicationEventPublisher events;

    public ItineraryCommandService(ItineraryRepository itineraries, ItineraryLegRepository legs,
                                   TripLookupPort trips, TerminalGeofencePort terminals,
                                   PartnerStandingPort partners, ApplicationEventPublisher events) {
        this.itineraries = itineraries;
        this.legs = legs;
        this.trips = trips;
        this.terminals = terminals;
        this.partners = partners;
        this.events = events;
    }

    /** Plans an itinerary of ordered legs. */
    @Transactional
    @PreAuthorize("hasAuthority('MULTIMODAL_PLAN')"
            + " and @abac.permits('MULTIMODAL_PLAN', 'TERMINAL',"
            + " (#command == null or #command.legs() == null or #command.legs().isEmpty())"
            + " ? null : #command.legs()[0].fromTerminalId())")
    public UUID plan(PlanItineraryCommand command) {
        UUID tenantId = TenantContext.requireTenantId();
        Instant now = Instant.now();
        UUID itineraryId = UUID.randomUUID();
        List<ItineraryLeg.Plan> plans = command.legs() == null ? List.of() : command.legs();

        List<ItineraryLeg> planned = new ArrayList<>();
        for (int i = 0; i < plans.size(); i++) {
            planned.add(ItineraryLeg.plan(UUID.randomUUID(), tenantId, itineraryId, i + 1,
                    truncate(plans.get(i)), now));
        }
        Itinerary itinerary = Itinerary.plan(itineraryId, tenantId, newItineraryNo(),
                command.shipmentRef(), command.currency(), planned, currentActor(), now);

        Set<UUID> tripsSeen = new HashSet<>();
        for (ItineraryLeg leg : planned) {
            requireTerminal(leg.fromTerminalId());
            requireTerminal(leg.toTerminalId());
            if (!partners.canTransact(leg.carrierPartnerId())) {
                throw new BusinessRuleViolationException("leg-carrier-cannot-transact",
                        "Leg " + leg.seq() + "'s carrier is unknown or not active");
            }
            if (leg.mode() == ItineraryLeg.Mode.ROAD) {
                requirePlannableTrip(tenantId, leg, tripsSeen);
            }
        }

        itineraries.save(itinerary);
        planned.forEach(legs::save);
        return itineraryId;
    }

    /**
     * A rail terminal event: placed, loaded, departed, arrived or released.
     *
     * @param at when it happened; now if not given
     */
    @Transactional
    @PreAuthorize("hasAuthority('MULTIMODAL_TRACK')")
    public void recordRailMilestone(UUID itineraryId, int seq, ItineraryLeg.Status milestone,
                                    Instant at) {
        UUID tenantId = TenantContext.requireTenantId();
        Instant now = Instant.now();
        Instant when = at == null ? now : at.truncatedTo(ChronoUnit.MICROS);
        if (when.isAfter(now.plus(CLOCK_SKEW))) {
            throw new BusinessRuleViolationException("milestone-in-future",
                    "A terminal event at " + when + " has not happened yet");
        }

        Itinerary itinerary = itineraries.findById(itineraryId)
                .orElseThrow(() -> new ResourceNotFoundException("Itinerary", itineraryId));
        List<ItineraryLeg> all = legs.findByItinerary(tenantId, itineraryId);
        ItineraryLeg leg = all.stream().filter(l -> l.seq() == seq).findFirst()
                .orElseThrow(() -> new BusinessRuleViolationException("leg-not-found",
                        "Itinerary " + itinerary.itineraryNo() + " has no leg " + seq));

        ItineraryLeg moved = leg.recordMilestone(milestone, when, predecessorOf(all, leg));
        if (moved == leg) {
            return;
        }
        ItineraryLeg saved = legs.save(moved);
        if (saved.status() == ItineraryLeg.Status.ARRIVED) {
            checkConnection(itinerary, all, saved, now);
        }
    }

    /** A road leg's trip has left. Idempotent; never refuses. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void tripDispatched(UUID tripId, Instant dispatchedAt) {
        UUID tenantId = TenantContext.requireTenantId();
        legs.findByTrip(tenantId, tripId).ifPresent(leg -> {
            List<ItineraryLeg> all = legs.findByItinerary(tenantId, leg.itineraryId());
            ItineraryLeg moved = leg.tripDispatched(dispatchedAt, predecessorOf(all, leg));
            if (moved != leg) {
                legs.save(moved);
            }
        });
    }

    /**
     * A road leg's trip has delivered. Idempotent; and the arrival is what
     * may make the next leg miss its connection.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void tripCompleted(UUID tripId, Instant dispatchedAt, Instant completedAt) {
        UUID tenantId = TenantContext.requireTenantId();
        Optional<ItineraryLeg> found = legs.findByTrip(tenantId, tripId);
        if (found.isEmpty()) {
            return;
        }
        ItineraryLeg leg = found.get();
        List<ItineraryLeg> all = legs.findByItinerary(tenantId, leg.itineraryId());
        ItineraryLeg moved = leg.tripCompleted(dispatchedAt, completedAt, predecessorOf(all, leg));
        if (moved == leg) {
            return;
        }
        ItineraryLeg saved = legs.save(moved);
        Itinerary itinerary = itineraries.findById(leg.itineraryId())
                .orElseThrow(() -> new ResourceNotFoundException("Itinerary", leg.itineraryId()));
        checkConnection(itinerary, all, saved, Instant.now());
    }

    // ------------------------------------------------------------- internals

    /**
     * The missed-connection detector: runs when a leg arrives, and asks
     * whether the next leg can still start when it was planned to.
     */
    private void checkConnection(Itinerary itinerary, List<ItineraryLeg> all,
                                 ItineraryLeg arrived, Instant now) {
        Optional<ItineraryLeg> next = all.stream()
                .filter(l -> l.seq() == arrived.seq() + 1)
                .findFirst();
        if (next.isEmpty() || !next.get().missesConnectionAfter(arrived)) {
            return;
        }
        ItineraryLeg successor = next.get();
        Instant earliest = successor.earliestStartAfter(arrived);
        ItineraryLeg marked = successor.markConnectionMissed(earliest, now);
        if (marked == successor) {
            return;
        }
        legs.save(marked);
        events.publishEvent(new ConnectionMissed(itinerary.tenantId(), itinerary.id(),
                itinerary.itineraryNo(), marked.id(), marked.seq(), marked.mode().name(),
                arrived.id(), arrived.tripId(), marked.plannedDepartureAt(), earliest, now));
    }

    private static ItineraryLeg predecessorOf(List<ItineraryLeg> all, ItineraryLeg leg) {
        return all.stream().filter(l -> l.seq() == leg.seq() - 1).findFirst().orElse(null);
    }

    private void requireTerminal(UUID terminalId) {
        if (terminals.centreOf(terminalId).isEmpty()) {
            throw new ResourceNotFoundException("Terminal", terminalId);
        }
    }

    /**
     * A road leg's trip must exist, be live, not have left yet -- a leg that
     * missed its own dispatch would never hear of it -- and run no other leg.
     */
    private void requirePlannableTrip(UUID tenantId, ItineraryLeg leg, Set<UUID> tripsSeen) {
        TripLookupPort.TripSnapshot trip = trips.find(leg.tripId())
                .orElseThrow(() -> new ResourceNotFoundException("Trip", leg.tripId()));
        if (trip.isCancelled()) {
            throw new BusinessRuleViolationException("road-leg-trip-cancelled",
                    "Trip " + trip.tripNo() + " is cancelled and cannot run leg " + leg.seq());
        }
        if (TRIP_ALREADY_MOVING.contains(trip.status())) {
            throw new BusinessRuleViolationException("road-leg-trip-already-moving",
                    "Trip " + trip.tripNo() + " has already been dispatched; plan the leg "
                            + "before the trip leaves");
        }
        if (!tripsSeen.add(trip.tripId()) || legs.findByTrip(tenantId, trip.tripId()).isPresent()) {
            throw new BusinessRuleViolationException("trip-already-on-itinerary",
                    "Trip " + trip.tripNo() + " already runs a leg of an itinerary");
        }
    }

    /** Postgres keeps microseconds; a retry must compare equal to what was stored. */
    private static ItineraryLeg.Plan truncate(ItineraryLeg.Plan plan) {
        return new ItineraryLeg.Plan(plan.mode(), plan.fromTerminalId(), plan.toTerminalId(),
                micros(plan.plannedDepartureAt()), micros(plan.plannedArrivalAt()),
                plan.transferDwellMinutes(), plan.tripId(), plan.unitType(), plan.unitCount(),
                plan.bookingRef(), plan.trainNo(), plan.carrierPartnerId(), plan.costAmount());
    }

    private static Instant micros(Instant at) {
        return at == null ? null : at.truncatedTo(ChronoUnit.MICROS);
    }

    private static String newItineraryNo() {
        return "MM-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
    }

    private static String currentActor() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication == null || authentication.getName() == null
                ? SYSTEM_ACTOR
                : authentication.getName();
    }
}
