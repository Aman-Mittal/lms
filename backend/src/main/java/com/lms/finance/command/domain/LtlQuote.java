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

/**
 * The arithmetic behind one docket's price, before it becomes a receivable.
 *
 * <p>Like {@link CostSheet}, the total is the sum of its parts rather than a
 * separately computed figure; the database holds the receivable to the same
 * rule with a CHECK constraint.
 *
 * @param minimumApplied   true when the card's minimum, not the docket, set the
 *                         chargeable weight -- shown so a customer billed for
 *                         50 kg on a 12 kg parcel can see why
 * @param odaBasis         how the ODA charge was reckoned; null when the docket
 *                         was not out of the delivery area and none was charged
 * @param fuelSurchargePct the month's fuel index the freight was surcharged at,
 *                         or null when the month had none
 * @param fuelSurcharge    that percentage of the freight amount; zero when
 *                         there was no index
 */
public record LtlQuote(
        BigDecimal volumeCft,
        BigDecimal volumetricWeightKg,
        BigDecimal chargeableWeightKg,
        boolean minimumApplied,
        boolean outOfDeliveryArea,
        BigDecimal freightAmount,
        BigDecimal docketCharge,
        BigDecimal odaSurcharge,
        LtlRateCard.OdaBasis odaBasis,
        BigDecimal fuelSurchargePct,
        BigDecimal fuelSurcharge) {

    public BigDecimal total() {
        return freightAmount.add(docketCharge).add(odaSurcharge).add(fuelSurcharge);
    }
}
