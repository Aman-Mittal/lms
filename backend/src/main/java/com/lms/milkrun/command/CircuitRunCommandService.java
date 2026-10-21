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
package com.lms.milkrun.command;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.lms.execution.api.TripLookupPort;
import com.lms.milkrun.command.domain.CircuitRun;
import com.lms.milkrun.command.domain.CircuitRunStop;
import com.lms.milkrun.events.CircuitRunCompleted;
import com.lms.milkrun.events.CircuitStopLate;
import com.lms.milkrun.events.CircuitStopSkipped;
import com.lms.shared.error.BusinessRuleViolationException;
import com.lms.shared.error.ResourceNotFoundException;
import com.lms.shared.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Runs a circuit: hands it to a trip, records each stop, and closes it at the
 * plant.
 *
 * <p>Stops are normally recorded by telematics -- a geofence entry at a
 * supplier is an arrival, an exit is a departure, an entry at the plant ends
 * the circuit. Every one of those also has a manual twin for the tracker that
 * is not working, and both paths go through the same rules below, so a stop
 * recorded by hand is judged on time or late, or skipped, exactly as a
 * tracked one would be.
 *
 * <p>Two exceptions are detected here and published as events:
 * <ul>
 *   <li><b>Skipped supplier</b> -- the vehicle reached a later stop, or the
 *       plant, while an earlier supplier was still waiting; or somebody said
 *       so.
 *   <li><b>Late window</b> -- the vehicle reached a supplier after its window
 *       had closed.
 * </ul>
 */
@Service
public class CircuitRunCommandService {

    private static final Logger log = LoggerFactory.getLogger(CircuitRunCommandService.class);

    private final CircuitRunRepository runs;
    private final CircuitRunStopRepository stops;
    private final CircuitRunGenerator generator;
    private final TripLookupPort trips;
    private final ApplicationEventPublisher events;

    public CircuitRunCommandService(CircuitRunRepository runs, CircuitRunStopRepository stops,
                                    CircuitRunGenerator generator, TripLookupPort trips,
                                    ApplicationEventPublisher events) {
        this.runs = runs;
        this.stops = stops;
        this.generator = generator;
        this.trips = trips;
        this.events = events;
    }

    /**
     * Plans circuits on demand -- the same idempotent generation the
     * scheduled job runs.
     *
     * @return how many runs were newly planned
     */
    @Transactional
    @PreAuthorize("hasAuthority('MILKRUN_PLAN')")
    public int generate(LocalDate firstDay, int days) {
        return generator.generate(TenantContext.requireTenantId(), firstDay, days);
    }

    /**
     * Hands a planned circuit to an execution trip.
     *
     * <p>The trip is looked up through execution rather than trusted: a
     * foreign key does not consult row-level security, and would happily link
     * a circuit to another tenant's trip.
     */
    @Transactional
    @PreAuthorize("hasAuthority('MILKRUN_EXECUTE')")
    public void assignTrip(UUID runId, UUID tripId) {
        UUID tenantId = TenantContext.requireTenantId();
        CircuitRun run = require(runId);
        if (tripId.equals(run.tripId())) {
            return;
        }
        TripLookupPort.TripSnapshot trip = trips.find(tripId)
                .orElseThrow(() -> new ResourceNotFoundException("Trip", tripId));
        if (trip.isCancelled()) {
            throw new BusinessRuleViolationException("trip-cancelled",
                    "Trip " + trip.tripNo() + " is cancelled and cannot carry a circuit");
        }
        runs.findByTrip(tenantId, tripId).ifPresent(other -> {
            throw new BusinessRuleViolationException("trip-already-carries-a-circuit",
                    "Trip " + trip.tripNo() + " already carries circuit " + other.routeCode()
                            + " on " + other.runDate());
        });
        runs.save(run.carriedBy(tripId, trip.tripNo(), Instant.now()));
    }

    @Transactional
    @PreAuthorize("hasAuthority('MILKRUN_EXECUTE')")
    public void recordArrival(UUID runId, int sequence, Instant at) {
        Circuit circuit = load(requireUnderWay(runId));
        arrive(circuit, circuit.stop(sequence), at, CircuitRun.Source.MANUAL);
    }

