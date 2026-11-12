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
import java.util.UUID;

import com.lms.shared.error.BusinessRuleViolationException;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.relational.core.mapping.Table;

/**
 * One visit of a vehicle to a site: the entry, and the exit that closes it
 * (issue #223).
 *
 * <p>The register covers every vehicle that comes through a gate, a trip's or
 * not. A registered vehicle is named by id and a visitor's by its typed
 * registration number; either way {@code registrationNo} is the identity, and
 * at most one visit per number is open at a time (a unique index says so as
 * well). Where the visit is a trip's, {@code tripId} links it, so the trip
 * view and the register tell one story.
 *
 * <p>Unlike {@link GateEvent}, which is a trip's append-only evidence, a visit
 * is closed in place by its exit: it is the answer to "who is inside".
 */
@Table("gate_visit")
public record GateVisit(
        @Id UUID id,
        UUID tenantId,
        UUID orgUnitId,
        UUID terminalId,
        UUID vehicleId,
        String registrationNo,
        UUID driverId,
        String driverName,
        Purpose purpose,
        UUID tripId,
        UUID gatePassId,
        Status status,
        Instant enteredAt,
        String[] entrySealNos,
        String entryWeightRef,
        String entryRemarks,
        Instant exitedAt,
        String[] exitSealNos,
        String exitWeightRef,
        String exitRemarks,
        @Version Long version,
        Instant createdAt,
        Instant updatedAt) {

    public enum Purpose {
        TRIP, VISITOR, MAINTENANCE, RETURN, OTHER
    }

    public enum Status {
        OPEN, CLOSED
    }

    public static GateVisit enter(UUID id, UUID tenantId, UUID orgUnitId, UUID terminalId, UUID vehicleId,
                                  String registrationNo, UUID driverId, String driverName, Purpose purpose,
                                  UUID tripId, UUID gatePassId, Instant at, String[] sealNos,
                                  String weightRef, String remarks) {
        String registration = Registrations.normalise(registrationNo);
        if (registration == null) {
            throw new IllegalArgumentException("A vehicle entering needs a registration number");
        }
        if (purpose == null) {
            throw new IllegalArgumentException("A visit needs a purpose");
        }
        Instant now = Instant.now();
        return new GateVisit(id, tenantId, orgUnitId, terminalId, vehicleId, registration, driverId,
                blankToNull(driverName), purpose, tripId, gatePassId, Status.OPEN, at,
                sealNos == null ? new String[0] : sealNos, blankToNull(weightRef), blankToNull(remarks),
                null, new String[0], null, null, null, now, now);
    }

    /** Closes the visit. Refused when it is already closed or the exit precedes the entry. */
    public GateVisit exit(Instant at, String[] sealNos, String weightRef, String remarks) {
        if (status == Status.CLOSED) {
            throw new BusinessRuleViolationException("gate-visit-closed",
                    "Vehicle " + registrationNo + " has already left; an exit closes a visit once");
        }
        if (at.isBefore(enteredAt)) {
            throw new BusinessRuleViolationException("gate-exit-before-entry",
                    "The exit of " + registrationNo + " cannot be earlier than its entry at " + enteredAt);
        }
        return new GateVisit(id, tenantId, orgUnitId, terminalId, vehicleId, registrationNo, driverId,
                driverName, purpose, tripId, gatePassId, Status.CLOSED, enteredAt, entrySealNos,
                entryWeightRef, entryRemarks, at, sealNos == null ? new String[0] : sealNos,
                blankToNull(weightRef), blankToNull(remarks), version, createdAt, Instant.now());
    }

    /** The visit, linked to the trip it turned out to belong to. */
    public GateVisit forTrip(UUID trip) {
        return new GateVisit(id, tenantId, orgUnitId, terminalId, vehicleId, registrationNo, driverId,
                driverName, purpose, trip, gatePassId, status, enteredAt, entrySealNos, entryWeightRef,
                entryRemarks, exitedAt, exitSealNos, exitWeightRef, exitRemarks, version, createdAt,
                Instant.now());
    }

    public boolean isOpen() {
        return status == Status.OPEN;
    }

    private static String blankToNull(String text) {
        return text == null || text.isBlank() ? null : text.strip();
    }
}
