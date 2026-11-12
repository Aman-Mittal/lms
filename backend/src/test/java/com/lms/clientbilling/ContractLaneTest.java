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
import java.util.List;
import java.util.UUID;

import com.lms.clientbilling.command.domain.ContractLane;
import com.lms.clientbilling.command.domain.ContractLane.RateBasis;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** A lane's rules and its rating, without a database. */
class ContractLaneTest {

    private static ContractLane lane(RateBasis basis, String rate, List<ContractLane.Slab> slabs) {
        return new ContractLane(UUID.randomUUID(), UUID.randomUUID(), " mum-del ",
                ContractLane.Place.terminal(UUID.randomUUID()), ContractLane.Place.zone("north"), null, " 32ft ",
                basis, rate == null ? null : new BigDecimal(rate), 3, slabs, null, null);
    }

    private static ContractLane.Slab slab(String min, String max, RateBasis basis, String rate) {
        return new ContractLane.Slab(new BigDecimal(min), max == null ? null : new BigDecimal(max), basis,
                new BigDecimal(rate));
    }

    @Test
    @DisplayName("a lane is named, its zone and load type are normalised, and it needs a price")
    void construction() {
        ContractLane lane = lane(RateBasis.PER_KG, "5", null);
        assertThat(lane.laneCode()).isEqualTo("mum-del");
        assertThat(lane.loadType()).isEqualTo("32FT");
        assertThat(lane.destination().zone()).isEqualTo("NORTH");
        assertThat(lane.consignees()).isEmpty();
        assertThatThrownBy(() -> lane(RateBasis.PER_KG, null, null)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("rate or weight slabs");
        assertThatThrownBy(() -> lane(RateBasis.PER_KG, "-1", null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ContractLane.Place.terminal(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ContractLane.Place(UUID.randomUUID(), "NORTH"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("weight bands are half open and may not overlap")
    void slabs() {
        assertThatThrownBy(() -> lane(RateBasis.PER_KG, null, List.of(
                slab("0", "1000", RateBasis.PER_KG, "12"), slab("999", null, RateBasis.PER_KG, "10"))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("overlap");
        assertThatThrownBy(() -> slab("5", "5", RateBasis.PER_KG, "1")).isInstanceOf(IllegalArgumentException.class);
        // Touching bands are fine: [0, 1000) and [1000, open).
        ContractLane lane = lane(RateBasis.PER_KG, null, List.of(slab("1000", null, RateBasis.PER_TONNE, "8000"),
                slab("0", "1000", RateBasis.PER_KG, "12")));
        assertThat(lane.slabs()).extracting(s -> s.minWeightKg().intValue()).containsExactly(0, 1000);
    }

    @Test
    @DisplayName("each basis turns a weight into money, rounded half up to two decimals")
    void rating() {
        BigDecimal kg = new BigDecimal("1234");
        ContractLane.Rating perKg = lane(RateBasis.PER_KG, "5.555", null).rate(kg).orElseThrow();
        assertThat(perKg.amount()).isEqualByComparingTo("6854.87");
        ContractLane.Rating perTonne = lane(RateBasis.PER_TONNE, "9000", null).rate(kg).orElseThrow();
        assertThat(perTonne.quantity()).isEqualByComparingTo("1.234");
        assertThat(perTonne.amount()).isEqualByComparingTo("11106.00");
        ContractLane.Rating perTrip = lane(RateBasis.PER_TRIP, "18000.505", null).rate(kg).orElseThrow();
        assertThat(perTrip.quantity()).isEqualByComparingTo("1");
        assertThat(perTrip.amount()).isEqualByComparingTo("18000.51");
    }

    @Test
    @DisplayName("the covering slab wins over the lane's own rate, which fills a gap; no rate at all is empty")
    void slabChoice() {
        ContractLane lane = lane(RateBasis.PER_KG, "20", List.of(
                slab("100", "500", RateBasis.PER_KG, "12"), slab("1000", null, RateBasis.PER_TONNE, "8000")));
        assertThat(lane.rate(new BigDecimal("100")).orElseThrow().rate()).isEqualByComparingTo("12");
        assertThat(lane.rate(new BigDecimal("499.999")).orElseThrow().rate()).isEqualByComparingTo("12");
        // 500 is in the gap between the bands: the lane's own rate.
        assertThat(lane.rate(new BigDecimal("500")).orElseThrow().rate()).isEqualByComparingTo("20");
        assertThat(lane.rate(new BigDecimal("50")).orElseThrow().rate()).isEqualByComparingTo("20");
        assertThat(lane.rate(new BigDecimal("1000")).orElseThrow().rateBasis()).isEqualTo(RateBasis.PER_TONNE);

        ContractLane slabsOnly = lane(RateBasis.PER_KG, null, List.of(slab("100", "500", RateBasis.PER_KG, "12")));
        assertThat(slabsOnly.rate(new BigDecimal("50"))).isEmpty();
    }

    @Test
    @DisplayName("a copy keeps every term under a new identity")
    void copy() {
        ContractLane lane = lane(RateBasis.PER_KG, "5", List.of(slab("0", null, RateBasis.PER_KG, "5")));
        UUID contract = UUID.randomUUID();
        ContractLane copy = lane.copiedTo(UUID.randomUUID(), contract);
        assertThat(copy.id()).isNotEqualTo(lane.id());
        assertThat(copy.contractId()).isEqualTo(contract);
        assertThat(copy).usingRecursiveComparison().ignoringFields("id", "contractId").isEqualTo(lane);
    }
}