    @Transactional
    @PreAuthorize("hasAuthority('MILKRUN_EXECUTE')")
    public void recordDeparture(UUID runId, int sequence, Instant at) {
        Circuit circuit = load(requireUnderWay(runId));
        circuit.replace(stops.save(circuit.stop(sequence).depart(at)));
    }

    /**
     * Records what was collected at a stop.
     *
     * <p>Allowed after the circuit has reached the plant as well: the figure
     * usually comes off the goods-received note, which is written after the
     * vehicle is unloaded.
     */
    @Transactional
    @PreAuthorize("hasAuthority('MILKRUN_EXECUTE')")
    public void recordPickup(UUID runId, int sequence, BigDecimal quantityKg) {
        CircuitRun run = require(runId);
        Circuit circuit = load(run);
        stops.save(circuit.stop(sequence).pickUp(quantityKg));
    }

    @Transactional
    @PreAuthorize("hasAuthority('MILKRUN_EXECUTE')")
    public void skip(UUID runId, int sequence, String reason) {
        Circuit circuit = load(requireUnderWay(runId));
        skipStop(circuit, circuit.stop(sequence), reason, Instant.now());
    }

    /** The plant was reached without a geofence to say so. */
    @Transactional
    @PreAuthorize("hasAuthority('MILKRUN_EXECUTE')")
    public void complete(UUID runId, Instant at) {
        Circuit circuit = load(requireUnderWay(runId));
        finish(circuit, at, CircuitRun.Source.MANUAL);
    }

    /**
     * A geofence crossing on some trip -- possibly one carrying a circuit.
     *
     * <p>Unguarded: the actor is the platform, reacting to telematics. Safe to
     * run twice, because every effect is a state-machine step that a
     * redelivered crossing finds already taken: an entry at a supplier that is
     * no longer PENDING, an exit from one that is no longer ARRIVED, an entry
     * at the plant of a circuit that is no longer IN_PROGRESS -- each changes
     * nothing.
     *
     * <p>Its own transaction: it runs after the ingest transaction has
     * committed, and a circuit that cannot be updated must not undo the
     * positions that were already stored.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void observeCrossing(UUID tripId, UUID terminalId, String eventType, Instant at) {
        UUID tenantId = TenantContext.requireTenantId();
        Optional<CircuitRun> carried = runs.findByTrip(tenantId, tripId);
        if (carried.isEmpty() || !carried.get().isInProgress()) {
            return;
        }
        Circuit circuit = load(carried.get());

        if ("ENTERED".equals(eventType)) {
            Optional<CircuitRunStop> supplier = circuit.stops().stream()
                    .filter(stop -> stop.terminalId().equals(terminalId) && stop.isPending())
                    .findFirst();
            if (supplier.isPresent()) {
                arrive(circuit, supplier.get(), at, CircuitRun.Source.GEOFENCE);
            } else if (terminalId.equals(circuit.run().plantTerminalId())) {
                finish(circuit, at, CircuitRun.Source.GEOFENCE);
            }
        } else if ("EXITED".equals(eventType)) {
            circuit.stops().stream()
                    .filter(stop -> stop.terminalId().equals(terminalId)
                            && stop.status() == CircuitRunStop.Status.ARRIVED
                            && !at.isBefore(stop.arrivedAt()))
                    .findFirst()
                    .ifPresent(stop -> circuit.replace(stops.save(stop.depart(at))));
        }
    }

    // ------------------------------------------------------------- internals

    /**
     * Reaching a supplier. Anything earlier still waiting has been driven
     * past, and is skipped -- before the arrival is recorded, so the events
     * come out in circuit order.
     */
    private void arrive(Circuit circuit, CircuitRunStop target, Instant at,
                        CircuitRun.Source source) {
        for (CircuitRunStop earlier : List.copyOf(circuit.stops())) {
            if (earlier.sequence() < target.sequence() && earlier.isPending()) {
                skipStop(circuit, earlier, "Not visited: the vehicle reached stop "
                        + target.sequence() + " first", at);
            }
        }

        CircuitRunStop arrived = stops.save(target.arrive(at, source));
        circuit.replace(arrived);

        if (arrived.minutesLate() != null && arrived.minutesLate() > 0) {
            CircuitRun run = circuit.run();
            log.info("Circuit {} on {} reached stop {} {} minutes after its window closed",
                    run.routeCode(), run.runDate(), arrived.sequence(), arrived.minutesLate());
            events.publishEvent(new CircuitStopLate(run.tenantId(), run.id(), run.routeCode(),
                    run.runDate(), run.tripId(), arrived.sequence(), arrived.terminalId(),
                    arrived.windowClosesAt(), at, arrived.minutesLate()));
        }
    }

