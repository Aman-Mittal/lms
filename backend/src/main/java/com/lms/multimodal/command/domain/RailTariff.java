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
package com.lms.multimodal.command.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import com.lms.shared.error.BusinessRuleViolationException;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.relational.core.mapping.Table;

/**
 * One version of one rail carrier's tariff between two railheads.
 *
 * <p>Versioned for the reason the road tariff is: a rail leg planned in
 * January is priced with January's card, and must still be explicable from
 * it after the railway revised its rates in February. So a published card is
 * never edited -- the database refuses it -- and the one permitted change is
 * closing it on the day before a successor starts.
 *
 * <p>Priced per unit booked, wagon or container, with a floor: a booking of
 * two containers still takes a slot on a rake. A card may price only one
 * unit type; a booking in the other is refused rather than guessed at.
 */
@Table("rail_tariff")
public record RailTariff(
        @Id UUID id,
        UUID tenantId,
        String code,
        UUID carrierPartnerId,
        UUID originTerminalId,
        UUID destinationTerminalId,
        String currency,
        BigDecimal perWagonRate,
        BigDecimal perContainerRate,
        BigDecimal minimumCharge,
        LocalDate effectiveFrom,
        /* Inclusive last day, or null while this is the version in force. */
        LocalDate effectiveTo,
        String createdBy,
        @Version Long version,
        Instant createdAt) {

    /** Checks a card's own shape; whether its ends are railheads is the service's. */
    public static RailTariff publish(UUID id, UUID tenantId, String code, UUID carrierPartnerId,
                                     UUID originTerminalId, UUID destinationTerminalId,
                                     String currency, BigDecimal perWagonRate,
                                     BigDecimal perContainerRate, BigDecimal minimumCharge,
                                     LocalDate effectiveFrom, String createdBy) {
        if (code == null || code.isBlank()) {
            throw new BusinessRuleViolationException("rail-tariff-code-required",
                    "A rail rate card needs a name somebody can refer to");
        }
        if (carrierPartnerId == null || originTerminalId == null || destinationTerminalId == null) {
            throw new BusinessRuleViolationException("rail-tariff-lane-required",
                    "A rail rate card names its carrier and the railheads at each end");
        }
        if (originTerminalId.equals(destinationTerminalId)) {
            throw new BusinessRuleViolationException("rail-tariff-goes-nowhere",
                    "A rail rate card runs between two different railheads");
        }
        if (effectiveFrom == null) {
            throw new BusinessRuleViolationException("rail-tariff-start-required",
                    "A rail rate card must say when it starts applying");
        }
        if (perWagonRate == null && perContainerRate == null) {
            throw new BusinessRuleViolationException("rail-tariff-prices-nothing",
                    "A rail rate card prices wagons, containers or both");
        }
        BigDecimal minimum = minimumCharge == null ? BigDecimal.ZERO : minimumCharge;
        if (negative(perWagonRate) || negative(perContainerRate) || negative(minimum)) {
            throw new BusinessRuleViolationException("rail-tariff-rate-invalid",
                    "A rail rate card's rates and minimum are zero or more");
        }
        return new RailTariff(id, tenantId, code.strip(), carrierPartnerId, originTerminalId,
                destinationTerminalId, currency == null || currency.isBlank() ? "INR" : currency.strip(),
                perWagonRate, perContainerRate, minimum, effectiveFrom, null, createdBy, null,
                Instant.now());
    }

    /** Whether this version was in force on the given day, both bounds inclusive. */
    public boolean coversOn(LocalDate day) {
        return !day.isBefore(effectiveFrom) && (effectiveTo == null || !day.isAfter(effectiveTo));
    }

    public boolean isOpen() {
        return effectiveTo == null;
    }

    /**
     * What a booking costs on this card: the unit rate times the count, and
     * never less than the minimum.
     */
    public BigDecimal priceFor(ItineraryLeg.UnitType unitType, int unitCount) {
        BigDecimal rate = unitType == ItineraryLeg.UnitType.WAGON ? perWagonRate : perContainerRate;
        if (rate == null) {
            throw new BusinessRuleViolationException("rail-tariff-unit-not-priced",
                    "Rail rate card " + code + " has no rate per " + unitType
                            + "; give the leg a cost or publish a card that prices it");
        }
        if (unitCount < 1) {
            throw new BusinessRuleViolationException("rail-leg-needs-capacity",
                    "A rail booking needs at least one " + unitType);
        }
        BigDecimal price = rate.multiply(BigDecimal.valueOf(unitCount))
                .setScale(2, RoundingMode.HALF_UP);
        return price.max(minimumCharge.setScale(2, RoundingMode.HALF_UP));
    }

    /**
     * Closes this version so a successor can take over the following day.
     * Closing before it began would leave days with no card at all.
     */
    public RailTariff closeOn(LocalDate lastDay) {
        if (effectiveTo != null) {
            throw new BusinessRuleViolationException("rail-tariff-already-closed",
                    "Rail rate card " + code + " was already closed on " + effectiveTo);
        }
        if (lastDay.isBefore(effectiveFrom)) {
            throw new BusinessRuleViolationException("rail-tariff-backdated",
                    "Rail rate card " + code + " has been in force since " + effectiveFrom
                            + "; a successor must start after that day");
        }
        return new RailTariff(id, tenantId, code, carrierPartnerId, originTerminalId,
                destinationTerminalId, currency, perWagonRate, perContainerRate, minimumCharge,
                effectiveFrom, lastDay, createdBy, version, createdAt);
    }

    private static boolean negative(BigDecimal value) {
        return value != null && value.signum() < 0;
    }
}
