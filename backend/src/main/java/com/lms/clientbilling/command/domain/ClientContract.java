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
package com.lms.clientbilling.command.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import com.lms.shared.error.BusinessRuleViolationException;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.relational.core.mapping.Table;

/**
 * One version of what a client pays for transport and warehousing.
 *
 * <p>Versioned for the same reason a carrier rate card is: an invoice disputed
 * in March must be argued from the contract that applied in January. A
 * published version is never edited -- the database refuses it -- and is
 * corrected by publishing a successor, which closes it the day before.
 */
@Table("client_contract")
public record ClientContract(
        @Id UUID id,
        UUID tenantId,
        UUID orgUnitId,
        String code,
        UUID clientPartnerId,
        String currency,
        LocalDate effectiveFrom,
        /** Inclusive last day, or null while this is the version in force. */
        LocalDate effectiveTo,
        BigDecimal transportRatePerKg,
        BigDecimal transportMinimumCharge,
        BigDecimal storageRatePerPalletDay,
        BigDecimal handlingInRatePerPallet,
        BigDecimal handlingOutRatePerPallet,
        @Version Long version,
        Instant createdAt) {

    /** The rates a contract is published with. */
    public record Rates(BigDecimal transportRatePerKg, BigDecimal transportMinimumCharge,
                        BigDecimal storageRatePerPalletDay, BigDecimal handlingInRatePerPallet,
                        BigDecimal handlingOutRatePerPallet) {
    }

    public static ClientContract publish(UUID id, UUID tenantId, UUID orgUnitId, String code,
                                         UUID clientPartnerId, String currency,
                                         LocalDate effectiveFrom, Rates rates) {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("A client contract needs a name somebody can refer to");
        }
        if (effectiveFrom == null) {
            throw new IllegalArgumentException("A client contract must say when it starts applying");
        }
        return new ClientContract(id, tenantId, orgUnitId, code, clientPartnerId,
                currency == null || currency.isBlank() ? "INR" : currency,
                effectiveFrom, null,
                nonNegative(rates.transportRatePerKg(), "transport rate"),
                nonNegative(rates.transportMinimumCharge(), "transport minimum charge"),
                nonNegative(rates.storageRatePerPalletDay(), "storage rate"),
                nonNegative(rates.handlingInRatePerPallet(), "handling-in rate"),
                nonNegative(rates.handlingOutRatePerPallet(), "handling-out rate"),
                null, Instant.now());
    }

    /** Whether this version was in force on the given day, both bounds inclusive. */
    public boolean coversOn(LocalDate day) {
        return !day.isBefore(effectiveFrom)
                && (effectiveTo == null || !day.isAfter(effectiveTo));
    }

    /**
     * The per-unit rate for a fixed-unit service.
     *
     * @throws IllegalArgumentException for {@link ServiceType#VALUE_ADDED},
     *         whose rate lives in the contract's service-rate list
     */
    public BigDecimal rateFor(ServiceType type) {
        return switch (type) {
            case TRANSPORT -> transportRatePerKg;
            case STORAGE -> storageRatePerPalletDay;
            case HANDLING_IN -> handlingInRatePerPallet;
            case HANDLING_OUT -> handlingOutRatePerPallet;
            case VALUE_ADDED -> throw new IllegalArgumentException(
                    "A value-added service is priced from the contract's service rates");
        };
    }

    /**
     * Closes this version so a successor can take over the following day --
     * the only change the database permits.
     */
    public ClientContract closeOn(LocalDate lastDay) {
        if (effectiveTo != null) {
            throw new BusinessRuleViolationException("client-contract-already-closed",
                    "Client contract " + code + " was already closed on " + effectiveTo);
        }
        if (lastDay.isBefore(effectiveFrom)) {
            throw new BusinessRuleViolationException("client-contract-backdated",
                    "Client contract " + code + " has been in force since " + effectiveFrom
                            + "; a successor cannot start on " + lastDay.plusDays(1));
        }
        return new ClientContract(id, tenantId, orgUnitId, code, clientPartnerId, currency,
                effectiveFrom, lastDay, transportRatePerKg, transportMinimumCharge,
                storageRatePerPalletDay, handlingInRatePerPallet, handlingOutRatePerPallet,
                version, createdAt);
    }

    private static BigDecimal nonNegative(BigDecimal value, String what) {
        if (value == null) {
            return BigDecimal.ZERO;
        }
        if (value.signum() < 0) {
            throw new IllegalArgumentException("The " + what + " cannot be negative");
        }
        return value;
    }
}
