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

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.lms.execution.command.domain.GatePass;
import com.lms.execution.command.domain.GateVisit;
import com.lms.execution.command.domain.Registrations;
import com.lms.execution.command.domain.Trip;
import com.lms.execution.events.GateEntryRecorded;
import com.lms.execution.events.GateExitRecorded;
import com.lms.masterdata.api.FleetCapacityPort;
import com.lms.masterdata.api.TerminalOrgUnitPort;
import com.lms.settings.api.SettingContext;
import com.lms.settings.api.Settings;
import com.lms.shared.audit.AuditTrail;
import com.lms.shared.config.Json;
import com.lms.shared.error.BusinessRuleViolationException;
import com.lms.shared.error.ResourceNotFoundException;
import com.lms.shared.tenant.TenantContext;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;

/**
 * The gate register's rules, whoever asks: the desk through
 * {@link GateRegisterService}, a trip's own gate-in and dispatch through
 * {@link TripCommandService}. Joins the caller's transaction and checks no
 * permission; the callers do.
 *
 * <p>An entry resolves its site (a unit, or a terminal and so its unit), its
 * vehicle (registered, or a visitor's typed number) and its driver, refuses a
 * second entry while the vehicle is already inside, checks the gate pass when
 * the unit demands one or one is presented, and writes the visit, the pass's
 * use, the audit row and the event together. A refusal writes nothing.
 *
 * <p>The register is the answer to "who is inside", so what keeps it true is
 * the exit. A trip's gate-in opens a visit and its dispatch (or cancellation)
 * closes it; a trip's arrival at its destination does not open one, because a
 * trip has no gate-out there to close it again.
 */
@Component
class GateRegister {

    /** An entry as asked for: its site (a unit, or a terminal and so its unit), vehicle, driver and detail. */
    record Entry(UUID orgUnitId, UUID terminalId, UUID vehicleId, String registrationNo, UUID driverId,
                 String driverName, GateVisit.Purpose purpose, UUID tripId, String gatePassNo, Instant at,
                 List<String> sealNos, String weightRef, String remarks) {
    }

    private final GateVisitRepository visits;
    private final GatePassRepository passes;
    private final GatePassDesk passDesk;
    private final TripRepository trips;
    private final FleetCapacityPort fleet;
    private final TerminalOrgUnitPort terminals;
    private final Settings settings;
    private final AuditTrail audit;
    private final ApplicationEventPublisher events;

    GateRegister(GateVisitRepository visits, GatePassRepository passes, GatePassDesk passDesk,
                 TripRepository trips, FleetCapacityPort fleet, TerminalOrgUnitPort terminals,
                 Settings settings, AuditTrail audit, ApplicationEventPublisher events) {
        this.visits = visits;
        this.passes = passes;
        this.passDesk = passDesk;
        this.trips = trips;
        this.fleet = fleet;
        this.terminals = terminals;
        this.settings = settings;
        this.audit = audit;
        this.events = events;
    }

    GateVisit enter(Entry entry) {
        UUID tenantId = TenantContext.requireTenantId();
        Instant at = entry.at() == null ? Instant.now() : entry.at();

        UUID orgUnitId = siteUnit(entry);
        String registration = registrationOf(entry);
        if (entry.driverId() != null && !fleet.isDriverRegistered(entry.driverId())) {
            throw new ResourceNotFoundException("Driver", entry.driverId());
        }
        Trip trip = entry.tripId() == null ? null : trips.findById(entry.tripId())
                .orElseThrow(() -> new ResourceNotFoundException("Trip", entry.tripId()));
        if (trip != null && trip.vehicleId() != null) {
            String tripVehicle = fleet.registrationOf(trip.vehicleId()).map(Registrations::normalise).orElse(null);
            if (tripVehicle != null && !tripVehicle.equals(registration)) {
                throw new BusinessRuleViolationException("gate-trip-vehicle-mismatch",
                        "Trip " + trip.tripNo() + " runs on " + tripVehicle + ", not " + registration);
            }
        }

        visits.findOpen(tenantId, registration).ifPresent(open -> {
            throw new BusinessRuleViolationException("gate-entry-already-open",
                    "Vehicle " + registration + " is already inside since " + open.enteredAt()
                            + "; record its exit before it enters again");
        });

        GatePass pass = null;
        boolean required = settings.effective(ExecutionSettingDefinitions.GATE_PASS_REQUIRED,
                SettingContext.ofOrgUnit(orgUnitId));
        boolean presented = entry.gatePassNo() != null && !entry.gatePassNo().isBlank();
        if (required || presented) {
            GatePassDesk.Verdict verdict = passDesk.evaluate(orgUnitId, entry.terminalId(), registration,
                    entry.gatePassNo(), entry.tripId(), at);
            verdict.requireAdmits();
            pass = verdict.pass();
        }

        UUID tripId = entry.tripId() != null ? entry.tripId() : pass == null ? null : pass.tripId();
        UUID visitId = UUID.randomUUID();
        GateVisit visit = GateVisit.enter(visitId, tenantId, orgUnitId, entry.terminalId(), entry.vehicleId(),
                registration, entry.driverId(), entry.driverName(), entry.purpose(), tripId,
                pass == null ? null : pass.id(), at, Registrations.seals(entry.sealNos()), entry.weightRef(),
                entry.remarks());
        GateVisit saved;
        try {
            saved = visits.save(visit);
        } catch (DuplicateKeyException lostRace) {
            throw new BusinessRuleViolationException("gate-entry-already-open",
                    "Vehicle " + registration + " is already inside; record its exit before it enters again");
        }
        if (pass != null) {
            passes.save(pass.use(saved.id(), at));
        }

        audit.recordAtomically("GATE_ENTRY", "GATE_VISIT", saved.id(), null, snapshot(saved,
                "gatePassNo", pass == null ? null : pass.passNo()));
        events.publishEvent(new GateEntryRecorded(tenantId, saved.id(), orgUnitId, saved.terminalId(),
                saved.vehicleId(), registration, saved.purpose().name(), saved.tripId(),
                pass == null ? null : pass.passNo(), at, Instant.now()));
        return saved;
    }

