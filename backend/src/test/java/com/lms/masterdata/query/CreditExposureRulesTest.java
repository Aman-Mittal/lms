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
package com.lms.masterdata.query;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.lms.masterdata.api.OpenOrderDemandSource.OpenOrderDemand;

import com.lms.masterdata.api.ReceivablesSource.TransportTariff;
import com.lms.masterdata.query.CreditPositionView.LimitState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** How an open order is valued, and where an exposure stands against the limits. */
class CreditExposureRulesTest {

    private static BigDecimal amount(String value) {
        return new BigDecimal(value);
    }

    @Test
    @DisplayName("reaching a limit is within it; only going past it counts")
    void limitStates() {
        assertThat(CreditExposureService.stateOf(amount("5000"), null, null))
                .isEqualTo(LimitState.NO_LIMIT);
        assertThat(CreditExposureService.stateOf(amount("800"), amount("1000"), amount("800")))
                .isEqualTo(LimitState.WITHIN);
        assertThat(CreditExposureService.stateOf(amount("800.01"), amount("1000"), amount("800")))
                .isEqualTo(LimitState.SOFT_EXCEEDED);
        assertThat(CreditExposureService.stateOf(amount("1000"), amount("1000"), null))
                .isEqualTo(LimitState.WITHIN);
        assertThat(CreditExposureService.stateOf(amount("1000.01"), amount("1000"), amount("800")))
                .isEqualTo(LimitState.HARD_EXCEEDED);
    }

    @Test
    @DisplayName("an open order is worth its kg at the contract rate, never under the minimum, plus tax")
    void openOrderValue() {
        TransportTariff tariff = new TransportTariff("INR", amount("5"), amount("300"), amount("18"));
        // 100 kg x 5 = 500, + 18% = 590.
        assertThat(CreditExposureService.valueOf(amount("100"), tariff)).isEqualByComparingTo("590");
        // 10 kg x 5 = 50, floored at 300, + 54 tax.
        assertThat(CreditExposureService.valueOf(amount("10"), tariff)).isEqualByComparingTo("354");
        TransportTariff untaxed = new TransportTariff("INR", amount("5"), null, null);
        assertThat(CreditExposureService.valueOf(amount("10.5"), untaxed)).isEqualByComparingTo("52.50");
    }

    @Test
    @DisplayName("an order's charged weight leaves its open value, so no kilogram is counted twice")
    void chargedWeightIsCountedOnce() {
        UUID untouched = UUID.randomUUID();
        UUID partlyCharged = UUID.randomUUID();
        UUID fullyCharged = UUID.randomUUID();
        List<OpenOrderDemand> open = CreditExposureService.stillOpen(List.of(
                        new OpenOrderDemand(untouched, amount("100")),
                        new OpenOrderDemand(partlyCharged, amount("100")),
                        new OpenOrderDemand(fullyCharged, amount("100"))),
                Map.of(partlyCharged, amount("40"), fullyCharged, amount("100.000")));

        // Untouched keeps all its weight, the part-charged order keeps the rest,
        // and the fully charged one drops out rather than being floored at the
        // contract minimum a second time.
        assertThat(open).extracting(OpenOrderDemand::orderId).containsExactly(untouched, partlyCharged);
        assertThat(open.get(0).chargeableWeightKg()).isEqualByComparingTo("100");
        assertThat(open.get(1).chargeableWeightKg()).isEqualByComparingTo("60");
        // Charged beyond the order (a re-weighed consignment) never goes negative.
        assertThat(CreditExposureService.stillOpen(List.of(new OpenOrderDemand(untouched,
                amount("10"))), Map.of(untouched, amount("12")))).isEmpty();
    }
}
