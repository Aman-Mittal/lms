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
package com.lms.finance.command;

import java.math.BigDecimal;
import java.math.RoundingMode;

import com.lms.finance.command.domain.LtlQuote;
import com.lms.finance.command.domain.LtlRateCard;
import org.springframework.stereotype.Component;

/**
 * Prices one part-load docket against a zone-to-zone rate card.
 *
 * <p>Free of any database, clock or tenant, like {@link RatingEngine}: the
 * answer is a pure function of the card and the docket, so it is reproducible
 * when the customer queries it months later.
 *
 * <p>The rule, in order:
 * <ol>
 *   <li>Volume in cubic metres is converted to cubic feet, and cubic feet to a
 *       volumetric weight at the card's kilograms per cubic foot.</li>
 *   <li>The chargeable weight is the greatest of the dead weight, the
 *       volumetric weight and the card's minimum.</li>
 *   <li>Freight is the rate times the chargeable weight -- in kilograms for a
 *       per-kg card, or converted back to cubic feet for a per-CFT one.</li>
 *   <li>The docket charge is added, and the ODA surcharge when the consignee's
 *       site is out of the delivery area: the card's flat amount, or its rate
 *       per kg on the chargeable weight lifted to the card's ODA minimum.</li>
 *   <li>The fuel surcharge is the month's percentage of the freight amount
 *       alone. Fuel moves the cost of moving the freight; the docket charge
 *       pays for paperwork and the ODA charge is already its own price, so
 *       surcharging them would charge fuel twice.</li>
 * </ol>
 */
@Component
public class LtlRatingEngine {

    /** Cubic feet in one cubic metre. */
    static final BigDecimal CFT_PER_M3 = new BigDecimal("35.3147");

    private static final int WEIGHT_SCALE = 3;
    private static final int MONEY_SCALE = 2;
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    /** A quote with no fuel surcharge. */
    public LtlQuote quote(LtlRateCard card, BigDecimal deadWeightKg, BigDecimal volumeM3,
                          boolean outOfDeliveryArea) {
        return quote(card, deadWeightKg, volumeM3, outOfDeliveryArea, null);
    }

    /**
     * @param fuelSurchargePct the fuel index for the month the docket was
     *                         booked in, or null when none was recorded --
     *                         which charges no fuel rather than refusing to
     *                         price
     */
    public LtlQuote quote(LtlRateCard card, BigDecimal deadWeightKg, BigDecimal volumeM3,
                          boolean outOfDeliveryArea, BigDecimal fuelSurchargePct) {
        BigDecimal dead = deadWeightKg == null ? BigDecimal.ZERO : deadWeightKg;
        BigDecimal volume = volumeM3 == null ? BigDecimal.ZERO : volumeM3;

        BigDecimal volumeCft = volume.multiply(CFT_PER_M3).setScale(WEIGHT_SCALE, RoundingMode.HALF_UP);
        BigDecimal volumetricKg = volumeCft.multiply(card.kgPerCft())
                .setScale(WEIGHT_SCALE, RoundingMode.HALF_UP);

        BigDecimal measured = dead.max(volumetricKg);
        boolean minimumApplied = card.minimumChargeableWeightKg().compareTo(measured) > 0;
        BigDecimal chargeableKg = measured.max(card.minimumChargeableWeightKg())
                .setScale(WEIGHT_SCALE, RoundingMode.HALF_UP);

        BigDecimal billedQuantity = card.rateBasis() == LtlRateCard.RateBasis.PER_CFT
                ? chargeableKg.divide(card.kgPerCft(), WEIGHT_SCALE, RoundingMode.HALF_UP)
                : chargeableKg;
        BigDecimal freight = money(card.rateAmount().multiply(billedQuantity));

        BigDecimal oda = outOfDeliveryArea ? oda(card, chargeableKg) : money(BigDecimal.ZERO);
        BigDecimal fuel = fuelSurchargePct == null
                ? money(BigDecimal.ZERO)
                : money(freight.multiply(fuelSurchargePct).divide(HUNDRED));

        return new LtlQuote(volumeCft, volumetricKg, chargeableKg, minimumApplied,
                outOfDeliveryArea, freight, money(card.docketCharge()), oda,
                outOfDeliveryArea ? card.odaBasis() : null, fuelSurchargePct, fuel);
    }

    /**
     * The out-of-delivery-area charge on a docket that earns one.
     *
     * <p>Per kg, it is reckoned on the chargeable weight -- the same weight the
     * freight is billed on -- so a bulky light docket pays for the space it
     * takes on the last stretch as it did on the line haul.
     */
    private static BigDecimal oda(LtlRateCard card, BigDecimal chargeableKg) {
        if (card.odaBasis() != LtlRateCard.OdaBasis.PER_KG) {
            return money(card.odaSurcharge());
        }
        return money(card.odaRatePerKg().multiply(chargeableKg)).max(money(card.odaMinimum()));
    }

    private static BigDecimal money(BigDecimal value) {
        return value.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }
}
