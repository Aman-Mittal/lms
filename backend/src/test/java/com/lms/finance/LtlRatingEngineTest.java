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
package com.lms.finance;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import com.lms.finance.command.LtlRatingEngine;
import com.lms.finance.command.domain.LtlQuote;
import com.lms.finance.command.domain.LtlRateCard;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The edges of part-load rating, without a database.
 *
 * <p>{@code freightmodes.feature} proves the rules hold end to end; these pin
 * the boundaries -- a docket weighing exactly the minimum, volume and weight
 * exactly tied, the per-CFT conversion -- that are not worth a scenario each.
 */
class LtlRatingEngineTest {

    private final LtlRatingEngine engine = new LtlRatingEngine();

    private static LtlRateCard card(LtlRateCard.RateBasis basis, String rate, String minimumKg,
                                    String kgPerCft) {
        return LtlRateCard.publish(UUID.randomUUID(), UUID.randomUUID(), "TEST", "west", " north ",
                null, LocalDate.of(2026, 1, 1), basis, new BigDecimal(rate),
                new BigDecimal(minimumKg), new BigDecimal(kgPerCft), new BigDecimal("100"),
                new BigDecimal("500"));
    }

    @Test
    void dead_weight_above_the_minimum_is_billed_as_it_is() {
        LtlQuote quote = engine.quote(card(LtlRateCard.RateBasis.PER_KG, "12", "50", "6"),
                new BigDecimal("200"), BigDecimal.ZERO, false);

        assertThat(quote.chargeableWeightKg()).isEqualByComparingTo("200");
        assertThat(quote.minimumApplied()).isFalse();
        assertThat(quote.freightAmount()).isEqualByComparingTo("2400.00");
        assertThat(quote.odaSurcharge()).isEqualByComparingTo("0");
        assertThat(quote.total()).isEqualByComparingTo("2500.00");
    }

    @Test
    void a_docket_weighing_exactly_the_minimum_did_not_need_it() {
        LtlQuote quote = engine.quote(card(LtlRateCard.RateBasis.PER_KG, "12", "50", "6"),
                new BigDecimal("50"), null, false);

        assertThat(quote.chargeableWeightKg()).isEqualByComparingTo("50");
        assertThat(quote.minimumApplied()).isFalse();
    }

    @Test
    void a_light_docket_is_lifted_to_the_minimum_and_says_so() {
        LtlQuote quote = engine.quote(card(LtlRateCard.RateBasis.PER_KG, "12", "50", "6"),
                new BigDecimal("12.5"), null, false);

        assertThat(quote.chargeableWeightKg()).isEqualByComparingTo("50");
        assertThat(quote.minimumApplied()).isTrue();
        assertThat(quote.freightAmount()).isEqualByComparingTo("600.00");
    }

    @Test
    void volume_outweighs_dead_weight_through_cubic_feet() {
        // 1 m3 = 35.315 CFT; at 6 kg per CFT, 211.890 kg.
        LtlQuote quote = engine.quote(card(LtlRateCard.RateBasis.PER_KG, "12", "50", "6"),
                new BigDecimal("100"), new BigDecimal("1.000"), false);

        assertThat(quote.volumeCft()).isEqualByComparingTo("35.315");
        assertThat(quote.volumetricWeightKg()).isEqualByComparingTo("211.890");
        assertThat(quote.chargeableWeightKg()).isEqualByComparingTo("211.890");
        assertThat(quote.freightAmount()).isEqualByComparingTo("2542.68");
    }

    @Test
    void a_per_cft_card_bills_the_chargeable_weight_in_cubic_feet() {
        // 140 kg at 7 kg per CFT is 20 CFT.
        LtlQuote quote = engine.quote(card(LtlRateCard.RateBasis.PER_CFT, "70", "0", "7"),
                new BigDecimal("140"), BigDecimal.ZERO, false);

        assertThat(quote.freightAmount()).isEqualByComparingTo("1400.00");
    }

    @Test
    void out_of_delivery_area_adds_the_surcharge_and_nothing_else() {
        LtlRateCard card = card(LtlRateCard.RateBasis.PER_KG, "12", "50", "6");
        LtlQuote inside = engine.quote(card, new BigDecimal("200"), null, false);
        LtlQuote outside = engine.quote(card, new BigDecimal("200"), null, true);

        assertThat(outside.odaSurcharge()).isEqualByComparingTo("500.00");
        assertThat(outside.outOfDeliveryArea()).isTrue();
        assertThat(outside.total().subtract(inside.total())).isEqualByComparingTo("500.00");
    }

    @Test
    void zone_codes_are_normalised_so_a_lane_cannot_be_two_lanes() {
        LtlRateCard card = card(LtlRateCard.RateBasis.PER_KG, "12", "50", "6");

        assertThat(card.originZone()).isEqualTo("WEST");
        assertThat(card.destinationZone()).isEqualTo("NORTH");
    }

