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
        /** Tax on transport lines, as a percentage. */
        BigDecimal transportTaxRatePct,
        /** Tax on storage, handling and value-added service lines, as a percentage. */
        BigDecimal warehouseTaxRatePct,
        /**
         * Units of the client's SKUs that make one pallet position, for storage
         * computed from the warehouse ledger.
         */
        int storageUnitsPerPallet,
        @Version Long version,
        Instant createdAt) {

    /**
     * The rates a contract is published with.
     *
     * @param transportTaxRatePct   tax on transport, percent; null is none
     * @param warehouseTaxRatePct   tax on warehousing and value-added services,
     *                              percent; null is none
     * @param storageUnitsPerPallet units per pallet position for ledger-derived
     *                              storage; null is one
     */
    public record Rates(BigDecimal transportRatePerKg, BigDecimal transportMinimumCharge,
                        BigDecimal storageRatePerPalletDay, BigDecimal handlingInRatePerPallet,
                        BigDecimal handlingOutRatePerPallet, BigDecimal transportTaxRatePct,
                        BigDecimal warehouseTaxRatePct, Integer storageUnitsPerPallet) {

        /** Untaxed, one unit to the pallet. */
        public Rates(BigDecimal transportRatePerKg, BigDecimal transportMinimumCharge,
                     BigDecimal storageRatePerPalletDay, BigDecimal handlingInRatePerPallet,
                     BigDecimal handlingOutRatePerPallet) {
            this(transportRatePerKg, transportMinimumCharge, storageRatePerPalletDay,
                    handlingInRatePerPallet, handlingOutRatePerPallet, null, null, null);
        }
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
                percentage(rates.transportTaxRatePct(), "transport tax rate"),
                percentage(rates.warehouseTaxRatePct(), "warehouse tax rate"),
                unitsPerPallet(rates.storageUnitsPerPallet()),
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
     * The tax rate, in percent, for a line of this service type: the transport
     * rate for movements, the warehouse rate for everything else.
     */
    public BigDecimal taxRateFor(ServiceType type) {
        return type == ServiceType.TRANSPORT ? transportTaxRatePct : warehouseTaxRatePct;
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
                transportTaxRatePct, warehouseTaxRatePct, storageUnitsPerPallet, version,
                createdAt);
    }

    private static BigDecimal percentage(BigDecimal value, String what) {
        BigDecimal rate = nonNegative(value, what);
        if (rate.compareTo(BigDecimal.valueOf(100)) > 0) {
            throw new IllegalArgumentException("The " + what + " is a percentage; "
                    + rate.toPlainString() + " is more than 100");
        }
        if (rate.stripTrailingZeros().scale() > 3) {
            throw new IllegalArgumentException("The " + what + " carries at most three decimals");
        }
        return rate;
    }

    private static int unitsPerPallet(Integer value) {
        if (value == null) {
            return 1;
        }
        if (value <= 0) {
            throw new IllegalArgumentException("A pallet holds at least one unit");
        }
        return value;
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