    GateVisit exit(UUID visitId, Instant at, List<String> sealNos, String weightRef, String remarks) {
        GateVisit before = visits.findById(visitId)
                .orElseThrow(() -> new ResourceNotFoundException("GateVisit", visitId));
        return close(before, at == null ? Instant.now() : at, sealNos, weightRef, remarks);
    }

    /** A trip's gate-in, mirrored into the register when its vehicle is known. Never refuses on the trip's behalf. */
    void tripGatedIn(Trip trip, UUID terminalId, Instant at, String remarks, String gatePassNo) {
        if (trip.vehicleId() == null) {
            return;
        }
        Optional<String> registration = fleet.registrationOf(trip.vehicleId());
        Optional<UUID> unit = terminals.orgUnitOf(terminalId);
        if (registration.isEmpty() || unit.isEmpty()) {
            return;
        }
        Optional<GateVisit> open = visits.findOpen(trip.tenantId(), Registrations.normalise(registration.get()));
        if (open.isPresent()) {
            // Already inside, as a visitor or on a pass: it is this trip's visit now.
            GateVisit inside = open.get();
            if (inside.tripId() == null && terminalId.equals(inside.terminalId())) {
                visits.save(inside.forTrip(trip.id()));
            }
            return;
        }
        enter(new Entry(unit.get(), terminalId, trip.vehicleId(), registration.get(), trip.driverId(), null,
                GateVisit.Purpose.TRIP, trip.id(), gatePassNo, at, List.of(), null, remarks));
    }

    /** A trip's dispatch (or cancellation) closes the visit it opened, if any. */
    void tripLeft(Trip trip, Instant at, String remarks) {
        if (trip.vehicleId() == null) {
            return;
        }
        fleet.registrationOf(trip.vehicleId())
                .flatMap(registration -> visits.findOpen(trip.tenantId(), Registrations.normalise(registration)))
                .filter(open -> trip.id().equals(open.tripId()))
                .ifPresent(open -> close(open, at.isBefore(open.enteredAt()) ? open.enteredAt() : at,
                        List.of(), null, remarks));
    }

    private GateVisit close(GateVisit before, Instant at, List<String> sealNos, String weightRef,
                            String remarks) {
        GateVisit closed = visits.save(before.exit(at, Registrations.seals(sealNos), weightRef, remarks));
        audit.recordAtomically("GATE_EXIT", "GATE_VISIT", closed.id(), snapshot(before), snapshot(closed));
        events.publishEvent(new GateExitRecorded(closed.tenantId(), closed.id(), closed.orgUnitId(),
                closed.terminalId(), closed.vehicleId(), closed.registrationNo(), closed.purpose().name(),
                closed.tripId(), closed.enteredAt(), at,
                Duration.between(closed.enteredAt(), at).toMinutes(), Instant.now()));
        return closed;
    }

    private UUID siteUnit(Entry entry) {
        if (entry.terminalId() != null) {
            UUID unit = terminals.orgUnitOf(entry.terminalId())
                    .orElseThrow(() -> new ResourceNotFoundException("Terminal", entry.terminalId()));
            if (entry.orgUnitId() != null && !entry.orgUnitId().equals(unit)) {
                throw new BusinessRuleViolationException("gate-terminal-not-in-unit",
                        "The terminal belongs to another organisational unit than the site named");
            }
            return unit;
        }
        if (entry.orgUnitId() == null) {
            throw new IllegalArgumentException("A gate entry names its site: an organisational unit or a terminal");
        }
        return entry.orgUnitId();
    }

    private String registrationOf(Entry entry) {
        if (entry.vehicleId() != null) {
            return Registrations.normalise(fleet.registrationOf(entry.vehicleId())
                    .orElseThrow(() -> new ResourceNotFoundException("Vehicle", entry.vehicleId())));
        }
        String typed = Registrations.normalise(entry.registrationNo());
        if (typed == null) {
            throw new IllegalArgumentException("A gate entry names a registered vehicle or a registration number");
        }
        return typed;
    }

    static Json snapshot(GateVisit visit, String... extra) {
        String[] pairs = new String[14 + extra.length];
        int i = 0;
        pairs[i++] = "registrationNo";
        pairs[i++] = visit.registrationNo();
        pairs[i++] = "purpose";
        pairs[i++] = visit.purpose().name();
        pairs[i++] = "status";
        pairs[i++] = visit.status().name();
        pairs[i++] = "orgUnitId";
        pairs[i++] = String.valueOf(visit.orgUnitId());
        pairs[i++] = "terminalId";
        pairs[i++] = visit.terminalId() == null ? null : visit.terminalId().toString();
        pairs[i++] = "tripId";
        pairs[i++] = visit.tripId() == null ? null : visit.tripId().toString();
        pairs[i++] = "enteredAt";
        pairs[i++] = String.valueOf(visit.enteredAt());
        for (String e : extra) {
            pairs[i++] = e;
        }
        String[] trimmed = java.util.Arrays.copyOf(pairs, i);
        return Json.object(trimmed);
    }
}