    @Test
    void a_card_without_a_volumetric_conversion_is_refused() {
        assertThatThrownBy(() -> card(LtlRateCard.RateBasis.PER_KG, "12", "50", "0"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("volumetric");
    }

    // ------------------------------------------------------- ODA per kg

    private static LtlRateCard perKgOdaCard(String odaRatePerKg, String odaMinimum) {
        return LtlRateCard.publish(UUID.randomUUID(), UUID.randomUUID(), "TEST", "WEST", "NORTH",
                null, LocalDate.of(2026, 1, 1), LtlRateCard.RateBasis.PER_KG, new BigDecimal("12"),
                new BigDecimal("50"), new BigDecimal("6"), new BigDecimal("100"),
                new BigDecimal("500"), LtlRateCard.OdaBasis.PER_KG, new BigDecimal(odaRatePerKg),
                new BigDecimal(odaMinimum));
    }

    @Test
    void a_per_kg_oda_charge_is_reckoned_on_the_chargeable_weight() {
        // 200 kg x 3.50 = 700.00, above the 250 minimum; the flat 500 is ignored.
        LtlQuote quote = engine.quote(perKgOdaCard("3.5", "250"), new BigDecimal("200"), null,
                true);

        assertThat(quote.odaSurcharge()).isEqualByComparingTo("700.00");
        assertThat(quote.odaBasis()).isEqualTo(LtlRateCard.OdaBasis.PER_KG);
        assertThat(quote.total()).isEqualByComparingTo("3200.00");
    }

    @Test
    void a_per_kg_oda_charge_never_falls_below_its_minimum() {
        // 30 kg is billed as 50 kg; 50 x 3.50 = 175.00, lifted to 250.
        LtlQuote quote = engine.quote(perKgOdaCard("3.5", "250"), new BigDecimal("30"), null,
                true);

        assertThat(quote.chargeableWeightKg()).isEqualByComparingTo("50");
        assertThat(quote.odaSurcharge()).isEqualByComparingTo("250.00");
    }

    @Test
    void a_per_kg_oda_charge_follows_volume_as_freight_does() {
        // 1 m3 = 35.315 CFT = 211.890 kg at 6 kg/CFT; x 2.00 = 423.78.
        LtlQuote quote = engine.quote(perKgOdaCard("2", "0"), new BigDecimal("100"),
                BigDecimal.ONE, true);

        assertThat(quote.odaSurcharge()).isEqualByComparingTo("423.78");
    }

    @Test
    void a_per_kg_oda_card_is_not_charged_inside_the_delivery_area() {
        LtlQuote quote = engine.quote(perKgOdaCard("3.5", "250"), new BigDecimal("200"), null,
                false);

        assertThat(quote.odaSurcharge()).isEqualByComparingTo("0");
        assertThat(quote.odaBasis()).isNull();
    }

    @Test
    void a_per_kg_oda_card_must_state_its_rate() {
        assertThatThrownBy(() -> LtlRateCard.publish(UUID.randomUUID(), UUID.randomUUID(),
                "TEST", "WEST", "NORTH", null, LocalDate.of(2026, 1, 1),
                LtlRateCard.RateBasis.PER_KG, new BigDecimal("12"), null, new BigDecimal("6"),
                null, null, LtlRateCard.OdaBasis.PER_KG, null, new BigDecimal("250")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("per kg");
    }

    @Test
    void a_card_published_without_a_basis_charges_oda_flat() {
        assertThat(card(LtlRateCard.RateBasis.PER_KG, "12", "50", "6").odaBasis())
                .isEqualTo(LtlRateCard.OdaBasis.FLAT);
    }

    // ------------------------------------------------------- fuel

    @Test
    void the_fuel_surcharge_is_a_percentage_of_the_freight_alone() {
        // Freight 2400.00; 7.5% of it is 180.00. The 100 docket charge and the
        // 500 ODA surcharge are not surcharged.
        LtlQuote quote = engine.quote(card(LtlRateCard.RateBasis.PER_KG, "12", "50", "6"),
                new BigDecimal("200"), null, true, new BigDecimal("7.5"));

        assertThat(quote.fuelSurchargePct()).isEqualByComparingTo("7.5");
        assertThat(quote.fuelSurcharge()).isEqualByComparingTo("180.00");
        assertThat(quote.total()).isEqualByComparingTo("3180.00");
    }

    @Test
    void the_fuel_surcharge_rounds_to_the_paisa() {
        // 2542.68 x 4.125% = 104.88555 -> 104.89.
        LtlQuote quote = engine.quote(card(LtlRateCard.RateBasis.PER_KG, "12", "50", "6"),
                new BigDecimal("100"), BigDecimal.ONE, false, new BigDecimal("4.125"));

        assertThat(quote.freightAmount()).isEqualByComparingTo("2542.68");
        assertThat(quote.fuelSurcharge()).isEqualByComparingTo("104.89");
    }

    @Test
    void a_falling_fuel_index_reduces_the_docket() {
        LtlQuote quote = engine.quote(card(LtlRateCard.RateBasis.PER_KG, "12", "50", "6"),
                new BigDecimal("200"), null, false, new BigDecimal("-2"));

        assertThat(quote.fuelSurcharge()).isEqualByComparingTo("-48.00");
        assertThat(quote.total()).isEqualByComparingTo("2452.00");
    }

    @Test
    void a_month_with_no_fuel_index_charges_no_fuel() {
        LtlQuote quote = engine.quote(card(LtlRateCard.RateBasis.PER_KG, "12", "50", "6"),
                new BigDecimal("200"), null, false, null);

        assertThat(quote.fuelSurchargePct()).isNull();
        assertThat(quote.fuelSurcharge()).isEqualByComparingTo("0");
        assertThat(quote.total()).isEqualByComparingTo("2500.00");
    }
}
