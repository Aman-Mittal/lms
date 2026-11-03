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
import com.lms.execution.events.TripCancelled;
import com.lms.execution.events.TripCompleted;
import com.lms.execution.events.TripDispatched;
import com.lms.masterdata.api.FleetCompliancePort;
import com.lms.planning.api.LoadLifecyclePort;
import com.lms.shared.audit.AuditTrail;
import com.lms.shared.config.Json;
import com.lms.shared.error.BusinessRuleViolationException;
import com.lms.shared.error.ResourceNotFoundException;
import com.lms.shared.tenant.TenantContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.prepost.PreAuthorize;
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
    private final AuditTrail audit;

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
                              AuditTrail audit,
                              @Value("${lms.execution.weight-tolerance-pct:5}")
                              BigDecimal weightTolerancePct) {
        this.trips = trips;
        this.documents = documents;
        this.gateEvents = gateEvents;
        this.weighings = weighings;
        this.loads = loads;
        this.compliance = compliance;
        this.events = events;
        this.audit = audit;
        this.weightTolerancePct = weightTolerancePct;
    }

    /** Raises a trip against an awarded load. */
    @Transactional
    @PreAuthorize("hasAuthority('TRIP_EXECUTE') and @abac.permits('TRIP_EXECUTE', 'LOAD', #loadId)")
    public UUID raiseTrip(String tripNo, UUID loadId, Instant plannedStartAt) {
        UUID tenantId = TenantContext.requireTenantId();

        LoadLifecyclePort.LoadSummary load = loads.summarise(loadId)
                .orElseThrow(() -> new ResourceNotFoundException("Load", loadId));

        if (!"AWARDED".equals(load.status())) {
            throw new BusinessRuleViolationException("load-not-awarded",
                    "Load " + load.loadNo() + " is " + load.status()
                            + "; a trip can only be raised against an awarded load");
        }

        // A cancelled trip no longer holds its load, so a replacement can be raised.
        trips.findLiveByLoad(tenantId, loadId).ifPresent(existing -> {
            throw new BusinessRuleViolationException("load-already-has-trip",
                    "Load " + load.loadNo() + " already has trip " + existing.tripNo());
        });

        trips.findByTripNo(tenantId, tripNo).ifPresent(existing -> {
            throw new BusinessRuleViolationException("trip-no-taken",
                    "A trip numbered " + tripNo + " already exists");
        });

        Trip raised = trips.save(Trip.raise(UUID.randomUUID(), tenantId, load.orgUnitId(), loadId,
                tripNo, load.awardedVendorPartnerId(), load.originTerminalId(),
                load.destinationTerminalId(), plannedStartAt));
        auditTransition("TRIP_RAISED", null, raised);
        return raised.id();
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
    @PreAuthorize("hasAuthority('TRIP_EXECUTE')")
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

        Trip assigned = trips.save(trip.assign(vehicleId, driverId));
        auditTransition("TRIP_ASSIGNED", trip, assigned);
    }

    @Transactional
    @PreAuthorize("hasAuthority('TRIP_EXECUTE')")
    public UUID attachDocument(UUID tripId, String documentType, String documentRef) {
        UUID tenantId = TenantContext.requireTenantId();
        Trip trip = require(tripId);

        if (trip.isOnRoad() || trip.status() == Trip.TripStatus.COMPLETED) {
            throw new BusinessRuleViolationException("trip-already-dispatched",
                    "Trip " + trip.tripNo() + " has already left; documents cannot be "
                            + "added retrospectively");
        }

        UUID documentId = documents.save(TripDocument.attach(UUID.randomUUID(), tenantId, tripId,
                documentType, documentRef)).id();
        audit.recordAtomically("TRIP_DOCUMENT_ATTACHED", "TRIP", tripId, null,
                Json.object("tripNo", trip.tripNo(), "documentType", documentType,
                        "documentRef", documentRef, "documentId", documentId.toString()));
        return documentId;
    }

    // ------------------------------------------------------------ 3.6.2 gate

    @Transactional
    @PreAuthorize("hasAuthority('TRIP_EXECUTE')")
    public void gateIn(UUID tripId, UUID terminalId, Instant at, String remarks) {
        UUID tenantId = TenantContext.requireTenantId();
        Trip trip = require(tripId);
        Instant when = at == null ? Instant.now() : at;

        gateEvents.save(GateEvent.record(UUID.randomUUID(), tenantId, tripId, terminalId,
                GateEvent.EventType.GATE_IN, when, remarks));
        Trip arrived = trips.save(trip.gateIn(when));
        auditTransition("TRIP_GATE_IN", trip, arrived, "terminalId", String.valueOf(terminalId),
                "at", when.toString());
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
    @PreAuthorize("hasAuthority('TRIP_EXECUTE')")
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

        Trip weighed = trips.save(updated);
        auditTransition("TRIP_WEIGHED", trip, weighed, "readingType", type.name(),
                "terminalId", String.valueOf(terminalId), "weightKg", weightKg.toPlainString());
    }

    /** Loading is finished and the vehicle has been weighed laden. */
    @Transactional
    @PreAuthorize("hasAuthority('TRIP_EXECUTE')")
    public void markLoaded(UUID tripId, Instant at) {
        Trip trip = require(tripId);

        if (!trip.hasBothWeighings()) {
            throw new BusinessRuleViolationException("weighing-incomplete",
                    "Trip " + trip.tripNo() + " cannot be marked loaded without both a tare "
                            + "and a gross weighbridge reading");
        }

        Trip loaded = trips.save(trip.markLoaded(at == null ? Instant.now() : at));
        auditTransition("TRIP_LOADED", trip, loaded);
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
    @PreAuthorize("hasAuthority('TRIP_EXECUTE')")
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
        auditTransition("TRIP_DISPATCHED", trip, dispatched, "at", when.toString());

        events.publishEvent(new TripDispatched(tenantId, dispatched.id(), dispatched.tripNo(),
                dispatched.loadId(), dispatched.vehicleId(), dispatched.driverId(),
                dispatched.vendorPartnerId(), dispatched.originTerminalId(),
                dispatched.destinationTerminalId(), dispatched.payloadWeightKg(),
                when, Instant.now()));
    }

    /** Moving. Driven automatically by the origin geofence exit once telematics lands. */
    @Transactional
    @PreAuthorize("hasAuthority('TRIP_EXECUTE')")
    public void beginTransit(UUID tripId) {
        Trip trip = require(tripId);
        Trip moving = trips.save(trip.beginTransit());
        if (moving.status() != trip.status()) {
            auditTransition("TRIP_IN_TRANSIT", trip, moving);
        }
    }

    /** Inside the destination geofence. */
    @Transactional
    @PreAuthorize("hasAuthority('TRIP_EXECUTE')")
    public void arrive(UUID tripId, Instant at) {
        UUID tenantId = TenantContext.requireTenantId();
        Trip trip = require(tripId);
        Instant when = at == null ? Instant.now() : at;

        if (trip.destinationTerminalId() != null) {
            gateEvents.save(GateEvent.record(UUID.randomUUID(), tenantId, tripId,
                    trip.destinationTerminalId(), GateEvent.EventType.GATE_IN, when, null));
        }
        Trip arrived = trips.save(trip.arrive(when));
        auditTransition("TRIP_ARRIVED", trip, arrived, "at", when.toString());
    }

    /** Proof of delivery accepted. */
    @Transactional
    @PreAuthorize("hasAuthority('TRIP_EXECUTE')")
    public void complete(UUID tripId, Instant at) {
        UUID tenantId = TenantContext.requireTenantId();
        Trip trip = require(tripId);
        Instant when = at == null ? Instant.now() : at;

        Trip completed = trips.save(trip.complete(when));
        // Synchronous, in this transaction, like dispatch: planning moves the
        // freight aboard -- a part-load line haul's dockets have now arrived
        // at their destination hub -- and a refusal there must undo this.
        loads.recordTripCompleted(completed.loadId());
        auditTransition("TRIP_COMPLETED", trip, completed, "at", when.toString());

        events.publishEvent(new TripCompleted(tenantId, completed.id(), completed.tripNo(),
                completed.loadId(), completed.vendorPartnerId(), completed.dispatchedAt(),
                when, completed.originDwell(), completed.payloadWeightKg(), Instant.now()));
    }

    // ---------------------------------------------------------------- cancel

    /**
     * Cancels a trip that has not left the yard.
     *
     * <p>What the trip held is released by the cancellation itself. The load
     * was never moved by raising the trip (it stays AWARDED until dispatch),
     * so there is nothing to hand back there; the trip stops holding it
     * because only a live trip counts against the one-trip-per-load rule. The
     * vendor's award stands: whether to re-offer the load is the planner's
     * call, made through sourcing. No freight bill can exist for the trip, as
     * billing follows completion and a cancelled trip never completes.
     *
     * <p>Cancelling twice is a no-op, not an error, so a retried request whose
     * first answer was lost still succeeds. Once the vehicle has been
     * dispatched the state machine refuses with {@code trip-not-cancellable}.
     *
     * <p>Held to the caller's organisational subtree like raising a trip
     * (the {@code trip-write-org-subtree} policy, applied to the trip's own
     * unit).
     */
    @Transactional
    @PreAuthorize("hasAuthority('TRIP_EXECUTE') and @abac.permits('TRIP_EXECUTE', 'TRIP', #tripId)")
    public void cancel(UUID tripId, String reason) {
        UUID tenantId = TenantContext.requireTenantId();
        Trip trip = require(tripId);
        if (trip.status() == Trip.TripStatus.CANCELLED) {
            return;
        }
        Instant now = Instant.now();

        Trip cancelled = trips.save(trip.cancel(reason, now));
        auditTransition("TRIP_CANCELLED", trip, cancelled, "reason", cancelled.cancellationReason());

        events.publishEvent(new TripCancelled(tenantId, cancelled.id(), cancelled.tripNo(),
                cancelled.loadId(), cancelled.vendorPartnerId(), cancelled.cancellationReason(),
                now, now));
    }

    // ----------------------------------------------------------------- audit

    /**
     * One audit row per transition, with the trip before and after.
     *
     * <p>Written in the transaction of the change itself, so the two commit
     * or roll back together and no second connection is taken (see
     * {@code AuditTrail.recordAtomically}). A refused transition therefore
     * leaves no entry; refusals are not audited here.
     *
     * <p>Not written for GPS pings or geofence crossings: those are
     * telemetry arriving at a rate the audit log is not built for, and the
     * decisions they lead to are already durable records of their own -- a
     * stoppage or deviation becomes a control tower ticket with its own
     * activity history.
     */
    private void auditTransition(String action, Trip before, Trip after, String... extra) {
        Json afterState = snapshot(after, extra);
        audit.recordAtomically(action, "TRIP", after.id(),
                before == null ? null : snapshot(before), afterState);
    }

    private static Json snapshot(Trip trip, String... extra) {
        List<String> pairs = new ArrayList<>();
        add(pairs, "tripNo", trip.tripNo());
        add(pairs, "status", trip.status().name());
        add(pairs, "loadId", String.valueOf(trip.loadId()));
        add(pairs, "vehicleId", trip.vehicleId());
        add(pairs, "driverId", trip.driverId());
        add(pairs, "tareWeightKg", trip.tareWeightKg());
        add(pairs, "grossWeightKg", trip.grossWeightKg());
        add(pairs, "payloadWeightKg", trip.payloadWeightKg());
        for (int i = 0; i < extra.length; i += 2) {
            add(pairs, extra[i], extra[i + 1]);
        }
        return Json.object(pairs.toArray(String[]::new));
    }

    private static void add(List<String> pairs, String key, Object value) {
        pairs.add(key);
        pairs.add(value == null ? null : value.toString());
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
