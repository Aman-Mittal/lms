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

package com.lms.clientbilling;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

import com.lms.clientbilling.command.domain.ChargePricing;
import com.lms.clientbilling.command.domain.ClientCharge;
import com.lms.clientbilling.command.domain.ClientContract;
import com.lms.clientbilling.command.domain.ServiceType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** A charge raised by hand is priced by its own amount, never by the contract's rates. */
class ManualChargePricingTest {

    private static final LocalDate DAY = LocalDate.of(2026, 3, 10);

    private static ClientContract contract() {
        return ClientContract.publish(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "C-1", UUID.randomUUID(),
                "INR", LocalDate.of(2026, 1, 1),
                new ClientContract.Rates(new BigDecimal("2"), new BigDecimal("1000"), new BigDecimal("12.50"),
                        new BigDecimal("25"), new BigDecimal("30"), new BigDecimal("18"), new BigDecimal("5"), null));
    }

    private static ClientCharge manual(String quantity, String rate, String amount) {
        return ClientCharge.manual(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), new BigDecimal(quantity),
                "hour", new BigDecimal(rate), new BigDecimal(amount), "Detention (DETENTION): waited", DAY,
                DAY.withDayOfMonth(1), "MANUAL:" + UUID.randomUUID(), "MC-1 / TR-1", UUID.randomUUID());
    }

    @Test
    @DisplayName("the line carries the charge's own rate and amount, below the transport minimum too")
    void ownAmount() {
        ChargePricing.Priced priced = ChargePricing.price(manual("2", "150", "300"), contract(), Map.of());
        assertThat(priced.amount()).isEqualByComparingTo("300");
        assertThat(priced.rate()).isEqualByComparingTo("150");
        assertThat(priced.unit()).isEqualTo("hour");
        assertThat(priced.quantity()).isEqualByComparingTo("2");
        assertThat(priced.narrative()).contains("Detention").contains("MC-1 / TR-1");
    }

    @Test
    @DisplayName("it is taxed at the contract's transport rate, and is not a warehouse service")
    void taxAndKind() {
        assertThat(contract().taxRateFor(ServiceType.MANUAL)).isEqualByComparingTo("18");
        assertThat(contract().taxRateFor(ServiceType.STORAGE)).isEqualByComparingTo("5");
        assertThat(ServiceType.MANUAL.isWarehouseService()).as("usage cannot be recorded as a manual charge").isFalse();
        assertThat(ServiceType.STORAGE.isWarehouseService()).isTrue();
    }

    @Test
    @DisplayName("a manual charge without its amount or narrative is not captured")
    void incomplete() {
        assertThatThrownBy(() -> ClientCharge.manual(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                BigDecimal.ONE, "unit", BigDecimal.ONE, BigDecimal.ZERO, "x", DAY, DAY.withDayOfMonth(1), "K", null,
                null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ClientCharge.manual(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                BigDecimal.ONE, "unit", BigDecimal.ONE, BigDecimal.ONE, " ", DAY, DAY.withDayOfMonth(1), "K", null,
                null)).isInstanceOf(IllegalArgumentException.class);
    }
}
