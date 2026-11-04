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
import java.util.List;
import java.util.UUID;

import com.lms.masterdata.api.CustomerCreditPort;
import com.lms.masterdata.api.CustomerCreditPort.CreditCheck;
import com.lms.masterdata.api.CustomerCreditPort.CreditEvent;
import com.lms.order.api.MaterialClass;
import com.lms.order.command.domain.OrderLine;
import com.lms.order.command.domain.SalesOrder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/** Which order changes are put to the credit gate. */
class OrderCreditGateTest {

    private final CustomerCreditPort credit = mock(CustomerCreditPort.class);
    private final OrderCreditGate gate = new OrderCreditGate(credit);
    private final UUID customer = UUID.randomUUID();

    private SalesOrder order(UUID customerId) {
        return SalesOrder.raise(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), customerId,
                "SO-1", UUID.randomUUID(), null, null, SalesOrder.OrderSource.MANUAL);
    }

    private static OrderLine line(String kg) {
        return new OrderLine(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 1, "MAT",
                "Material", MaterialClass.GENERAL, null, BigDecimal.ONE, "EA", new BigDecimal(kg),
                null, null, null, null, UUID.randomUUID(), UUID.randomUUID(), false, false, null,
                Instant.now(), null, null, null);
    }

    @Test
    @DisplayName("an amendment that shrinks the order for the same customer is not held to the limit")
    void shrinkingIsNotChecked() {
        SalesOrder order = order(customer);
        gate.afterAmending(order, order, List.of(line("100")), List.of(line("60")));
        gate.afterAmending(order, order, List.of(line("100")), List.of(line("100")));
        verify(credit, never()).requireHeadroom(org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("an amendment that grows the order is checked as an amendment")
    void growingIsChecked() {
        SalesOrder order = order(customer);
        gate.afterAmending(order, order, List.of(line("100")), List.of(line("100"), line("1")));
        ArgumentCaptor<CreditCheck> check = ArgumentCaptor.forClass(CreditCheck.class);
        verify(credit).requireHeadroom(check.capture());
        assertThat(check.getValue().event()).isEqualTo(CreditEvent.ORDER_AMEND);
        assertThat(check.getValue().subjectKey()).isEqualTo("ORDER:" + order.id());
    }

    @Test
    @DisplayName("moving an order to another customer checks the new customer, whatever the weight")
    void newCustomerIsChecked() {
        SalesOrder before = order(customer);
        UUID other = UUID.randomUUID();
        SalesOrder after = before.revised(other, before.originTerminalId(), null, null, null, null,
                before.version());
        gate.afterAmending(before, after, List.of(line("100")), List.of(line("10")));
        ArgumentCaptor<CreditCheck> check = ArgumentCaptor.forClass(CreditCheck.class);
        verify(credit).requireHeadroom(check.capture());
        assertThat(check.getValue().customerPartnerId()).isEqualTo(other);
    }

    @Test
    @DisplayName("validation is checked against the order's own customer, counting the order")
    void validationIsChecked() {
        SalesOrder order = order(customer);
        gate.requireTrading(order);
        gate.afterValidating(order);
        verify(credit).requireTrading(customer);
        verify(credit).requireHeadroom(
                CreditCheck.forOrder(customer, CreditEvent.ORDER_VALIDATE, order.id()));
    }
}
