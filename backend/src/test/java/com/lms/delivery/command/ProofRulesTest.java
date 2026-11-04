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
package com.lms.delivery.command;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import com.lms.delivery.command.domain.DeliveryProof;
import com.lms.delivery.command.domain.GoodsCondition;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** When a consignment may be proved delivered, and what a proof keeps of the goods' state. */
class ProofRulesTest {

    @Test
    @DisplayName("a full truckload is provable once it has left, by its status or, for an old one, its trip")
    void truckload() {
        assertThat(new DeliveryService.Standing("IN_TRANSIT", false, false).provable()).isTrue();
        assertThat(new DeliveryService.Standing("DELIVERED", false, false).provable()).isTrue();
        assertThat(new DeliveryService.Standing("ASSIGNED_TO_LOAD", false, true).provable()).isTrue();
        assertThat(new DeliveryService.Standing("ASSIGNED_TO_LOAD", false, false).provable()).isFalse();
        assertThat(new DeliveryService.Standing("PLANNED", false, false).provable()).isFalse();
    }

    @Test
    @DisplayName("a docket is provable only out for delivery from its destination hub")
    void docket() {
        assertThat(new DeliveryService.Standing("OUT_FOR_DELIVERY", true, true).provable()).isTrue();
        assertThat(new DeliveryService.Standing("DELIVERED", true, true).provable()).isTrue();
        assertThat(new DeliveryService.Standing("IN_TRANSIT", true, true).provable())
                .as("on its line haul").isFalse();
        assertThat(new DeliveryService.Standing("ARRIVED_AT_HUB", true, true).provable()).isFalse();
        assertThat(new DeliveryService.Standing("ASSIGNED_TO_LOAD", true, true).provable()).isFalse();
    }

    @Test
    @DisplayName("a proof keeps the remark of a shortage or damage, and none for clean goods")
    void condition() {
        DeliveryProof issued = DeliveryProof.issued(UUID.randomUUID(), UUID.randomUUID(), "hash",
                Instant.now(), Instant.now().plusSeconds(60));
        assertThat(issued.goodsCondition()).isEqualTo(GoodsCondition.CLEAN);

        DeliveryProof damaged = issued.confirmed("R", null, Instant.now(), BigDecimal.ONE, BigDecimal.ONE,
                GoodsCondition.DAMAGE, "Crushed");
        assertThat(damaged.clean()).isFalse();
        assertThat(damaged.remark()).isEqualTo("Crushed");

        DeliveryProof clean = issued.confirmed("R", null, Instant.now(), null, null, null, "ignored");
        assertThat(clean.clean()).isTrue();
        assertThat(clean.goodsCondition()).isEqualTo(GoodsCondition.CLEAN);
        assertThat(clean.remark()).isNull();
    }
}
