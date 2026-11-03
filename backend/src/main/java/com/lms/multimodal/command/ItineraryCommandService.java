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
import java.time.LocalDate;
import java.time.ZoneOffset;
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
import com.lms.masterdata.api.TerminalCategoryPort;
import com.lms.masterdata.api.TerminalOrgUnitPort;
import com.lms.multimodal.command.domain.Itinerary;
import com.lms.multimodal.command.domain.ItineraryLeg;
import com.lms.multimodal.command.domain.RailTariff;
import com.lms.multimodal.events.ConnectionMissed;
import com.lms.multimodal.events.RailLegCompleted;
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
 * least once. The missed-connection sweep is a third, unguarded for the same
 * reason: it is the platform noticing that a fact failed to happen.
 *
 * <p>Releasing a rail leg publishes {@link RailLegCompleted}, which finance
 * turns into the carrier's freight bill.
 *
 * <p>A rail leg planned without a cost is priced from the rail rate card in
 * force on its planned departure day, and refused when there is none. An
 * explicit cost always wins over the card: it is the planner recording a spot
 * rate or a one-off, and a card that silently replaced it would make the
 * planner's number a suggestion.
 */
@Service
public class ItineraryCommandService {

    static final String SYSTEM_ACTOR = "system";

    /** The functional category a rail leg must start and end at. */
    static final String RAILHEAD = "RAILHEAD";

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
    private final RailTariffRepository railTariffs;
    private final TerminalCategoryPort terminals;
    private final TerminalOrgUnitPort terminalOwners;
    private final PartnerStandingPort partners;
    private final ApplicationEventPublisher events;

