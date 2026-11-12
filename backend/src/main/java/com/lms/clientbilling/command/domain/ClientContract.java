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
 * version that is in force is never edited -- the database refuses it -- and is
 * corrected by publishing a successor, which closes it the day before. Before
 * that it is a draft its maker may edit or delete, then frozen while it waits
 * for verification; the only change an active version accepts is an approved
 * extension of its end date.
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
        Instant createdAt,
        /**
         * When the client may be invoiced for a trip's transport: false (the
         * default) once each of its consignments on the trip has a confirmed
         * proof of delivery; true as soon as the trip is dispatched.
         */
        boolean billOnDispatch,
        /** Where it is in its life; see {@link Status}. */
        Status status,
        /**
         * The last day the contract applies, or null for no end date. Moved
         * later only by an approved extension. Not the same as
         * {@link #effectiveTo}, which a successor sets.
         */
        LocalDate validTill,
        /** Why verification was last refused, while the contract is back in DRAFT. */
        String rejectionReason) {

    /**
     * Where a version is in its life. {@code EXPIRED} is not stored: it is
     * {@link #stateOn} for an active version past its end date.
     */
    public enum Status {
        /** Being written: editable, deletable, applies to nothing. */
        DRAFT,
        /** Asked to be verified: frozen until somebody decides. */
        PENDING_VERIFICATION,
        /** Verified: immutable, and in force from its start date. */
        ACTIVE
    }

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
        return publish(id, tenantId, orgUnitId, code, clientPartnerId, currency, effectiveFrom,
                rates, false);
    }

    /**
     * As the other {@code publish}, saying whether transport is billed on dispatch.
     * The version is ACTIVE at once: the in-process path of tests and imports; over
     * HTTP a contract is drafted and verified.
     */
    public static ClientContract publish(UUID id, UUID tenantId, UUID orgUnitId, String code,
                                         UUID clientPartnerId, String currency,
                                         LocalDate effectiveFrom, Rates rates,
                                         boolean billOnDispatch) {
        return build(id, tenantId, orgUnitId, code, clientPartnerId, currency, effectiveFrom, null, rates,
                billOnDispatch, Status.ACTIVE);
    }

    /** A new version, still being written. {@code validTill} may be null. */
    public static ClientContract draft(UUID id, UUID tenantId, UUID orgUnitId, String code,
                                       UUID clientPartnerId, String currency, LocalDate effectiveFrom,
                                       LocalDate validTill, Rates rates, boolean billOnDispatch) {
        return build(id, tenantId, orgUnitId, code, clientPartnerId, currency, effectiveFrom, validTill, rates,
                billOnDispatch, Status.DRAFT);
    }

    private static ClientContract build(UUID id, UUID tenantId, UUID orgUnitId, String code,
                                        UUID clientPartnerId, String currency, LocalDate effectiveFrom,
                                        LocalDate validTill, Rates rates, boolean billOnDispatch, Status status) {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("A client contract needs a name somebody can refer to");
        }
        if (effectiveFrom == null) {
            throw new IllegalArgumentException("A client contract must say when it starts applying");
        }
        requireValidity(effectiveFrom, validTill);
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
                null, Instant.now(), billOnDispatch, status, validTill, null);
    }

    /** The end date may not come before the start date. */
    public static void requireValidity(LocalDate effectiveFrom, LocalDate validTill) {
        if (validTill != null && effectiveFrom != null && validTill.isBefore(effectiveFrom)) {
            throw new BusinessRuleViolationException("client-contract-validity-invalid",
                    "A contract starting on " + effectiveFrom + " cannot end on " + validTill);
        }
    }

    /**
     * Whether this version was in force on the given day, both bounds inclusive:
     * verified, started, not closed by a successor and not past its end date.
     */
    public boolean coversOn(LocalDate day) {
        return status == Status.ACTIVE
                && !day.isBefore(effectiveFrom)
                && (effectiveTo == null || !day.isAfter(effectiveTo))
                && (validTill == null || !day.isAfter(validTill));
    }

    /**
     * The state to show on {@code today}: the stored status, or {@code EXPIRED}
     * for a version in force whose end date has passed. Derived on every
     * read, so there is no job to run and nothing to go stale.
     */
    public String stateOn(LocalDate today) {
        return status == Status.ACTIVE && validTill != null && validTill.isBefore(today)
                ? "EXPIRED" : status.name();
    }

    /** Replaces the content of a draft; refused once it has been submitted. */
    public ClientContract revised(UUID newOrgUnitId, String newCode, String newCurrency,
                                  LocalDate newEffectiveFrom, LocalDate newValidTill, Rates rates,
                                  boolean newBillOnDispatch) {
        requireDraft();
        ClientContract fresh = build(id, tenantId, newOrgUnitId, newCode, clientPartnerId, newCurrency,
                newEffectiveFrom, newValidTill, rates, newBillOnDispatch, Status.DRAFT);
        return new ClientContract(id, tenantId, fresh.orgUnitId, fresh.code, clientPartnerId, fresh.currency,
                fresh.effectiveFrom, null, fresh.transportRatePerKg, fresh.transportMinimumCharge,
                fresh.storageRatePerPalletDay, fresh.handlingInRatePerPallet, fresh.handlingOutRatePerPallet,
                fresh.transportTaxRatePct, fresh.warehouseTaxRatePct, fresh.storageUnitsPerPallet, version,
                createdAt, fresh.billOnDispatch, Status.DRAFT, fresh.validTill, rejectionReason);
    }

    /** The draft the caller says it read at {@code expectedVersion}. */
    public ClientContract expecting(Long expectedVersion) {
        return copy(status, effectiveTo, validTill, rejectionReason, expectedVersion);
    }

    /** DRAFT to PENDING_VERIFICATION, clearing the last refusal. */
    public ClientContract submitted() {
        requireDraft();
        return copy(Status.PENDING_VERIFICATION, effectiveTo, validTill, null, version);
    }

    /** PENDING_VERIFICATION to ACTIVE; a draft may also be activated directly when no verification is asked. */
    public ClientContract activated() {
        if (status == Status.ACTIVE) {
            throw new BusinessRuleViolationException("client-contract-not-pending",
                    "Client contract " + code + " is already in force");
        }
        return copy(Status.ACTIVE, null, validTill, null, version);
    }

    /** Verification came to nothing: back to DRAFT, with the reason. */
    public ClientContract returnedToDraft(String reason) {
        if (status != Status.PENDING_VERIFICATION) {
            throw new BusinessRuleViolationException("client-contract-not-pending",
                    "Client contract " + code + " is " + status + ", not awaiting verification");
        }
        return copy(Status.DRAFT, effectiveTo, validTill, reason, version);
    }

    /** Draft only: the one state in which content may change or the row be deleted. */
    public void requireDraft() {
        if (status != Status.DRAFT) {
            throw new BusinessRuleViolationException("client-contract-not-draft",
                    "Client contract " + code + " is " + status + " and can no longer be edited");
        }
    }

    /**
     * Whether this version may have its end date moved to {@code newValidTill}:
     * in force, with an end date to move (an open-ended contract has nothing to
     * extend), not already closed by a successor, and moved later.
     */
    public void requireExtendableTo(LocalDate newValidTill) {
        if (status != Status.ACTIVE) {
            throw new BusinessRuleViolationException("client-contract-not-active",
                    "Client contract " + code + " is " + status + "; only a contract in force is extended");
        }
        if (validTill == null) {
            throw new BusinessRuleViolationException("client-contract-no-end-date",
                    "Client contract " + code + " has no end date to extend");
        }
        if (effectiveTo != null) {
            throw new BusinessRuleViolationException("client-contract-already-closed",
                    "Client contract " + code + " was closed on " + effectiveTo
                            + " by its successor; extend the successor");
        }
        if (newValidTill == null || !newValidTill.isAfter(validTill)) {
            throw new BusinessRuleViolationException("client-contract-extension-not-later",
                    "Client contract " + code + " ends on " + validTill + "; an extension must end later");
        }
    }

    private ClientContract copy(Status newStatus, LocalDate newEffectiveTo, LocalDate newValidTill,
                                String newRejection, Long newVersion) {
        return new ClientContract(id, tenantId, orgUnitId, code, clientPartnerId, currency, effectiveFrom,
                newEffectiveTo, transportRatePerKg, transportMinimumCharge, storageRatePerPalletDay,
                handlingInRatePerPallet, handlingOutRatePerPallet, transportTaxRatePct, warehouseTaxRatePct,
                storageUnitsPerPallet, newVersion, createdAt, billOnDispatch, newStatus, newValidTill,
                newRejection);
    }

    /**
     * A successor version of this one, in force at once, starting on {@code from}
     * with the same terms: the carrier of an added charge, whose rate is
     * written beside it.
     */
    public ClientContract successor(UUID newId, LocalDate from) {
        return new ClientContract(newId, tenantId, orgUnitId, code, clientPartnerId, currency, from, null,
                transportRatePerKg, transportMinimumCharge, storageRatePerPalletDay, handlingInRatePerPallet,
                handlingOutRatePerPallet, transportTaxRatePct, warehouseTaxRatePct, storageUnitsPerPallet, null,
                Instant.now(), billOnDispatch, Status.ACTIVE, validTill, null);
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
        return copy(status, lastDay, validTill, rejectionReason, version);
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
