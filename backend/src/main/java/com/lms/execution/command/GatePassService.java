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
import java.util.UUID;

import com.lms.execution.command.domain.GatePass;
import com.lms.execution.command.domain.GateVisit;
import com.lms.execution.command.domain.Registrations;
import com.lms.execution.command.domain.Trip;
import com.lms.identity.numbering.DocumentNumbers;
import com.lms.identity.numbering.DocumentType;
import com.lms.masterdata.api.FleetCapacityPort;
import com.lms.masterdata.api.TerminalOrgUnitPort;
import com.lms.shared.audit.AuditTrail;
import com.lms.shared.config.Json;
import com.lms.shared.error.BusinessRuleViolationException;
import com.lms.shared.error.ResourceNotFoundException;
import com.lms.shared.tenant.TenantContext;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Issuing and cancelling gate passes (issue #223).
 *
 * <p>The number is drawn from the {@code GATE_PASS} series that covers the
 * unit, in the issuing transaction, so a rolled-back pass gives its number back
 * and a cancelled one releases it. Where no series covers the unit the issuer
 * types the number ({@code gate-pass-number-required} otherwise): a pass has no
 * older numbering to fall back on. Both rights are held to the site's unit by
 * the access policies.
 */
@Service
public class GatePassService {

    /** A pass to issue; {@code passNo} is used only where no series covers the unit. */
    public record IssueCommand(UUID orgUnitId, UUID terminalId, UUID vehicleId, String registrationNo, UUID tripId,
                               GateVisit.Purpose purpose, Instant validFrom, Instant validUntil, String passNo) {
    }

    private final GatePassRepository passes;
    private final TripRepository trips;
    private final FleetCapacityPort fleet;
    private final TerminalOrgUnitPort terminals;
    private final DocumentNumbers numbers;
    private final AuditTrail audit;

    public GatePassService(GatePassRepository passes, TripRepository trips, FleetCapacityPort fleet,
                           TerminalOrgUnitPort terminals, DocumentNumbers numbers, AuditTrail audit) {
        this.passes = passes;
        this.trips = trips;
        this.fleet = fleet;
        this.terminals = terminals;
        this.numbers = numbers;
        this.audit = audit;
    }

    @Transactional
    @PreAuthorize("hasAuthority('GATE_PASS_MANAGE')"
            + " and @abac.permitsIn('GATE_PASS_MANAGE', @gateSites.unitOf(#command.orgUnitId(), #command.terminalId()))")
    public UUID issue(IssueCommand command) {
        UUID tenantId = TenantContext.requireTenantId();
        UUID orgUnitId = command.orgUnitId();
        if (command.terminalId() != null) {
            UUID unit = terminals.orgUnitOf(command.terminalId())
                    .orElseThrow(() -> new ResourceNotFoundException("Terminal", command.terminalId()));
            if (orgUnitId != null && !orgUnitId.equals(unit)) {
                throw new BusinessRuleViolationException("gate-terminal-not-in-unit",
                        "The terminal belongs to another organisational unit than the site named");
            }
            orgUnitId = unit;
        }
        if (orgUnitId == null) {
            throw new IllegalArgumentException("A gate pass names its site: an organisational unit or a terminal");
        }
        String registration;
        if (command.vehicleId() != null) {
            registration = fleet.registrationOf(command.vehicleId())
                    .orElseThrow(() -> new ResourceNotFoundException("Vehicle", command.vehicleId()));
        } else {
            registration = command.registrationNo();
        }
        if (Registrations.normalise(registration) == null) {
            throw new IllegalArgumentException("A gate pass names a registered vehicle or a registration number");
        }
        UUID tripId = command.tripId();
        if (tripId != null) {
            Trip trip = trips.findById(tripId).orElseThrow(() -> new ResourceNotFoundException("Trip", tripId));
            if (trip.status() == Trip.TripStatus.CANCELLED) {
                throw new BusinessRuleViolationException("gate-pass-trip-cancelled",
                        "Trip " + trip.tripNo() + " is cancelled; no pass is issued for it");
            }
        }

        String drawn = numbers.next(DocumentType.GATE_PASS, orgUnitId).orElse(null);
        String number = drawn != null ? drawn : command.passNo();
        if (number == null || number.isBlank()) {
            throw new BusinessRuleViolationException("gate-pass-number-required",
                    "No GATE_PASS number series covers this unit; give the pass a number");
        }
        passes.findByNumber(tenantId, number.strip()).ifPresent(existing -> {
            throw new BusinessRuleViolationException("gate-pass-number-taken",
                    "A gate pass numbered " + number.strip() + " already exists");
        });

        GatePass pass = GatePass.issue(UUID.randomUUID(), tenantId, orgUnitId, command.terminalId(), number,
                command.vehicleId(), registration, tripId, command.purpose(), command.validFrom(),
                command.validUntil());
        GatePass saved;
        try {
            saved = passes.save(pass);
        } catch (DuplicateKeyException taken) {
            throw new BusinessRuleViolationException("gate-pass-number-taken",
                    "A gate pass numbered " + number.strip() + " already exists");
        }
        audit.recordAtomically("GATE_PASS_ISSUED", "GATE_PASS", saved.id(), null, snapshot(saved));
        return saved.id();
    }

    /** Withdraws a pass that has not been used, with the reason; the number goes back to its series. */
    @Transactional
    @PreAuthorize("hasAuthority('GATE_PASS_MANAGE') and @abac.permitsIn('GATE_PASS_MANAGE', @gateSites.unitOfPass(#id))")
    public void cancel(UUID id, long expectedVersion, String reason) {
        GatePass before = passes.findById(id).orElseThrow(() -> new ResourceNotFoundException("GatePass", id));
        if (before.version() == null || before.version() != expectedVersion) {
            throw new org.springframework.dao.OptimisticLockingFailureException(
                    "Gate pass " + before.passNo() + " was changed since it was read");
        }
        GatePass cancelled = passes.save(before.cancel(reason, Instant.now()));
        numbers.release(DocumentType.GATE_PASS, cancelled.passNo(), reason.strip());
        audit.recordAtomically("GATE_PASS_CANCELLED", "GATE_PASS", id, snapshot(before), snapshot(cancelled,
                "reason", cancelled.cancelReason()));
    }

    static Json snapshot(GatePass pass, String... extra) {
        String[] pairs = new String[10 + extra.length];
        int i = 0;
        pairs[i++] = "passNo";
        pairs[i++] = pass.passNo();
        pairs[i++] = "status";
        pairs[i++] = pass.status().name();
        pairs[i++] = "registrationNo";
        pairs[i++] = pass.registrationNo();
        pairs[i++] = "validFrom";
        pairs[i++] = String.valueOf(pass.validFrom());
        pairs[i++] = "validUntil";
        pairs[i++] = String.valueOf(pass.validUntil());
        for (String e : extra) {
            pairs[i++] = e;
        }
        return Json.object(pairs);
    }
}
