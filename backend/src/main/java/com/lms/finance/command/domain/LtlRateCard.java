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
import java.util.Locale;
import java.util.UUID;

import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.relational.core.mapping.Table;

/**
 * One version of the part-load price between two zones.
 *
 * <p>The customer-facing counterpart of {@link Tariff}. A tariff prices a
 * vehicle for a carrier; this prices a docket for a customer, and the two are
 * never the same number -- the difference between what the dockets on a lorry
 * earn and what the lorry costs is the margin consolidation exists to make.
 *
 * <p>Immutable, like a tariff, and for the same reason: a docket priced in
 * January must be answerable with January's card. The version in force on a
 * day is the most recent one to have started by then, so a successor takes
 * over without its predecessor being touched at all; the database refuses
 * updates and deletes outright.
 */
@Table("ltl_rate_card")
public record LtlRateCard(
        @Id UUID id,
        UUID tenantId,
        String code,
        String originZone,
        String destinationZone,
        String currency,
        LocalDate effectiveFrom,
        RateBasis rateBasis,
        BigDecimal rateAmount,
        /**
         * The weight below which a docket is not billed, however light it is.
         * Handling a 3 kg parcel through two hubs costs nearly what handling
         * 30 kg does.
         */
        BigDecimal minimumChargeableWeightKg,
        /**
         * The volumetric conversion: each cubic foot a docket occupies is
         * billed as this many kilograms. Part-load space is sold by the cubic
         * foot because a lorry fills with light bulky freight long before it
         * reaches its axle limit.
         */
        BigDecimal kgPerCft,
        /** A flat charge per docket, for the paperwork and the hub handling. */
        BigDecimal docketCharge,
        /** Added when the consignee's site is out of the destination hub's delivery area. */
        BigDecimal odaSurcharge,
        @Version Long version,
        Instant createdAt) {

    public enum RateBasis {
        /** The rate multiplies the chargeable weight in kilograms. */
        PER_KG,
        /**
         * The rate multiplies the chargeable weight expressed in cubic feet,
         * at the card's own {@code kgPerCft}.
         */
        PER_CFT
    }

    public static LtlRateCard publish(UUID id, UUID tenantId, String code, String originZone,
                                      String destinationZone, String currency,
                                      LocalDate effectiveFrom, RateBasis rateBasis,
                                      BigDecimal rateAmount, BigDecimal minimumChargeableWeightKg,
                                      BigDecimal kgPerCft, BigDecimal docketCharge,
                                      BigDecimal odaSurcharge) {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("A rate card needs a name somebody can refer to");
        }
        if (effectiveFrom == null) {
            throw new IllegalArgumentException("A rate card must say when it starts applying");
        }
        if (rateBasis == null) {
            throw new IllegalArgumentException("A rate card must say what its rate multiplies");
        }
        if (rateAmount == null || rateAmount.signum() < 0) {
            throw new IllegalArgumentException("A rate cannot be negative");
        }
        if (kgPerCft == null || kgPerCft.signum() <= 0) {
            throw new IllegalArgumentException(
                    "The volumetric conversion must be positive, or volume would be free");
        }
        return new LtlRateCard(id, tenantId, code, zone(originZone), zone(destinationZone),
                currency == null || currency.isBlank() ? "INR" : currency,
                effectiveFrom, rateBasis, rateAmount, nonNegative(minimumChargeableWeightKg),
                kgPerCft, nonNegative(docketCharge), nonNegative(odaSurcharge),
                null, Instant.now());
    }

    /**
     * Zone codes are compared exactly, so they are normalised once, here and in
     * the zone register. "west" and "WEST " being two zones would make a lane
     * unpriceable for a reason nobody could see on screen.
     */
    public static String zone(String code) {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("A zone code is mandatory");
        }
        return code.trim().toUpperCase(Locale.ROOT);
    }

    private static BigDecimal nonNegative(BigDecimal value) {
        if (value == null) {
            return BigDecimal.ZERO;
        }
        if (value.signum() < 0) {
            throw new IllegalArgumentException("A charge cannot be negative");
        }
        return value;
    }
}
