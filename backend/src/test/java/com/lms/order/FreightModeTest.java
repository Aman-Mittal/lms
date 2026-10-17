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

import java.math.BigDecimal;
import java.util.UUID;

import com.lms.order.api.FreightMode;
import com.lms.order.command.domain.SalesOrder;
import com.lms.planning.command.domain.LoadUnit;
import com.lms.shared.error.BusinessRuleViolationException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The freight-mode rules on the order and the load, without a database. */
class FreightModeTest {

    private static SalesOrder raise(FreightMode mode, UUID originHub, UUID destinationHub) {
        return SalesOrder.raise(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), "SO-1", UUID.randomUUID(), null, null, null,
                mode, originHub, destinationHub);
    }

    @Test
    void an_order_without_a_mode_is_a_full_truckload() {
        SalesOrder legacy = SalesOrder.raise(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), "SO-1", UUID.randomUUID(), null, null, null);

        assertThat(legacy.freightMode()).isEqualTo(FreightMode.FTL);
        assertThat(raise(null, null, null).freightMode()).isEqualTo(FreightMode.FTL);
    }

    @Test
    void a_part_load_names_both_hubs() {
        assertThatThrownBy(() -> raise(FreightMode.LTL, UUID.randomUUID(), null))
                .isInstanceOfSatisfying(BusinessRuleViolationException.class,
                        e -> assertThat(e.code()).isEqualTo("ltl-order-needs-hubs"));

        assertThat(raise(FreightMode.LTL, UUID.randomUUID(), UUID.randomUUID()).freightMode())
                .isEqualTo(FreightMode.LTL);
    }

    @Test
    void a_full_truckload_does_not_travel_through_hubs() {
        assertThatThrownBy(() -> raise(FreightMode.FTL, UUID.randomUUID(), UUID.randomUUID()))
                .isInstanceOfSatisfying(BusinessRuleViolationException.class,
                        e -> assertThat(e.code()).isEqualTo("hubs-only-for-ltl"));
    }

    @Test
    void reserved_modes_are_refused_rather_than_stored() {
        for (FreightMode reserved : new FreightMode[] {FreightMode.RAIL, FreightMode.MILK_RUN}) {
            assertThatThrownBy(() -> raise(reserved, null, null))
                    .isInstanceOfSatisfying(BusinessRuleViolationException.class,
                            e -> assertThat(e.code()).isEqualTo("freight-mode-not-supported"));
        }
    }

    @Test
    void the_mode_survives_a_status_change() {
        SalesOrder order = raise(FreightMode.LTL, UUID.randomUUID(), UUID.randomUUID());

        assertThat(order.transitionTo(SalesOrder.OrderStatus.VALIDATED).freightMode())
                .isEqualTo(FreightMode.LTL);
    }

    @Test
    void a_load_takes_only_its_own_mode() {
        LoadUnit ltl = LoadUnit.open(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "LOAD-1", UUID.randomUUID(), UUID.randomUUID(), "TRUCK", false,
                new BigDecimal("1000"), new BigDecimal("10"), FreightMode.LTL);

        ltl.requireSameMode(FreightMode.LTL, "DKT-1");
        assertThatThrownBy(() -> ltl.requireSameMode(FreightMode.FTL, "LR-1"))
                .isInstanceOfSatisfying(BusinessRuleViolationException.class,
                        e -> assertThat(e.code()).isEqualTo("load-freight-mode-mismatch"));
        // Building it keeps the mode; a capacity change must not reset it to FTL.
        assertThat(ltl.addConsignment(new BigDecimal("100"), BigDecimal.ONE, false).freightMode())
                .isEqualTo(FreightMode.LTL);
    }

    @Test
    void a_reserved_mode_cannot_be_planned() {
        assertThatThrownBy(() -> LoadUnit.open(UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), "LOAD-2", UUID.randomUUID(), null, "TRUCK", false,
                new BigDecimal("1000"), new BigDecimal("10"), FreightMode.RAIL))
                .isInstanceOfSatisfying(BusinessRuleViolationException.class,
                        e -> assertThat(e.code()).isEqualTo("freight-mode-not-supported"));
    }
}
