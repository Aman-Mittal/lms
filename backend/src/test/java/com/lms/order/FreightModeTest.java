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
import com.lms.planning.command.domain.Consignment;
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

    // ------------------------------------------------------- delivery leg

    @Test
    void only_a_part_load_has_a_delivery_leg() {
        assertThatThrownBy(() -> LoadUnit.open(UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), "LOAD-3", UUID.randomUUID(), null, "TRUCK", false,
                new BigDecimal("1000"), new BigDecimal("10"), FreightMode.FTL,
                LoadUnit.LtlLeg.DELIVERY))
                .isInstanceOfSatisfying(BusinessRuleViolationException.class,
                        e -> assertThat(e.code()).isEqualTo("delivery-leg-only-for-ltl"));

        LoadUnit delivery = LoadUnit.open(UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), "LOAD-4", UUID.randomUUID(), null, "TRUCK", false,
                new BigDecimal("1000"), new BigDecimal("10"), FreightMode.LTL,
                LoadUnit.LtlLeg.DELIVERY);
        assertThat(delivery.isDelivery()).isTrue();
        // The leg survives building and the lifecycle.
        assertThat(delivery.addConsignment(new BigDecimal("100"), BigDecimal.ONE, false)
                .transitionTo(LoadUnit.LoadStatus.PLANNED).isDelivery()).isTrue();
    }

    @Test
    void a_load_opened_without_a_leg_is_a_line_haul() {
        LoadUnit ltl = LoadUnit.open(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "LOAD-5", UUID.randomUUID(), null, "TRUCK", false,
                new BigDecimal("1000"), new BigDecimal("10"), FreightMode.LTL);

        assertThat(ltl.ltlLeg()).isEqualTo(LoadUnit.LtlLeg.LINE_HAUL);
        assertThat(ltl.isDelivery()).isFalse();
    }

    @Test
    void a_docket_walks_through_both_legs() {
        Consignment docket = docket();
        for (Consignment.ConsignmentStatus next : new Consignment.ConsignmentStatus[] {
                Consignment.ConsignmentStatus.ASSIGNED_TO_LOAD,
                Consignment.ConsignmentStatus.IN_TRANSIT,
                Consignment.ConsignmentStatus.ARRIVED_AT_HUB,
                Consignment.ConsignmentStatus.ASSIGNED_TO_DELIVERY,
                // A delivery load broken up before it leaves hands the docket
                // back to the hub, not to the line haul.
                Consignment.ConsignmentStatus.ARRIVED_AT_HUB,
                Consignment.ConsignmentStatus.ASSIGNED_TO_DELIVERY,
                Consignment.ConsignmentStatus.OUT_FOR_DELIVERY,
                Consignment.ConsignmentStatus.DELIVERED}) {
            docket = docket.transitionTo(next);
        }
        assertThat(docket.status()).isEqualTo(Consignment.ConsignmentStatus.DELIVERED);
    }

    @Test
    void a_docket_at_a_hub_cannot_be_cancelled_or_skip_its_delivery_load() {
        Consignment atHub = docket()
                .transitionTo(Consignment.ConsignmentStatus.ASSIGNED_TO_LOAD)
                .transitionTo(Consignment.ConsignmentStatus.IN_TRANSIT)
                .transitionTo(Consignment.ConsignmentStatus.ARRIVED_AT_HUB);

        for (Consignment.ConsignmentStatus illegal : new Consignment.ConsignmentStatus[] {
                Consignment.ConsignmentStatus.CANCELLED,
                Consignment.ConsignmentStatus.DELIVERED,
                Consignment.ConsignmentStatus.OUT_FOR_DELIVERY}) {
            assertThatThrownBy(() -> atHub.transitionTo(illegal))
                    .isInstanceOfSatisfying(BusinessRuleViolationException.class,
                            e -> assertThat(e.code()).isEqualTo("consignment-illegal-transition"));
        }
    }

    @Test
    void a_full_truckload_never_reaches_a_hub() {
        Consignment inTransit = Consignment.generate(UUID.randomUUID(), UUID.randomUUID(),
                        UUID.randomUUID(), "LR-1", UUID.randomUUID(), UUID.randomUUID(),
                        new BigDecimal("100"), BigDecimal.ONE, new BigDecimal("100"), false,
                        java.util.List.of(), FreightMode.FTL, UUID.randomUUID(), null, null)
                .transitionTo(Consignment.ConsignmentStatus.ASSIGNED_TO_LOAD)
                .transitionTo(Consignment.ConsignmentStatus.IN_TRANSIT);

        assertThatThrownBy(() -> inTransit.transitionTo(
                Consignment.ConsignmentStatus.ARRIVED_AT_HUB))
                .isInstanceOfSatisfying(BusinessRuleViolationException.class,
                        e -> assertThat(e.code()).isEqualTo("consignment-illegal-transition"));
        assertThat(inTransit.transitionTo(Consignment.ConsignmentStatus.DELIVERED).status())
                .isEqualTo(Consignment.ConsignmentStatus.DELIVERED);
    }

    private static Consignment docket() {
        return Consignment.generate(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "DKT-1", UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("100"),
                BigDecimal.ONE, new BigDecimal("100"), false, java.util.List.of(),
                FreightMode.LTL, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
    }
}
