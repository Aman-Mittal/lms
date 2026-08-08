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
 */package com.lms.finance;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.lms.finance.command.RatingEngine;
import com.lms.finance.command.domain.CostSheet;
import com.lms.finance.command.domain.FreightBillLine.ChargeType;
import com.lms.finance.command.domain.Tariff;
import com.lms.finance.command.domain.TariffSlab;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The boundaries of the rating rules, without a database.
 *
 * <p>{@code finance.feature} proves the rules hold end to end; these prove the
 * edges hold at all. A weight landing exactly on a band boundary, a dwell of
 * precisely the free allowance, a lane with no band covering the load -- none
 * of those are worth a scenario each, and all of them are worth a test.
 */
class RatingEngineTest {

    private final RatingEngine engine = new RatingEngine();

    private static Tariff card(String detentionFreeHours, String detentionRate,
                               String dropFee, String minimumCharge) {
        return Tariff.publish(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "TEST-CARD", UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "RIGID", "INR", LocalDate.of(2026, 1, 1),
                new BigDecimal(detentionFreeHours), new BigDecimal(detentionRate),
                new BigDecimal(dropFee), new BigDecimal(minimumCharge));
    }

    private static TariffSlab band(String from, String to, String ratePerKg) {
        return TariffSlab.of(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                new BigDecimal(from), to == null ? null : new BigDecimal(to),
                TariffSlab.RateBasis.PER_KG, new BigDecimal(ratePerKg));
    }

    private static BigDecimal amountOf(CostSheet sheet, ChargeType type) {
        return sheet.charges().stream()
                .filter(charge -> charge.type() == type)
                .map(CostSheet.Charge::amount)
                .findFirst()
                .orElse(null);
    }

    @Test
    void weightOnABandBoundaryFallsInTheUpperBand() {
        // Half-open bands. 5000 kg belongs to [5000, 10000) and to nothing else;
        // with closed intervals both bands would claim it and the price would
        // depend on which was read first.
        CostSheet sheet = engine.rate(card("0", "0", "0", "0"),
                List.of(band("0", "5000", "10"), band("5000", "10000", "4")),
                new BigDecimal("5000"), null, 1, null);

        assertThat(sheet.baseFreight()).isEqualByComparingTo("20000");
    }

    @Test
    void aDwellOfExactlyTheFreeAllowanceIsNotCharged() {
        CostSheet sheet = engine.rate(card("2", "300", "0", "0"),
                List.of(band("0", null, "1")), new BigDecimal("1000"),
                Duration.ofHours(2), 1, null);

        assertThat(sheet.detentionAmount()).isEqualByComparingTo("0");
        assertThat(amountOf(sheet, ChargeType.DETENTION)).isNull();
    }

    @Test
    void detentionIsChargedInFractionsOfAnHour() {
        // Twelve minutes over. Rounding up would bill an hour nobody waited and
        // rounding down would bill nothing for a real delay; both are how a
        // vendor stops trusting the number.
        CostSheet sheet = engine.rate(card("2", "300", "0", "0"),
                List.of(band("0", null, "1")), new BigDecimal("1000"),
                Duration.ofMinutes(132), 1, null);

        assertThat(sheet.detentionHours()).isEqualByComparingTo("0.20");
        assertThat(sheet.detentionAmount()).isEqualByComparingTo("60.00");
    }

    @Test
    void aLaneWithNoBandCoveringTheWeightFallsBackToTheFloor() {
        CostSheet sheet = engine.rate(card("0", "0", "0", "7500"),
                List.of(band("0", "1000", "10")), new BigDecimal("4000"), null, 1, null);

        assertThat(sheet.baseFreight()).isEqualByComparingTo("7500");
        assertThat(sheet.total()).isEqualByComparingTo("7500");
    }

    @Test
    void aLaneWithNeitherBandNorFloorPricesAtNothingRatherThanGuessing() {
        // Zero is not a valid freight charge, which is exactly why it is the
        // right answer here: the caller turns an empty sheet into an UNPRICED
        // bill, and a human decides. Inventing a rate would be worse.
        CostSheet sheet = engine.rate(card("0", "0", "0", "0"),
                List.of(band("0", "1000", "10")), new BigDecimal("4000"), null, 1, null);

        assertThat(sheet.total()).isEqualByComparingTo("0");
        assertThat(sheet.charges()).isEmpty();
    }

    @Test
    void theFuelSurchargeIsTakenOnLinehaulAloneAndNotOnTheDetention() {
        CostSheet sheet = engine.rate(card("0", "1000", "0", "0"),
                List.of(band("0", null, "1")), new BigDecimal("10000"),
                Duration.ofHours(3), 1, new BigDecimal("10"));

        assertThat(sheet.baseFreight()).isEqualByComparingTo("10000");
        assertThat(sheet.detentionAmount()).isEqualByComparingTo("3000");
        // Ten per cent of the linehaul, not of the 13 000 total.
        assertThat(sheet.fuelSurchargeAmount()).isEqualByComparingTo("1000");
        assertThat(sheet.total()).isEqualByComparingTo("14000");
    }

    @Test
    void aSingleDropCarriesNoAdditionalDropFee() {
        CostSheet sheet = engine.rate(card("0", "0", "750", "0"),
                List.of(band("0", null, "1")), new BigDecimal("1000"), null, 1, null);

        assertThat(sheet.multiDropAmount()).isEqualByComparingTo("0");
        assertThat(amountOf(sheet, ChargeType.ADDITIONAL_DROP)).isNull();
    }

    @Test
    void aTotalAlwaysEqualsTheSumOfTheLinesShownBeneathIt() {
        CostSheet sheet = engine.rate(card("1", "250", "600", "9000"),
                List.of(band("0", null, "2")), new BigDecimal("1500"),
                Duration.ofMinutes(200), 3, new BigDecimal("7.5"));

        BigDecimal sum = sheet.charges().stream()
                .map(CostSheet.Charge::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        assertThat(sheet.total()).isEqualByComparingTo(sum);
        assertThat(sheet.charges())
                .extracting(CostSheet.Charge::type)
                .contains(ChargeType.BASE_FREIGHT, ChargeType.MINIMUM_CHARGE_UPLIFT,
                        ChargeType.DETENTION, ChargeType.ADDITIONAL_DROP,
                        ChargeType.FUEL_SURCHARGE);
    }

    @Test
    void aTripThatNeverGatedInHasNoDwellToCharge() {
        CostSheet sheet = engine.rate(card("0", "500", "0", "0"),
                List.of(band("0", null, "1")), new BigDecimal("1000"), null, 1, null);

        assertThat(sheet.detentionHours()).isEqualByComparingTo("0");
        assertThat(sheet.detentionAmount()).isEqualByComparingTo("0");
    }
}
