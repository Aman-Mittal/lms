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
package com.lms.finance.command.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import com.lms.shared.error.BusinessRuleViolationException;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.relational.core.mapping.Table;

/**
 * One version of one vendor's rate card for one lane (vision document 3.9.1).
 *
 * <p>Not "the rate for this lane". A rate card is a bilateral agreement with a
 * period of validity, and the period is the whole point: an invoice raised in
 * March for a trip dispatched in January must be priced with January's card,
 * even though the rate changed in February. A mutable rate table cannot answer
 * that question at all -- it has forgotten January ever happened.
 *
 * <p>The accessorial terms live here rather than in a table of their own
 * because they are negotiated in the same conversation as the base rate and
 * change with it. Splitting them out would create a second thing to version,
 * and a second opportunity for the two halves of one agreement to disagree.
 */
@Table("tariff")
public record Tariff(
        @Id UUID id,
        UUID tenantId,
        UUID orgUnitId,
        String code,
        UUID vendorPartnerId,
        UUID originTerminalId,
        UUID destinationTerminalId,
        String vehicleType,
        String currency,
        LocalDate effectiveFrom,
        /** Inclusive last day, or null while this is the version in force. */
        LocalDate effectiveTo,
        BigDecimal detentionFreeHours,
        BigDecimal detentionHourlyRate,
        BigDecimal additionalDropFee,
        BigDecimal minimumCharge,
        @Version Long version,
        Instant createdAt) {

    public static Tariff publish(UUID id, UUID tenantId, UUID orgUnitId, String code,
                                 UUID vendorPartnerId, UUID originTerminalId,
                                 UUID destinationTerminalId, String vehicleType,
                                 String currency, LocalDate effectiveFrom,
                                 BigDecimal detentionFreeHours, BigDecimal detentionHourlyRate,
                                 BigDecimal additionalDropFee, BigDecimal minimumCharge) {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("A rate card needs a name somebody can refer to");
        }
        if (effectiveFrom == null) {
            throw new IllegalArgumentException("A rate card must say when it starts applying");
        }
        return new Tariff(id, tenantId, orgUnitId, code, vendorPartnerId, originTerminalId,
                destinationTerminalId, vehicleType,
                currency == null || currency.isBlank() ? "INR" : currency,
                effectiveFrom, null,
                orZero(detentionFreeHours), orZero(detentionHourlyRate),
                orZero(additionalDropFee), orZero(minimumCharge),
                null, Instant.now());
    }

    /** Whether this version was in force on the given day, both bounds inclusive. */
    public boolean coversOn(LocalDate day) {
        return !day.isBefore(effectiveFrom)
                && (effectiveTo == null || !day.isAfter(effectiveTo));
    }

    public boolean isOpen() {
        return effectiveTo == null;
    }

    /**
     * Closes this version so a successor can take over the following day.
     *
     * <p>The only permitted change to a published card, and the database
     * refuses every other one. Closing it before it started would leave a lane
     * with no rate at all for the days in between, which reads downstream as
     * "this movement is unpriceable" rather than as the mistake it is.
     */
    public Tariff closeOn(LocalDate lastDay) {
        if (effectiveTo != null) {
            throw new BusinessRuleViolationException("tariff-already-closed",
                    "Rate card " + code + " was already closed on " + effectiveTo);
        }
        if (lastDay.isBefore(effectiveFrom)) {
            throw new BusinessRuleViolationException("tariff-closed-before-it-began",
                    "Rate card " + code + " starts on " + effectiveFrom
                            + " and cannot be closed on " + lastDay);
        }
        return new Tariff(id, tenantId, orgUnitId, code, vendorPartnerId, originTerminalId,
                destinationTerminalId, vehicleType, currency, effectiveFrom, lastDay,
                detentionFreeHours, detentionHourlyRate, additionalDropFee, minimumCharge,
                version, createdAt);
    }

    private static BigDecimal orZero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
