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
package com.lms.order;

import java.time.Instant;
import java.util.UUID;

import com.lms.order.command.domain.SalesOrder;
import com.lms.order.command.domain.SalesOrder.OrderStatus;
import com.lms.shared.error.BusinessRuleViolationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The order state machine of vision document 3.3, and the coverage rule. */
class OrderLifecycleTest {

    private static SalesOrder draft() {
        return SalesOrder.raise(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), "SO-1", UUID.randomUUID(), null, null,
                SalesOrder.OrderSource.MANUAL);
    }

    @Test
    @DisplayName("a new order starts editable and unplannable")
    void newOrderIsDraft() {
        SalesOrder order = draft();

        assertThat(order.status()).isEqualTo(OrderStatus.DRAFT);
        assertThat(order.isEditable()).isTrue();
        // Planning must not draw from an order that has not been through the
        // hazmat and compatibility checks.
        assertThat(order.isPlannable()).isFalse();
    }

    @Test
    @DisplayName("validation closes the order to amendment and opens it to planning")
    void validatedOrderIsPlannable() {
        SalesOrder validated = draft().transitionTo(OrderStatus.VALIDATED);

        assertThat(validated.isEditable()).isFalse();
        assertThat(validated.isPlannable()).isTrue();
    }

    @Test
    @DisplayName("an order cannot skip validation")
    void cannotPlanAnUnvalidatedOrder() {
        assertThatThrownBy(() -> draft().transitionTo(OrderStatus.FULLY_PLANNED))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("cannot move from DRAFT to FULLY_PLANNED");
    }

    @Test
    @DisplayName("partial coverage gives PARTIALLY_PLANNED, full coverage FULLY_PLANNED")
    void coverageDrivesStatus() {
        SalesOrder validated = draft().transitionTo(OrderStatus.VALIDATED);

        assertThat(validated.withCoverage(2, 5).status()).isEqualTo(OrderStatus.PARTIALLY_PLANNED);
        assertThat(validated.withCoverage(5, 5).status()).isEqualTo(OrderStatus.FULLY_PLANNED);
    }

    @Test
    @DisplayName("coverage can fall back when a load is broken up")
    void coverageGoesBackwards() {
        // A cancelled load releases its consignments. An order that claimed full
        // coverage no longer has it, and a forward-only machine would leave it
        // lying about being ready to dispatch.
        SalesOrder fullyPlanned = draft()
                .transitionTo(OrderStatus.VALIDATED)
                .withCoverage(4, 4);

        assertThat(fullyPlanned.withCoverage(1, 4).status()).isEqualTo(OrderStatus.PARTIALLY_PLANNED);
    }

    @Test
    @DisplayName("coverage falling to nothing returns the order to VALIDATED")
    void coverageBackToNothing() {
        SalesOrder partially = draft()
                .transitionTo(OrderStatus.VALIDATED)
                .withCoverage(1, 3);

        assertThat(partially.withCoverage(0, 3).status()).isEqualTo(OrderStatus.VALIDATED);
    }

    @Test
    @DisplayName("recomputing the same coverage is a no-op")
    void coverageIsIdempotent() {
        // Consignment generation is retried after timeouts, and each retry
        // recomputes coverage. Re-declaring the state it is already in must not
        // be treated as an illegal self-transition.
        SalesOrder fullyPlanned = draft()
                .transitionTo(OrderStatus.VALIDATED)
                .withCoverage(3, 3);

        assertThat(fullyPlanned.withCoverage(3, 3).status()).isEqualTo(OrderStatus.FULLY_PLANNED);
    }

    @Test
    @DisplayName("coverage cannot exceed the order")
    void coverageCannotExceedTotal() {
        SalesOrder validated = draft().transitionTo(OrderStatus.VALIDATED);

        assertThatThrownBy(() -> validated.withCoverage(6, 5))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("an order with no lines cannot be given coverage")
    void noLinesNoCoverage() {
        SalesOrder validated = draft().transitionTo(OrderStatus.VALIDATED);

        assertThatThrownBy(() -> validated.withCoverage(0, 0))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("no lines");
    }

    @Test
    @DisplayName("fulfilled and cancelled are terminal")
    void terminalStates() {
        SalesOrder cancelled = draft().transitionTo(OrderStatus.CANCELLED);
        SalesOrder fulfilled = draft()
                .transitionTo(OrderStatus.VALIDATED)
                .withCoverage(1, 1)
                .transitionTo(OrderStatus.FULFILLED);

        assertThatThrownBy(() -> cancelled.transitionTo(OrderStatus.VALIDATED))
                .isInstanceOf(BusinessRuleViolationException.class);
        assertThatThrownBy(() -> fulfilled.transitionTo(OrderStatus.CANCELLED))
                .isInstanceOf(BusinessRuleViolationException.class);
    }

    @Test
    @DisplayName("a delivery date before the pickup date is refused")
    void deliveryBeforePickup() {
        Instant pickup = Instant.parse("2026-08-10T08:00:00Z");
        Instant delivery = pickup.minusSeconds(3600);

        assertThatThrownBy(() -> SalesOrder.raise(UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), "SO-BAD", UUID.randomUUID(),
                pickup, delivery, SalesOrder.OrderSource.MANUAL))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("an order must name a customer and carry a number")
    void mandatoryFields() {
        assertThatThrownBy(() -> SalesOrder.raise(UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), "  ", null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> SalesOrder.raise(UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), null, "SO-2", null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
