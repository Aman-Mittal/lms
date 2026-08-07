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

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.lms.execution.command.domain.GateEvent;
import com.lms.execution.command.domain.Trip;
import com.lms.execution.command.domain.TripDocument;
import com.lms.execution.command.domain.WeighbridgeReading;
import com.lms.execution.events.TripCompleted;
import com.lms.execution.events.TripDispatched;
import com.lms.masterdata.api.FleetCompliancePort;
import com.lms.planning.api.LoadLifecyclePort;
import com.lms.shared.error.BusinessRuleViolationException;
import com.lms.shared.error.ResourceNotFoundException;
import com.lms.shared.tenant.TenantContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Write side for trip execution (vision document 3.6).
 *
 * <p>Most of this class is bookkeeping around one method. {@link #dispatch} is
 * the point where every rule the platform has been accumulating becomes a
 * physical fact -- after it, a lorry is on a road, and nothing downstream can
 * undo that.
 */
@Service
public class TripCommandService {

    /**
     * Documents that must be aboard before any trip may leave.
     *
     * <p>Deliberately a small, fixed set rather than configuration. Making it
     * configurable would make "may this vehicle leave" depend on a row somebody
     * can edit, and the first time a tenant empties the list the check becomes
     * decorative. A per-tenant policy belongs here as a considered feature, not
     * as a default that can be switched off by accident.
     */
    private static final Set<String> MANDATORY_DOCUMENTS = Set.of("CONSIGNMENT_NOTE", "EWAY_BILL");

    private final TripRepository trips;
    private final TripDocumentRepository documents;
    private final GateEventRepository gateEvents;
    private final WeighbridgeReadingRepository weighings;
    private final LoadLifecyclePort loads;
    private final FleetCompliancePort compliance;
    private final org.springframework.context.ApplicationEventPublisher events;

    /**
     * How far the weighed payload may differ from the planned weight before
     * dispatch is refused, as a percentage.
     *
     * <p>Not zero. Weighbridges have a tolerance, pallets absorb moisture, and
     * a system that refused every load half a percent off its manifest would be
     * switched off within a week.
     */
    private final BigDecimal weightTolerancePct;

    public TripCommandService(TripRepository trips, TripDocumentRepository documents,
                              GateEventRepository gateEvents,
                              WeighbridgeReadingRepository weighings,
                              LoadLifecyclePort loads, FleetCompliancePort compliance,
                              org.springframework.context.ApplicationEventPublisher events,
                              @Value("${lms.execution.weight-tolerance-pct:5}")
                              BigDecimal weightTolerancePct) {
        this.trips = trips;
        this.documents = documents;
        this.gateEvents = gateEvents;
        this.weighings = weighings;
        this.loads = loads;
        this.compliance = compliance;
        this.events = events;
        this.weightTolerancePct = weightTolerancePct;
    }

    /** Raises a trip against an awarded load. */
    @Transactional
    public UUID raiseTrip(String tripNo, UUID loadId, Instant plannedStartAt) {
        UUID tenantId = TenantContext.requireTenantId();

        LoadLifecyclePort.LoadSummary load = loads.summarise(loadId)
                .orElseThrow(() -> new ResourceNotFoundException("Load", loadId));

        if (!"AWARDED".equals(load.status())) {
            throw new BusinessRuleViolationException("load-not-awarded",
                    "Load " + load.loadNo() + " is " + load.status()
                            + "; a trip can only be raised against an awarded load");
        }

        trips.findByLoad(tenantId, loadId).ifPresent(existing -> {
            throw new BusinessRuleViolationException("load-already-has-trip",
                    "Load " + load.loadNo() + " already has trip " + existing.tripNo());
        });

        trips.findByTripNo(tenantId, tripNo).ifPresent(existing -> {
            throw new BusinessRuleViolationException("trip-no-taken",
                    "A trip numbered " + tripNo + " already exists");
        });

        return trips.save(Trip.raise(UUID.randomUUID(), tenantId, load.orgUnitId(), loadId,
                tripNo, load.awardedVendorPartnerId(), load.originTerminalId(),
                load.destinationTerminalId(), plannedStartAt)).id();
    }

    /**
     * Nominates the vehicle and driver.
     *
     * <p>Compliance is checked here as well as at dispatch, and the difference
     * matters: this one warns nobody and blocks nothing later. Assigning a
     * lorry whose insurance lapses next Tuesday to a trip departing next
     * Wednesday should fail now, while there is time to find another, not at
     * the gate with the driver waiting.
     */
    @Transactional
    public void assign(UUID tripId, UUID vehicleId, UUID driverId) {
        Trip trip = require(tripId);

        LocalDate departsOn = trip.plannedStartAt() == null
                ? LocalDate.now(ZoneOffset.UTC)
                : trip.plannedStartAt().atZone(ZoneOffset.UTC).toLocalDate();

        FleetCompliancePort.ComplianceVerdict verdict =
                compliance.checkDispatchReadiness(vehicleId, driverId, departsOn);

        if (!verdict.dispatchable()) {
            throw new BusinessRuleViolationException("assignment-not-compliant",
                    "Cannot assign this vehicle and driver to trip " + trip.tripNo()
                            + ": " + verdict.summary());
        }

        trips.save(trip.assign(vehicleId, driverId));
    }

    @Transactional
    public UUID attachDocument(UUID tripId, String documentType, String documentRef) {
        UUID tenantId = TenantContext.requireTenantId();
        Trip trip = require(tripId);

        if (trip.isOnRoad() || trip.status() == Trip.TripStatus.COMPLETED) {
            throw new BusinessRuleViolationException("trip-already-dispatched",
                    "Trip " + trip.tripNo() + " has already left; documents cannot be "
                            + "added retrospectively");
        }

        return documents.save(TripDocument.attach(UUID.randomUUID(), tenantId, tripId,
                documentType, documentRef)).id();
    }

    // ------------------------------------------------------------ 3.6.2 gate

    @Transactional
    public void gateIn(UUID tripId, UUID terminalId, Instant at, String remarks) {
        UUID tenantId = TenantContext.requireTenantId();
        Trip trip = require(tripId);
        Instant when = at == null ? Instant.now() : at;

        gateEvents.save(GateEvent.record(UUID.randomUUID(), tenantId, tripId, terminalId,
                GateEvent.EventType.GATE_IN, when, remarks));
        trips.save(trip.gateIn(when));
    }

    /**
     * Records a weighbridge ticket.
     *
     * <p>The tare must come before the gross, because the tare is the empty
     * vehicle. Taking them the other way round means the "tare" was measured
     * with freight aboard, and the payload computed from it is quietly wrong
     * rather than obviously wrong.
     */
    @Transactional
    public void recordWeighing(UUID tripId, UUID terminalId,
                               WeighbridgeReading.ReadingType type,
                               BigDecimal weightKg, Instant at) {
        UUID tenantId = TenantContext.requireTenantId();
        Trip trip = require(tripId);

        if (type == WeighbridgeReading.ReadingType.GROSS && trip.tareWeightKg() == null) {
            throw new BusinessRuleViolationException("tare-not-recorded",
                    "Trip " + trip.tripNo() + " has no tare reading; a gross weight on its own "
                            + "says nothing about the payload");
        }

        weighings.save(WeighbridgeReading.of(UUID.randomUUID(), tenantId, tripId, terminalId,
                type, weightKg, at));

        Trip updated = type == WeighbridgeReading.ReadingType.TARE
                ? trip.recordWeighing(weightKg, null)
                : trip.recordWeighing(null, weightKg);

        trips.save(updated);
    }

    /** Loading is finished and the vehicle has been weighed laden. */
    @Transactional
    public void markLoaded(UUID tripId, Instant at) {
        Trip trip = require(tripId);

        if (!trip.hasBothWeighings()) {
            throw new BusinessRuleViolationException("weighing-incomplete",
                    "Trip " + trip.tripNo() + " cannot be marked loaded without both a tare "
                            + "and a gross weighbridge reading");
        }

        trips.save(trip.markLoaded(at == null ? Instant.now() : at));
    }

    // ------------------------------------------------------- 3.6.3 dispatch

    /**
     * The compliance gate.
     *
     * <p>Four refusals, checked together and reported together so that a
     * dispatcher standing at a gate with a driver waiting learns everything
     * that is wrong in one go rather than one failed attempt at a time:
     *
     * <ol>
     *   <li>the trip's own statutory documents (3.6.3),
     *   <li>the vehicle's and driver's documents, against the departure date --
     *       the hard stop of 3.2.2,
     *   <li>a certified vehicle and endorsed driver when the load carries
     *       dangerous goods,
     *   <li>the weighed payload against what the load was planned at (3.6.2).
     * </ol>
     *
     * <p>Records the gate-out event and tells planning the load has moved. The
     * order matters: nothing is written until every check has passed.
     */
    @Transactional
    public void dispatch(UUID tripId, Instant at) {
        UUID tenantId = TenantContext.requireTenantId();
        Trip trip = require(tripId);
        Instant when = at == null ? Instant.now() : at;

        LoadLifecyclePort.LoadSummary load = loads.summarise(trip.loadId())
                .orElseThrow(() -> new ResourceNotFoundException("Load", trip.loadId()));

        List<String> blockers = new ArrayList<>();
        blockers.addAll(missingDocuments(tenantId, tripId));
        blockers.addAll(fleetBlockers(trip, when));
        blockers.addAll(hazmatBlockers(trip, load));
        blockers.addAll(weightBlockers(trip, load));

        if (!blockers.isEmpty()) {
            throw new BusinessRuleViolationException("dispatch-blocked",
                    "Trip " + trip.tripNo() + " cannot be dispatched: "
                            + String.join("; ", blockers));
        }

        gateEvents.save(GateEvent.record(UUID.randomUUID(), tenantId, tripId,
                trip.originTerminalId(), GateEvent.EventType.GATE_OUT, when, null));

        Trip dispatched = trips.save(trip.dispatch(when));
        loads.recordDispatched(trip.loadId());

        events.publishEvent(new TripDispatched(tenantId, dispatched.id(), dispatched.tripNo(),
                dispatched.loadId(), dispatched.vehicleId(), dispatched.driverId(),
                dispatched.vendorPartnerId(), dispatched.originTerminalId(),
                dispatched.destinationTerminalId(), dispatched.payloadWeightKg(),
                when, Instant.now()));
    }

    /** Moving. Driven automatically by the origin geofence exit once telematics lands. */
    @Transactional
    public void beginTransit(UUID tripId) {
        trips.save(require(tripId).beginTransit());
    }

    /** Inside the destination geofence. */
    @Transactional
    public void arrive(UUID tripId, Instant at) {
        UUID tenantId = TenantContext.requireTenantId();
        Trip trip = require(tripId);
        Instant when = at == null ? Instant.now() : at;

        if (trip.destinationTerminalId() != null) {
            gateEvents.save(GateEvent.record(UUID.randomUUID(), tenantId, tripId,
                    trip.destinationTerminalId(), GateEvent.EventType.GATE_IN, when, null));
        }
        trips.save(trip.arrive(when));
    }

    /** Proof of delivery accepted. */
    @Transactional
    public void complete(UUID tripId, Instant at) {
        UUID tenantId = TenantContext.requireTenantId();
        Trip trip = require(tripId);
        Instant when = at == null ? Instant.now() : at;

        Trip completed = trips.save(trip.complete(when));

        events.publishEvent(new TripCompleted(tenantId, completed.id(), completed.tripNo(),
                completed.loadId(), completed.vendorPartnerId(), completed.dispatchedAt(),
                when, completed.originDwell(), Instant.now()));
    }

    // -------------------------------------------------------------- blockers

    private List<String> missingDocuments(UUID tenantId, UUID tripId) {
        Set<String> present = documents.findByTrip(tenantId, tripId).stream()
                .map(TripDocument::documentType)
                .collect(java.util.stream.Collectors.toSet());

        return MANDATORY_DOCUMENTS.stream()
                .filter(required -> !present.contains(required))
                .sorted()
                .map(missing -> "no " + missing.toLowerCase(java.util.Locale.ROOT)
                        .replace('_', ' ') + " attached")
                .toList();
    }

    private List<String> fleetBlockers(Trip trip, Instant when) {
        if (trip.vehicleId() == null) {
            return List.of("no vehicle assigned");
        }

        // Judged against the departure date, not today. That is the whole point
        // of the port taking a date: a certificate valid this morning and
        // lapsing this evening is not valid for an evening departure.
        LocalDate departsOn = when.atZone(ZoneOffset.UTC).toLocalDate();
        FleetCompliancePort.ComplianceVerdict verdict =
                compliance.checkDispatchReadiness(trip.vehicleId(), trip.driverId(), departsOn);

        return verdict.dispatchable() ? List.of() : List.copyOf(verdict.blockingReasons());
    }

    /**
     * Dangerous goods need a certified vehicle and an endorsed driver.
     *
     * <p>Asked only of the loads that carry them. The load knows whether it
     * does, because planning propagated the flag from the consignments aboard,
     * so nobody has to remember to tick a box at the gate.
     */
    private List<String> hazmatBlockers(Trip trip, LoadLifecyclePort.LoadSummary load) {
        if (!load.requiresHazmat() || trip.vehicleId() == null) {
            return List.of();
        }
        FleetCompliancePort.ComplianceVerdict verdict =
                compliance.checkHazmatReadiness(trip.vehicleId(), trip.driverId());
        return verdict.dispatchable() ? List.of() : List.copyOf(verdict.blockingReasons());
    }

    private List<String> weightBlockers(Trip trip, LoadLifecyclePort.LoadSummary load) {
        if (!trip.hasBothWeighings()) {
            return List.of("the vehicle has not been weighed both empty and laden");
        }

        BigDecimal variance = trip.payloadVariancePct(load.plannedWeightKg());
        if (variance != null && variance.compareTo(weightTolerancePct) > 0) {
            return List.of("weighed payload of " + trip.payloadWeightKg()
                    + " kg differs from the planned " + load.plannedWeightKg()
                    + " kg by " + variance + "%, beyond the " + weightTolerancePct
                    + "% tolerance");
        }
        return List.of();
    }

    private Trip require(UUID tripId) {
        return trips.findById(tripId)
                .orElseThrow(() -> new ResourceNotFoundException("Trip", tripId));
    }
}
