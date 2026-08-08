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
import java.math.RoundingMode;
import java.time.Instant;
import java.util.UUID;

import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.relational.core.mapping.Table;

/**
 * One weight band of a rate card.
 *
 * <p>Bands are half-open -- {@code [min, max)} -- so a consignment weighing
 * exactly the boundary falls in the upper band and only there. Closed
 * intervals would make every round number ambiguous, and the ambiguity would
 * surface for the first time as an argument about an invoice rather than as a
 * failing test.
 */
@Table("tariff_slab")
public record TariffSlab(
        @Id UUID id,
        UUID tenantId,
        UUID tariffId,
        BigDecimal minWeightKg,
        /** Null is the open top band: everything heavier than {@code minWeightKg}. */
        BigDecimal maxWeightKg,
        RateBasis rateBasis,
        BigDecimal rateAmount,
        @Version Long version,
        Instant createdAt) {

    public enum RateBasis {
        /** Multiplied by the chargeable weight. */
        PER_KG,
        /** The whole vehicle, whatever is aboard. */
        FLAT
    }

    public static TariffSlab of(UUID id, UUID tenantId, UUID tariffId, BigDecimal minWeightKg,
                                BigDecimal maxWeightKg, RateBasis rateBasis,
                                BigDecimal rateAmount) {
        if (minWeightKg == null || minWeightKg.signum() < 0) {
            throw new IllegalArgumentException("A weight band starts at zero or above");
        }
        if (maxWeightKg != null && maxWeightKg.compareTo(minWeightKg) <= 0) {
            throw new IllegalArgumentException(
                    "A weight band must end above where it begins");
        }
        if (rateAmount == null || rateAmount.signum() < 0) {
            throw new IllegalArgumentException("A rate cannot be negative");
        }
        return new TariffSlab(id, tenantId, tariffId, minWeightKg, maxWeightKg,
                rateBasis, rateAmount, null, Instant.now());
    }

    public boolean covers(BigDecimal weightKg) {
        return weightKg.compareTo(minWeightKg) >= 0
                && (maxWeightKg == null || weightKg.compareTo(maxWeightKg) < 0);
    }

    /** Whether this band and another would both claim some weight. */
    public boolean overlaps(BigDecimal otherMin, BigDecimal otherMax) {
        boolean startsAfterOtherEnds = otherMax != null && minWeightKg.compareTo(otherMax) >= 0;
        boolean endsBeforeOtherStarts = maxWeightKg != null && maxWeightKg.compareTo(otherMin) <= 0;
        return !startsAfterOtherEnds && !endsBeforeOtherStarts;
    }

    /** The linehaul charge for a weight this band covers. */
    public BigDecimal chargeFor(BigDecimal weightKg) {
        BigDecimal raw = rateBasis == RateBasis.FLAT
                ? rateAmount
                : rateAmount.multiply(weightKg);
        return raw.setScale(2, RoundingMode.HALF_UP);
    }
}
