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
 * Permission for a vehicle to come in, for a window and a purpose (issue #223).
 *
 * <p>Issued for a registered vehicle or a visitor's registration number, and
 * optionally for a trip. It is checked at entry and consumed by it: a pass
 * admits one visit. A pass is never expired by a job -- {@link #check} reads
 * the clock it is given -- and a cancelled one stays on record with its reason.
 */
@Table("gate_pass")
public record GatePass(
        @Id UUID id,
        UUID tenantId,
        UUID orgUnitId,
        UUID terminalId,
        String passNo,
        UUID vehicleId,
        String registrationNo,
        UUID tripId,
        GateVisit.Purpose purpose,
        Instant validFrom,
        Instant validUntil,
        Status status,
        Instant usedAt,
        UUID usedVisitId,
        Instant cancelledAt,
        String cancelReason,
        @Version Long version,
        Instant createdAt,
        Instant updatedAt) {

    public enum Status {
        ISSUED, USED, CANCELLED
    }

    public static GatePass issue(UUID id, UUID tenantId, UUID orgUnitId, UUID terminalId, String passNo,
                                 UUID vehicleId, String registrationNo, UUID tripId,
                                 GateVisit.Purpose purpose, Instant validFrom, Instant validUntil) {
        String registration = Registrations.normalise(registrationNo);
        if (registration == null) {
            throw new IllegalArgumentException("A gate pass is for a vehicle");
        }
        if (passNo == null || passNo.isBlank()) {
            throw new IllegalArgumentException("A gate pass needs a number");
        }
        if (purpose == null || validFrom == null || validUntil == null) {
            throw new IllegalArgumentException("A gate pass needs a purpose and a validity window");
        }
        if (!validUntil.isAfter(validFrom)) {
            throw new BusinessRuleViolationException("gate-pass-window-invalid",
                    "A gate pass is valid until a time after it is valid from");
        }
        Instant now = Instant.now();
        return new GatePass(id, tenantId, orgUnitId, terminalId, passNo.strip(), vehicleId, registration,
                tripId, purpose, validFrom, validUntil, Status.ISSUED, null, null, null, null, null, now, now);
    }

    /**
     * The refusal this pass gives to an entry at {@code at}, by problem code,
     * or null when it admits one. Cancelled and used are judged before the
     * clock: a pass that is both used and out of date was used.
     */
    public String refusalAt(Instant at) {
        if (status == Status.CANCELLED) {
            return "gate-pass-cancelled";
        }
        if (status == Status.USED) {
            return "gate-pass-used";
        }
        if (at.isBefore(validFrom) || at.isAfter(validUntil)) {
            return "gate-pass-expired";
        }
        return null;
    }

    public boolean admitsAt(Instant at) {
        return refusalAt(at) == null;
    }

    /** Consumed by the visit it admitted. */
    public GatePass use(UUID visitId, Instant at) {
        String refusal = refusalAt(at);
        if (refusal != null) {
            throw new BusinessRuleViolationException(refusal,
                    "Gate pass " + passNo + " cannot admit an entry: " + describe(refusal));
        }
        return new GatePass(id, tenantId, orgUnitId, terminalId, passNo, vehicleId, registrationNo, tripId,
                purpose, validFrom, validUntil, Status.USED, at, visitId, null, null, version, createdAt,
                Instant.now());
    }

    /** Withdrawn. A used pass was honoured and cannot be taken back. */
    public GatePass cancel(String reason, Instant at) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("Cancelling a gate pass needs a reason");
        }
        if (status == Status.USED) {
            throw new BusinessRuleViolationException("gate-pass-used",
                    "Gate pass " + passNo + " has already admitted a vehicle and cannot be cancelled");
        }
        if (status == Status.CANCELLED) {
            throw new BusinessRuleViolationException("gate-pass-cancelled",
                    "Gate pass " + passNo + " is already cancelled");
        }
        return new GatePass(id, tenantId, orgUnitId, terminalId, passNo, vehicleId, registrationNo, tripId,
                purpose, validFrom, validUntil, Status.CANCELLED, null, null, at, reason.strip(), version,
                createdAt, Instant.now());
    }

    private String describe(String code) {
        return switch (code) {
            case "gate-pass-cancelled" -> "it was cancelled";
            case "gate-pass-used" -> "it has already been used";
            default -> "it is valid from " + validFrom + " until " + validUntil;
        };
    }
}