    public ItineraryCommandService(ItineraryRepository itineraries, ItineraryLegRepository legs,
                                   TripLookupPort trips, RailTariffRepository railTariffs,
                                   TerminalCategoryPort terminals, TerminalOrgUnitPort terminalOwners,
                                   PartnerStandingPort partners,
                                   ApplicationEventPublisher events) {
        this.itineraries = itineraries;
        this.legs = legs;
        this.trips = trips;
        this.railTariffs = railTariffs;
        this.terminals = terminals;
        this.terminalOwners = terminalOwners;
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
        List<ItineraryLeg> priced = new ArrayList<>();
        for (ItineraryLeg leg : planned) {
            String from = categoryOf(leg.fromTerminalId());
            String to = categoryOf(leg.toTerminalId());
            if (leg.mode() == ItineraryLeg.Mode.RAIL
                    && !(RAILHEAD.equals(from) && RAILHEAD.equals(to))) {
                throw new BusinessRuleViolationException("rail-leg-not-at-railhead",
                        "Leg " + leg.seq() + " is by rail and must run from a railhead to a "
                                + "railhead, not from a " + from + " to a " + to);
            }
            if (!partners.canTransact(leg.carrierPartnerId())) {
                throw new BusinessRuleViolationException("leg-carrier-cannot-transact",
                        "Leg " + leg.seq() + "'s carrier is unknown or not active");
            }
            if (leg.mode() == ItineraryLeg.Mode.ROAD) {
                requirePlannableTrip(tenantId, leg, tripsSeen);
            }
            priced.add(leg.isPriced() ? leg : priceFromCard(tenantId, itinerary.currency(), leg));
        }

        itineraries.save(itinerary);
        priced.forEach(legs::save);
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
        if (saved.status() == ItineraryLeg.Status.RELEASED) {
            announceCompleted(itinerary, saved);
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

    /**
     * One tenant's pass of the missed-connection sweep: marks every leg whose
     * predecessor is overdue and has not arrived, and announces each once.
     *
     * <p>The arrival-time detector cannot see a predecessor that never
     * arrives, because nothing happens for it to react to. Unguarded, like the
     * trip handlers: the actor is the platform. Joins the transaction
     * {@code TenantSweep} opened for this tenant.
     *
     * @return how many legs were marked
     */
    @Transactional
    public int sweepMissedConnections(UUID tenantId, Instant now) {
        Instant at = now.truncatedTo(ChronoUnit.MICROS);
        int marked = 0;
        for (ItineraryLeg candidate : legs.findAwaitingOverduePredecessor(tenantId, at)) {
            List<ItineraryLeg> all = legs.findByItinerary(tenantId, candidate.itineraryId());
            ItineraryLeg leg = all.stream().filter(l -> l.id().equals(candidate.id()))
                    .findFirst().orElse(candidate);
            ItineraryLeg predecessor = predecessorOf(all, leg);
            if (!leg.isOverdueAfter(predecessor, at)) {
                continue;
            }
            // Nothing has arrived, so the soonest this leg can start is a
            // bound: arriving this instant and transferring. Revised to the
            // real figure when the predecessor does turn up.
            Instant earliest = at.plus(Duration.ofMinutes(leg.transferDwellMinutes()));
            ItineraryLeg missed = leg.markConnectionMissed(earliest, at);
            if (missed == leg) {
                continue;
            }
            legs.save(missed);
            Itinerary itinerary = itineraries.findById(leg.itineraryId())
                    .orElseThrow(() -> new ResourceNotFoundException("Itinerary", leg.itineraryId()));
            events.publishEvent(new ConnectionMissed(itinerary.tenantId(), itinerary.id(),
                    itinerary.itineraryNo(), missed.id(), missed.seq(), missed.mode().name(),
                    predecessor.id(), predecessor.tripId(), missed.plannedDepartureAt(), earliest,
                    at));
            marked++;
        }
        return marked;
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
            // Already missed -- the sweep got there first, while nothing had
            // arrived. The miss was announced then; only the earliest start,
            // which the sweep could only bound, is now known for certain.
            ItineraryLeg revised = successor.reviseEarliestStart(earliest, now);
            if (revised != successor) {
                legs.save(revised);
            }
            return;
        }
        legs.save(marked);
        events.publishEvent(new ConnectionMissed(itinerary.tenantId(), itinerary.id(),
                itinerary.itineraryNo(), marked.id(), marked.seq(), marked.mode().name(),
                arrived.id(), arrived.tripId(), marked.plannedDepartureAt(), earliest, now));
    }

    /**
     * Tells finance a rail leg is done and its carrier can be paid. The owning
     * unit is that of the origin railhead, as for a rail rate card.
     */
    private void announceCompleted(Itinerary itinerary, ItineraryLeg released) {
        UUID orgUnitId = terminalOwners.orgUnitOf(released.fromTerminalId())
                .orElseThrow(() -> new ResourceNotFoundException("Terminal",
                        released.fromTerminalId()));
        events.publishEvent(new RailLegCompleted(itinerary.tenantId(), itinerary.id(),
                itinerary.itineraryNo(), released.id(), released.seq(), orgUnitId,
                released.carrierPartnerId(), released.costAmount(), itinerary.currency(),
                released.unitType() == null ? null : released.unitType().name(),
                released.unitCount(), released.bookingRef(), released.trainNo(),
                released.departedAt(), released.releasedAt()));
    }

    private static ItineraryLeg predecessorOf(List<ItineraryLeg> all, ItineraryLeg leg) {
        return all.stream().filter(l -> l.seq() == leg.seq() - 1).findFirst().orElse(null);
    }

    /** The terminal's functional category; refuses one that does not exist. */
    private String categoryOf(UUID terminalId) {
        return terminals.functionalCategoryOf(terminalId)
                .orElseThrow(() -> new ResourceNotFoundException("Terminal", terminalId));
    }

    /**
     * Prices a rail leg planned without a cost from the card in force on its
     * planned departure day, in UTC as every tariff date here is.
     */
    private ItineraryLeg priceFromCard(UUID tenantId, String currency, ItineraryLeg leg) {
        LocalDate day = LocalDate.ofInstant(leg.plannedDepartureAt(), ZoneOffset.UTC);
        RailTariff card = railTariffs.findInForceOn(tenantId, leg.carrierPartnerId(),
                        leg.fromTerminalId(), leg.toTerminalId(), day)
                .orElseThrow(() -> new BusinessRuleViolationException("rail-leg-no-tariff",
                        "Leg " + leg.seq() + " is by rail with no cost given, and its carrier "
                                + "has no rail rate card between these railheads in force on "
                                + day + "; give the leg a cost or publish a card"));
        if (!card.currency().equals(currency)) {
            throw new BusinessRuleViolationException("rail-tariff-currency-mismatch",
                    "Rail rate card " + card.code() + " is in " + card.currency()
                            + " but the itinerary is costed in " + currency);
        }
        return leg.pricedBy(card.id(), card.priceFor(leg.unitType(), leg.unitCount()));
    }

    /**
     * A road leg's trip must exist, be live, not have left yet -- a leg that
     * missed its own dispatch would never hear of it -- run between the leg's
     * terminals, and run no other leg.
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
        // The leg's terminals are where the goods are handed over; a trip
        // that runs anywhere else delivers them somewhere the next leg is not.
        // A trip raised with no destination cannot be shown to go there.
        if (!leg.fromTerminalId().equals(trip.originTerminalId())
                || !leg.toTerminalId().equals(trip.destinationTerminalId())) {
            throw new BusinessRuleViolationException("road-leg-trip-terminals-mismatch",
                    "Trip " + trip.tripNo() + " does not run between leg " + leg.seq()
                            + "'s terminals");
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
