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
 */package com.lms.finance.command;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.lms.finance.command.domain.FuelSurchargeIndex;
import com.lms.finance.command.domain.Tariff;
import com.lms.finance.command.domain.TariffSlab;
import com.lms.shared.audit.AuditTrail;
import com.lms.shared.config.Json;
import com.lms.shared.error.BusinessRuleViolationException;
import com.lms.shared.error.ResourceNotFoundException;
import com.lms.shared.tenant.TenantContext;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Publishing rate cards (vision document 3.9.1).
 *
 * <p>Guarded by {@code TARIFF_MANAGE} rather than {@code INVOICE_APPROVE}.
 * Whoever decides what a lane costs should not also be the person who certifies
 * that the vendor's bill matches it -- that is the oldest separation of duties
 * there is, and collapsing the two permissions would make the tolerance check
 * in {@link BillingService} something one person could arrange the answer to.
 */
@Service
public class TariffCommandService {

    private final TariffRepository tariffs;
    private final TariffSlabRepository slabs;
    private final FuelSurchargeIndexRepository fuelIndex;
    private final AuditTrail audit;

    public TariffCommandService(TariffRepository tariffs, TariffSlabRepository slabs,
                                FuelSurchargeIndexRepository fuelIndex, AuditTrail audit) {
        this.tariffs = tariffs;
        this.slabs = slabs;
        this.fuelIndex = fuelIndex;
        this.audit = audit;
    }

    /**
     * Publishes a new version of a lane's rate card, closing the incumbent.
     *
     * <p>Closing the incumbent on the day before the successor starts is what
     * keeps "the rate in force on a date" answerable. Doing it in the same
     * transaction matters as much as doing it at all: the partial unique index
     * permits one open version per lane, so a crash between the two writes
     * cannot leave two.
     */
    @Transactional
    @PreAuthorize("hasAuthority('TARIFF_MANAGE')")
    public UUID publishTariff(UUID orgUnitId, String code, UUID vendorPartnerId,
                              UUID originTerminalId, UUID destinationTerminalId,
                              String vehicleType, String currency, LocalDate effectiveFrom,
                              BigDecimal detentionFreeHours, BigDecimal detentionHourlyRate,
                              BigDecimal additionalDropFee, BigDecimal minimumCharge) {
        UUID tenantId = TenantContext.requireTenantId();

        tariffs.findOpenVersion(tenantId, vendorPartnerId, originTerminalId,
                destinationTerminalId, vehicleType).ifPresent(incumbent -> {
            if (!effectiveFrom.isAfter(incumbent.effectiveFrom())) {
                // Backdating under a live card would create a period with two
                // rates in force, and pricing would then depend on which row
                // the query happened to reach first.
                throw new BusinessRuleViolationException("tariff-backdated",
                        "Rate card " + incumbent.code() + " has been in force since "
                                + incumbent.effectiveFrom() + "; a successor cannot start on "
                                + effectiveFrom);
            }
            tariffs.save(incumbent.closeOn(effectiveFrom.minusDays(1)));
        });

        Tariff published = tariffs.save(Tariff.publish(UUID.randomUUID(), tenantId, orgUnitId,
                code, vendorPartnerId, originTerminalId, destinationTerminalId, vehicleType,
                currency, effectiveFrom, detentionFreeHours, detentionHourlyRate,
                additionalDropFee, minimumCharge));

        // Rate changes are the mutations an auditor asks about first.
        audit.record("TARIFF_PUBLISHED", "TARIFF", published.id(), null,
                Json.object("code", code, "effectiveFrom", effectiveFrom.toString(),
                        "vehicleType", vehicleType));

        return published.id();
    }

    /**
     * Adds a weight band to a card that has not yet been superseded.
     *
     * <p>Refused once the card is closed. A closed card has priced movements,
     * and adding a band to it would change what those movements cost without
     * touching a single bill -- the reprice would be invisible until somebody
     * recomputed a figure and got a different answer.
     */
    @Transactional
    @PreAuthorize("hasAuthority('TARIFF_MANAGE')")
    public UUID addSlab(UUID tariffId, BigDecimal minWeightKg, BigDecimal maxWeightKg,
                        TariffSlab.RateBasis rateBasis, BigDecimal rateAmount) {
        UUID tenantId = TenantContext.requireTenantId();

        Tariff tariff = tariffs.findById(tariffId)
                .orElseThrow(() -> new ResourceNotFoundException("Tariff", tariffId));

        if (!tariff.isOpen()) {
            throw new BusinessRuleViolationException("tariff-closed",
                    "Rate card " + tariff.code() + " was superseded on " + tariff.effectiveTo()
                            + " and cannot gain new weight bands");
        }

        TariffSlab candidate = TariffSlab.of(UUID.randomUUID(), tenantId, tariffId,
                minWeightKg, maxWeightKg, rateBasis, rateAmount);

        List<TariffSlab> existing = slabs.findForTariff(tenantId, tariffId);
        existing.stream()
                .filter(slab -> slab.overlaps(candidate.minWeightKg(), candidate.maxWeightKg()))
                .findFirst()
                .ifPresent(clash -> {
                    throw new BusinessRuleViolationException("slab-overlaps",
                            "A band from " + clash.minWeightKg() + " kg already covers part of "
                                    + "the range being added; two bands claiming one weight "
                                    + "would price the same load differently depending on "
                                    + "which was read first");
                });

        return slabs.save(candidate).id();
    }

    /**
     * Records the fuel surcharge percentage for a month.
     *
     * <p>Correctable, unlike a rate card, and safely so: a bill stores the
     * surcharge <em>amount</em> it was priced with, so a later correction
     * changes what the next trip costs and nothing that has already been
     * billed.
     */
    @Transactional
    @PreAuthorize("hasAuthority('TARIFF_MANAGE')")
    public UUID recordFuelSurcharge(LocalDate anyDayInMonth, BigDecimal surchargePct) {
        UUID tenantId = TenantContext.requireTenantId();
        LocalDate month = anyDayInMonth.withDayOfMonth(1);

        return fuelIndex.findForMonth(tenantId, month)
                .map(existing -> fuelIndex.save(existing.correctTo(surchargePct)).id())
                .orElseGet(() -> fuelIndex.save(FuelSurchargeIndex.of(UUID.randomUUID(),
                        tenantId, month, surchargePct)).id());
    }
}
