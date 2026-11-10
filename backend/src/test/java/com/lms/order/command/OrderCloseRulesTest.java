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

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import com.lms.order.api.MaterialClass;
import com.lms.order.api.OrderFreightProbe.LineFreight;
import com.lms.order.command.domain.CloseKind;
import com.lms.order.command.domain.OrderLine;
import com.lms.order.command.domain.SalesOrder;
import com.lms.order.command.domain.SalesOrder.OrderStatus;
import com.lms.shared.error.BusinessRuleViolationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The rules a close rests on: which orders may close, coverage with closed lines, and the tolerance. */
class OrderCloseRulesTest {

    private static SalesOrder order(OrderStatus status) {
        SalesOrder raised = SalesOrder.raise(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), "SO-1", UUID.randomUUID(), null, null, SalesOrder.OrderSource.MANUAL);
        return switch (status) {
            case DRAFT -> raised;
            case VALIDATED -> raised.transitionTo(OrderStatus.VALIDATED);
            case PARTIALLY_PLANNED, FULLY_PLANNED, CANCELLED ->
                    raised.transitionTo(OrderStatus.VALIDATED).transitionTo(status);
            case FULFILLED -> raised.transitionTo(OrderStatus.VALIDATED)
                    .transitionTo(OrderStatus.FULLY_PLANNED).transitionTo(OrderStatus.FULFILLED);
            case CLOSED -> raised.transitionTo(OrderStatus.VALIDATED)
                    .closed(CloseKind.FORCE, "why", Instant.now());
        };
    }

    @ParameterizedTest
    @EnumSource(value = OrderStatus.class, names = {"VALIDATED", "PARTIALLY_PLANNED", "FULLY_PLANNED"})
    @DisplayName("an order open to planning or delivery can be closed, and says how")
    void openOrdersClose(OrderStatus status) {
        SalesOrder order = order(status);
        assertThat(order.isClosable()).isTrue();
        Instant at = Instant.now();
        SalesOrder closed = order.closed(CloseKind.SHORT, "no more wanted", at);
        assertThat(closed.status()).isEqualTo(OrderStatus.CLOSED);
        assertThat(closed.closeKind()).isEqualTo(CloseKind.SHORT);
        assertThat(closed.closeReason()).isEqualTo("no more wanted");
        assertThat(closed.closedAt()).isEqualTo(at);
    }

    @ParameterizedTest
    @EnumSource(value = OrderStatus.class, names = {"DRAFT", "FULFILLED", "CANCELLED", "CLOSED"})
    @DisplayName("a draft, or an order that has ended, cannot move to CLOSED")
    void endedOrdersDoNotClose(OrderStatus status) {
        SalesOrder order = order(status);
        assertThat(order.isClosable()).isFalse();
        assertThatThrownBy(() -> order.closed(CloseKind.FORCE, "why", Instant.now()))
                .isInstanceOf(BusinessRuleViolationException.class);
    }

    @Test
    @DisplayName("a closed order cannot be cancelled afterwards")
    void closedIsTerminal() {
        assertThatThrownBy(() -> order(OrderStatus.CLOSED).transitionTo(OrderStatus.CANCELLED))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("CLOSED");
    }

    @Test
    @DisplayName("a line closed unplanned counts as spoken for, but not when nothing is planned")
    void coverageCountsClosedLines() {
        SalesOrder validated = order(OrderStatus.VALIDATED);
        assertThat(validated.withCoverage(1, 1, 2).status()).isEqualTo(OrderStatus.FULLY_PLANNED);
        assertThat(validated.withCoverage(1, 0, 3).status()).isEqualTo(OrderStatus.PARTIALLY_PLANNED);
        assertThat(validated.withCoverage(0, 1, 2).status()).isEqualTo(OrderStatus.VALIDATED);
        assertThatThrownBy(() -> validated.withCoverage(2, 1, 2)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("a line is open only while it is neither closed nor cancelled")
    void openLines() {
        OrderLine line = new OrderLine(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 1, "MAT",
                "Material", MaterialClass.GENERAL, null, BigDecimal.ONE, "EA", BigDecimal.TEN,
                null, null, null, null, UUID.randomUUID(), UUID.randomUUID(), false, false, 1L,
                Instant.now(), null, null, null);
        assertThat(line.isOpen()).isTrue();
        assertThat(line.cancel().isOpen()).isFalse();
        assertThat(line.cancel().isClosed()).isFalse();
        assertThat(line.closed(CloseKind.SHORT, "why", Instant.now()).isOpen()).isFalse();
        assertThat(line.closed(CloseKind.SHORT, "why", Instant.now()).cancelled()).isFalse();
    }

    @Test
    @DisplayName("closing a line keeps whether it was planned, and closing it again changes nothing")
    void lineCloses() {
        OrderLine line = new OrderLine(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 1, "MAT",
                "Material", MaterialClass.GENERAL, null, BigDecimal.ONE, "EA", BigDecimal.TEN,
                null, null, null, null, UUID.randomUUID(), UUID.randomUUID(), true, false, 3L,
                Instant.now(), null, null, null);
        OrderLine closed = line.closed(CloseKind.FORCE, "why", Instant.now());
        assertThat(closed.isClosed()).isTrue();
        assertThat(closed.planned()).isTrue();
        assertThat(closed.version()).isEqualTo(3L);
        assertThat(closed.closed(CloseKind.SHORT, "again", Instant.now())).isSameAs(closed);
    }

    @Test
    @DisplayName("only short and forced closes must say why")
    void reasons() {
        assertThat(CloseKind.SHORT.needsReason()).isTrue();
        assertThat(CloseKind.FORCE.needsReason()).isTrue();
        assertThat(CloseKind.NORMAL.needsReason()).isFalse();
        assertThat(CloseKind.AUTO.needsReason()).isFalse();
    }

    @ParameterizedTest(name = "{0} of {1} consignments, {2} of {3} kg delivered, tolerance {4}% -> {5}")
    @CsvSource({
        "2, 2, 10000, 10000, 0, true",
        "1, 2, 9600, 10000, 0, false",
        "1, 2, 9600, 10000, 3.99, false",
        "1, 2, 9600, 10000, 4, true",
        "1, 2, 9600, 10000, 5, true",
        "0, 2, 0, 10000, 100, false",
        "0, 0, 0, 0, 100, false",
    })
    @DisplayName("a line counts as delivered within the tolerance by the weight still on the road")
    void tolerance(int delivered, int live, int deliveredKg, int liveKg, String percent, boolean expected) {
        LineFreight freight = new LineFreight(live, delivered, 0, BigDecimal.valueOf(liveKg),
                BigDecimal.valueOf(deliveredKg));
        assertThat(freight.fulfilledWithin(new BigDecimal(percent))).isEqualTo(expected);
    }

    @Test
    @DisplayName("a line is fulfilled only when something was delivered and nothing is left")
    void fulfilled() {
        assertThat(new LineFreight(2, 2, 0, BigDecimal.TEN, BigDecimal.TEN).fulfilled()).isTrue();
        assertThat(new LineFreight(2, 1, 0, BigDecimal.TEN, BigDecimal.ONE).fulfilled()).isFalse();
        assertThat(new LineFreight(0, 0, 0, BigDecimal.ZERO, BigDecimal.ZERO).fulfilled()).isFalse();
    }
}
