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
package com.lms.order.command;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import com.lms.order.command.domain.SalesOrder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** The two pure rules where an order meets its contract: the day it is judged by, and days from distance. */
class OrderContractDeskTest {

    private static SalesOrder order(Instant pickup) {
        return SalesOrder.raise(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "ORD-1", UUID.randomUUID(), pickup, null, SalesOrder.OrderSource.MANUAL);
    }

    @Test
    @DisplayName("an order is judged by its requested pickup day, so a back-dated order finds the version in force then")
    void theDayAnOrderIsJudgedBy() {
        assertThat(OrderContractDesk.orderDate(order(Instant.parse("2026-03-15T23:30:00Z"))))
                .isEqualTo(LocalDate.of(2026, 3, 15));
        assertThat(OrderContractDesk.orderDate(order(null))).isEqualTo(LocalDate.now(java.time.ZoneOffset.UTC));
    }

    @Test
    @DisplayName("days from distance are rounded up and never fewer than one")
    void daysFromDistance() {
        assertThat(OrderContractDesk.daysFor(1138, 400)).isEqualTo(3);
        assertThat(OrderContractDesk.daysFor(800, 400)).isEqualTo(2);
        assertThat(OrderContractDesk.daysFor(800.1, 400)).isEqualTo(3);
        assertThat(OrderContractDesk.daysFor(0.4, 400)).isEqualTo(1);
        assertThat(OrderContractDesk.daysFor(0, 400)).isEqualTo(1);
        assertThat(OrderContractDesk.daysFor(1138, 1000)).isEqualTo(2);
    }

    @Test
    @DisplayName("a load type is stored upper case and trimmed, and a blank one is none")
    void loadTypes() {
        assertThat(SalesOrder.normaliseLoadType(" 32ft ")).isEqualTo("32FT");
        assertThat(SalesOrder.normaliseLoadType("  ")).isNull();
        assertThat(SalesOrder.normaliseLoadType(null)).isNull();
    }
}
