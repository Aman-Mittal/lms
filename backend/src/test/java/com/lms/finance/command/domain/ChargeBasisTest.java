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

import com.lms.shared.error.BusinessRuleViolationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The amount of a manual charge by its basis, and the catalogue entry's own rules, without a database. */
class ChargeBasisTest {

    @Test
    @DisplayName("a flat charge is one thing whatever quantity is typed")
    void flatIgnoresTheQuantity() {
        assertThat(ChargeBasis.FLAT.amount(new BigDecimal("7"), new BigDecimal("500"))).isEqualByComparingTo("500.00");
        assertThat(ChargeBasis.FLAT.chargedQuantity(new BigDecimal("7"))).isEqualByComparingTo("1");
    }

    @Test
    @DisplayName("the others are quantity times rate, half-up to two decimals")
    void othersMultiply() {
        assertThat(ChargeBasis.PER_KG.amount(new BigDecimal("100.5"), new BigDecimal("0.333")))
                .isEqualByComparingTo("33.47");
        assertThat(ChargeBasis.PER_HOUR.amount(new BigDecimal("2.5"), new BigDecimal("300"))).isEqualByComparingTo("750");
        assertThat(ChargeBasis.PER_DAY.amount(new BigDecimal("3"), new BigDecimal("400.005")))
                .isEqualByComparingTo("1200.02");
        assertThat(ChargeBasis.PER_UNIT.amount(new BigDecimal("12"), new BigDecimal("0.125"))).isEqualByComparingTo("1.50");
        assertThat(ChargeBasis.PER_KG.unit()).isEqualTo("kg");
    }

    @Test
    @DisplayName("a catalogue entry may be raised against the side its direction allows")
    void directionAllows() {
        assertThat(ChargeDirection.BOTH.allows(ChargeDirection.Side.RECEIVABLE)).isTrue();
        assertThat(ChargeDirection.BOTH.allows(ChargeDirection.Side.PAYABLE)).isTrue();
        assertThat(ChargeDirection.RECEIVABLE.allows(ChargeDirection.Side.RECEIVABLE)).isTrue();
        assertThat(ChargeDirection.RECEIVABLE.allows(ChargeDirection.Side.PAYABLE)).isFalse();
        assertThat(ChargeDirection.PAYABLE.allows(ChargeDirection.Side.RECEIVABLE)).isFalse();
    }

    @Test
    @DisplayName("a catalogue code is upper case and plain; a name and a rate are required")
    void catalogueRules() {
        assertThat(ChargeCatalogueItem.normaliseCode("  loading-1 ")).isEqualTo("LOADING-1");
        assertThatThrownBy(() -> ChargeCatalogueItem.normaliseCode("two words"))
                .isInstanceOfSatisfying(BusinessRuleViolationException.class,
                        e -> assertThat(e.code()).isEqualTo("charge-code-invalid"));
        assertThatThrownBy(() -> ChargeCatalogueItem.normaliseCode(" "))
                .isInstanceOf(BusinessRuleViolationException.class);
        assertThatThrownBy(() -> ChargeCatalogueItem.normaliseCode("X".repeat(41)))
                .isInstanceOf(BusinessRuleViolationException.class);

        ChargeCatalogueItem entry = ChargeCatalogueItem.create(java.util.UUID.randomUUID(), java.util.UUID.randomUUID(),
                "toll", "Toll", ChargeBasis.FLAT, new BigDecimal("120"), ChargeDirection.PAYABLE, true);
        assertThat(entry.code()).isEqualTo("TOLL");
        ChargeCatalogueItem edited = entry.edited("Toll plaza", ChargeBasis.PER_UNIT, new BigDecimal("1"),
                ChargeDirection.BOTH, false);
        assertThat(edited.code()).as("the code never changes").isEqualTo("TOLL");
        assertThat(edited.active()).isFalse();
        assertThatThrownBy(() -> entry.edited("Toll", ChargeBasis.FLAT, new BigDecimal("-1"), ChargeDirection.BOTH, true))
                .isInstanceOfSatisfying(BusinessRuleViolationException.class,
                        e -> assertThat(e.code()).isEqualTo("charge-rate-invalid"));
    }
}