    private void skipStop(Circuit circuit, CircuitRunStop stop, String reason, Instant at) {
        CircuitRunStop skipped = stops.save(stop.skip(reason));
        circuit.replace(skipped);
        CircuitRun run = circuit.run();
        events.publishEvent(new CircuitStopSkipped(run.tenantId(), run.id(), run.routeCode(),
                run.runDate(), run.tripId(), skipped.sequence(), skipped.terminalId(),
                skipped.skipReason(), at));
    }

    /** Reaching the plant: every supplier still waiting has been missed. */
    private void finish(Circuit circuit, Instant at, CircuitRun.Source source) {
        for (CircuitRunStop stop : List.copyOf(circuit.stops())) {
            if (stop.isPending()) {
                skipStop(circuit, stop, "Not visited: the vehicle reached the plant first", at);
            }
        }

        int visited = (int) circuit.stops().stream().filter(CircuitRunStop::wasVisited).count();
        int skipped = circuit.stops().size() - visited;
        BigDecimal pickedUp = circuit.stops().stream()
                .map(CircuitRunStop::pickedUpKg)
                .filter(quantity -> quantity != null)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        CircuitRun done = runs.save(circuit.run().complete(visited, skipped, source, at));
        // Read at completion rather than when the trip was assigned: the vendor
        // is whoever carried the circuit, and a trip can be re-awarded between
        // being handed the circuit and finishing it.
        Optional<TripLookupPort.TripSnapshot> trip = trips.find(done.tripId());
        events.publishEvent(new CircuitRunCompleted(done.tenantId(), done.id(), done.routeCode(),
                done.runDate(), done.tripId(), visited, skipped, pickedUp, done.runRate(),
                done.perStopFee(), done.chargeAmount(), done.currency(), at, done.tripNo(),
                trip.map(TripLookupPort.TripSnapshot::loadId).orElse(null),
                trip.map(TripLookupPort.TripSnapshot::vendorPartnerId).orElse(null)));
    }

    private CircuitRun require(UUID runId) {
        return runs.findById(runId)
                .orElseThrow(() -> new ResourceNotFoundException("Circuit run", runId));
    }

    private CircuitRun requireUnderWay(UUID runId) {
        CircuitRun run = require(runId);
        if (!run.isInProgress()) {
            throw new BusinessRuleViolationException("circuit-run-not-in-progress",
                    "Circuit " + run.routeCode() + " on " + run.runDate() + " is " + run.status()
                            + "; stops are recorded only while it is under way");
        }
        return run;
    }

    private Circuit load(CircuitRun run) {
        return new Circuit(run, new ArrayList<>(stops.findForRun(run.tenantId(), run.id())));
    }

    /**
     * A run and its stops, kept current as stops are saved.
     *
     * <p>Spring Data JDBC bumps the version on every save; holding on to the
     * pre-save copy and saving it again would fail the optimistic lock. This
     * keeps the latest copy of each stop in hand.
     */
    private record Circuit(CircuitRun run, List<CircuitRunStop> stops) {

        CircuitRunStop stop(int sequence) {
            return stops.stream()
                    .filter(stop -> stop.sequence() == sequence)
                    .findFirst()
                    .orElseThrow(() -> new ResourceNotFoundException("Circuit " + run.routeCode()
                            + " on " + run.runDate() + " has no stop " + sequence));
        }

        void replace(CircuitRunStop saved) {
            stops.replaceAll(stop -> stop.id().equals(saved.id()) ? saved : stop);
        }
    }
}
