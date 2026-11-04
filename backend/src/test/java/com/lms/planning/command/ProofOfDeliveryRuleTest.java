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
package com.lms.planning.command;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import com.lms.order.api.FreightMode;
import com.lms.order.api.MaterialClass;
import com.lms.planning.command.domain.Consignment;
import com.lms.planning.command.domain.Consignment.ConsignmentStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Which consignments a confirmed proof of delivery may deliver. */
class ProofOfDeliveryRuleTest {

    private static Consignment truckload() {
        return Consignment.generate(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "LR-1",
                UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("1000"), BigDecimal.ONE,
                new BigDecimal("1000"), false, List.of(MaterialClass.GENERAL), FreightMode.FTL,
                UUID.randomUUID(), null, null);
    }

    private static Consignment docket() {
        return Consignment.generate(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "DKT-1",
                UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("100"), BigDecimal.ONE,
                new BigDecimal("100"), false, List.of(MaterialClass.GENERAL), FreightMode.LTL,
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
    }

    @Test
    @DisplayName("a full truckload that has left is delivered by its proof")
    void truckloadOnItsWay() {
        Consignment assigned = truckload().transitionTo(ConsignmentStatus.ASSIGNED_TO_LOAD);
        assertThat(ProofOfDeliveryService.deliverable(assigned))
                .as("dispatched before dispatch moved it").isTrue();
        assertThat(ProofOfDeliveryService.deliverable(
                assigned.transitionTo(ConsignmentStatus.IN_TRANSIT))).isTrue();
    }

    @Test
    @DisplayName("a full truckload not yet on a load, delivered or cancelled is left alone")
    void truckloadOtherwise() {
        Consignment planned = truckload();
        assertThat(ProofOfDeliveryService.deliverable(planned)).isFalse();
        assertThat(ProofOfDeliveryService.deliverable(planned.transitionTo(ConsignmentStatus.CANCELLED)))
                .isFalse();
        Consignment delivered = planned.transitionTo(ConsignmentStatus.ASSIGNED_TO_LOAD)
                .transitionTo(ConsignmentStatus.IN_TRANSIT).transitionTo(ConsignmentStatus.DELIVERED);
        assertThat(ProofOfDeliveryService.deliverable(delivered)).as("a repeat changes nothing").isFalse();
    }

    @Test
    @DisplayName("a docket is delivered by its proof only on its last leg")
    void docketOnlyOnItsLastLeg() {
        Consignment lineHaul = docket().transitionTo(ConsignmentStatus.ASSIGNED_TO_LOAD)
                .transitionTo(ConsignmentStatus.IN_TRANSIT);
        assertThat(ProofOfDeliveryService.deliverable(lineHaul)).as("on its line haul").isFalse();
        Consignment atHub = lineHaul.transitionTo(ConsignmentStatus.ARRIVED_AT_HUB);
        assertThat(ProofOfDeliveryService.deliverable(atHub)).as("waiting at the hub").isFalse();
        Consignment boarded = atHub.transitionTo(ConsignmentStatus.ASSIGNED_TO_DELIVERY);
        assertThat(ProofOfDeliveryService.deliverable(boarded)).as("boarded, not yet left").isFalse();
        assertThat(ProofOfDeliveryService.deliverable(
                boarded.transitionTo(ConsignmentStatus.OUT_FOR_DELIVERY))).isTrue();
    }
}
