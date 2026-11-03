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
package com.lms.multimodal;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import com.lms.multimodal.command.domain.ItineraryLeg.UnitType;
import com.lms.multimodal.command.domain.RailTariff;
import com.lms.shared.error.BusinessRuleViolationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Rail rate card arithmetic and versioning, without a database. */
class RailTariffTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID CARRIER = UUID.randomUUID();
    private static final UUID NGP = UUID.randomUUID();
    private static final UUID DLI = UUID.randomUUID();
    private static final LocalDate DAY = LocalDate.parse("2026-10-01");

    @Test
    @DisplayName("a booking costs the unit rate times the count, never less than the minimum")
    void price() {
        RailTariff card = card(new BigDecimal("2000"), new BigDecimal("1500.50"),
                new BigDecimal("10000"));
        assertThat(card.priceFor(UnitType.WAGON, 40)).isEqualByComparingTo("80000");
        assertThat(card.priceFor(UnitType.CONTAINER, 10)).isEqualByComparingTo("15005");
        assertThat(card.priceFor(UnitType.CONTAINER, 2)).isEqualByComparingTo("10000");
    }

    @Test
    @DisplayName("a unit the card does not price is refused rather than guessed")
    void unpricedUnit() {
        RailTariff wagonsOnly = card(new BigDecimal("2000"), null, null);
        assertRefused(() -> wagonsOnly.priceFor(UnitType.CONTAINER, 2),
                "rail-tariff-unit-not-priced");
        assertThat(wagonsOnly.minimumCharge()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("a card must price something, at zero or more, between two railheads")
    void shape() {
        assertRefused(() -> card(null, null, null), "rail-tariff-prices-nothing");
        assertRefused(() -> card(new BigDecimal("-1"), null, null), "rail-tariff-rate-invalid");
        assertRefused(() -> RailTariff.publish(UUID.randomUUID(), TENANT, "RC", CARRIER, NGP, NGP,
                null, BigDecimal.ONE, null, null, DAY, "planner"), "rail-tariff-goes-nowhere");
        assertRefused(() -> RailTariff.publish(UUID.randomUUID(), TENANT, " ", CARRIER, NGP, DLI,
                null, BigDecimal.ONE, null, null, DAY, "planner"), "rail-tariff-code-required");
        assertRefused(() -> RailTariff.publish(UUID.randomUUID(), TENANT, "RC", CARRIER, NGP, DLI,
                null, BigDecimal.ONE, null, null, null, "planner"), "rail-tariff-start-required");
    }

    @Test
    @DisplayName("closing a version bounds the days it covers, once, and never before it began")
    void closing() {
        RailTariff open = card(BigDecimal.ONE, null, null);
        assertThat(open.isOpen()).isTrue();
        assertThat(open.coversOn(DAY.plusYears(5))).isTrue();
        assertThat(open.coversOn(DAY.minusDays(1))).isFalse();

        RailTariff closed = open.closeOn(DAY.plusDays(9));
        assertThat(closed.isOpen()).isFalse();
        assertThat(closed.coversOn(DAY.plusDays(9))).isTrue();
        assertThat(closed.coversOn(DAY.plusDays(10))).isFalse();

        assertRefused(() -> closed.closeOn(DAY.plusDays(20)), "rail-tariff-already-closed");
        assertRefused(() -> open.closeOn(DAY.minusDays(1)), "rail-tariff-backdated");
    }

    private static RailTariff card(BigDecimal perWagon, BigDecimal perContainer,
                                   BigDecimal minimum) {
        return RailTariff.publish(UUID.randomUUID(), TENANT, "RC-1", CARRIER, NGP, DLI, null,
                perWagon, perContainer, minimum, DAY, "planner");
    }

    private static void assertRefused(Runnable action, String code) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(BusinessRuleViolationException.class,
                        e -> assertThat(e.code()).isEqualTo(code));
    }
}
