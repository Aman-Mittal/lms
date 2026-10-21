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
package com.lms.warehouse.command;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

import com.lms.warehouse.command.WaveAllocator.Allocation;
import com.lms.warehouse.command.WaveAllocator.Demand;
import com.lms.warehouse.command.WaveAllocator.Source;
import com.lms.warehouse.command.WaveAllocator.Take;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;

class WaveAllocatorTest {

    private static final UUID WIDGET = UUID.randomUUID();
    private static final UUID GADGET = UUID.randomUUID();
    private static final UUID ORDER_1 = UUID.randomUUID();
    private static final UUID ORDER_2 = UUID.randomUUID();
    private static final UUID ORDER_3 = UUID.randomUUID();

    private final UUID binA = UUID.randomUUID();
    private final UUID binB = UUID.randomUUID();
    private final UUID binC = UUID.randomUUID();

    @Test
    @DisplayName("takes a SKU by bin code then lot, whatever order the stock arrives in")
    void binCodeThenLot() {
        List<Source> stock = List.of(
                new Source(binB, "B-01", WIDGET, "", 5),
                new Source(binA, "A-01", WIDGET, "LOT-2", 5),
                new Source(binA, "A-01", WIDGET, "LOT-1", 5));

        Allocation allocation = WaveAllocator.allocate(
                List.of(new Demand(ORDER_1, 1, WIDGET, 12)), stock);

        assertThat(allocation.takes())
                .extracting(Take::binId, Take::lotNo, Take::quantity)
                .containsExactly(
                        tuple(binA, "LOT-1", 5),
                        tuple(binA, "LOT-2", 5),
                        tuple(binB, "", 2));
        assertThat(allocation.filledFor(0)).isEqualTo(12);
        assertThat(allocation.shortOf(0)).isZero();
    }

    @Test
    @DisplayName("orders in a wave share one balance: what the first takes, the second cannot")
    void noOverAllocationAcrossTheWave() {
        List<Source> stock = List.of(
                new Source(binA, "A-01", WIDGET, "", 6),
                new Source(binB, "B-01", WIDGET, "", 4));

        Allocation allocation = WaveAllocator.allocate(List.of(
                new Demand(ORDER_1, 1, WIDGET, 7),
                new Demand(ORDER_2, 1, WIDGET, 7)), stock);

        assertThat(allocation.filledFor(0)).isEqualTo(7);
        assertThat(allocation.filledFor(1)).isEqualTo(3);
        assertThat(allocation.shortOf(1)).isEqualTo(4);
        assertThat(allocation.takes())
                .extracting(Take::demandIndex, Take::binId, Take::quantity)
                .containsExactly(
                        tuple(0, binA, 6),
                        tuple(0, binB, 1),
                        tuple(1, binB, 3));
    }

    @Test
    @DisplayName("a shortfall falls on the order that suffered it, SKU by SKU")
    void shortfallAttributedPerOrder() {
        List<Source> stock = List.of(
                new Source(binA, "A-01", WIDGET, "", 10),
                new Source(binC, "C-01", GADGET, "", 10));

        Allocation allocation = WaveAllocator.allocate(List.of(
                new Demand(ORDER_1, 1, WIDGET, 8),
                new Demand(ORDER_2, 1, GADGET, 4),
                new Demand(ORDER_2, 2, WIDGET, 5),
                new Demand(ORDER_3, 1, GADGET, 6)), stock);

        // Order 1 is served first and filled; order 2 gets its gadgets but
        // only the 2 widgets order 1 left; order 3 gets the remaining gadgets.
        assertThat(allocation.filled()).containsExactly(8, 4, 2, 6);
        assertThat(allocation.shortOf(0)).isZero();
        assertThat(allocation.shortOf(2)).isEqualTo(3);
        assertThat(allocation.shortOf(3)).isZero();
    }

    @Test
    @DisplayName("a SKU nobody holds is wholly short, and nothing is taken for it")
    void nothingInStock() {
        Allocation allocation = WaveAllocator.allocate(List.of(
                new Demand(ORDER_1, 1, GADGET, 3)),
                List.of(new Source(binA, "A-01", WIDGET, "", 10),
                        new Source(binB, "B-01", GADGET, "", 0)));

        assertThat(allocation.takes()).isEmpty();
        assertThat(allocation.shortOf(0)).isEqualTo(3);
    }

    @Test
    @DisplayName("a demand already filled takes nothing")
    void zeroDemand() {
        Allocation allocation = WaveAllocator.allocate(List.of(
                new Demand(ORDER_1, 1, WIDGET, 0),
                new Demand(ORDER_2, 1, WIDGET, 2)),
                List.of(new Source(binA, "A-01", WIDGET, "", 2)));

        assertThat(allocation.filled()).containsExactly(0, 2);
        assertThat(allocation.takes()).singleElement()
                .satisfies(take -> assertThat(take.demandIndex()).isEqualTo(1));
    }

    @Test
    @DisplayName("however a wave is shaped, no bin and lot gives more than it holds")
    void neverMoreThanOnHand() {
        Random random = new Random(42);
        UUID[] skus = {WIDGET, GADGET};
        UUID[] bins = {binA, binB, binC};
        for (int round = 0; round < 200; round++) {
            List<Source> stock = new java.util.ArrayList<>();
            Map<String, Integer> onHand = new HashMap<>();
            for (UUID bin : bins) {
                for (UUID sku : skus) {
                    for (String lot : List.of("", "L1")) {
                        int units = random.nextInt(6);
                        stock.add(new Source(bin, "BIN-" + bin, sku, lot, units));
                        onHand.put(bin + "/" + sku + "/" + lot, units);
                    }
                }
            }
            List<Demand> demands = new java.util.ArrayList<>();
            for (int d = 0; d < 1 + random.nextInt(8); d++) {
                demands.add(new Demand(UUID.randomUUID(), 1, skus[random.nextInt(2)],
                        random.nextInt(15)));
            }

            Allocation allocation = WaveAllocator.allocate(demands, stock);

            Map<String, Integer> taken = new HashMap<>();
            int[] perDemand = new int[demands.size()];
            for (Take take : allocation.takes()) {
                Demand demand = demands.get(take.demandIndex());
                taken.merge(take.binId() + "/" + demand.skuId() + "/" + take.lotNo(),
                        take.quantity(), Integer::sum);
                perDemand[take.demandIndex()] += take.quantity();
                assertThat(take.quantity()).isPositive();
            }
            taken.forEach((key, units) -> assertThat(units).isLessThanOrEqualTo(onHand.get(key)));
            for (int i = 0; i < demands.size(); i++) {
                assertThat(allocation.filledFor(i)).isEqualTo(perDemand[i])
                        .isLessThanOrEqualTo(demands.get(i).quantity());
                // Nobody is left short while the SKU is still on a shelf.
                if (allocation.shortOf(i) > 0) {
                    UUID sku = demands.get(i).skuId();
                    int held = stock.stream().filter(source -> source.skuId().equals(sku))
                            .mapToInt(Source::onHand).sum();
                    int given = allocation.takes().stream()
                            .filter(take -> demands.get(take.demandIndex()).skuId().equals(sku))
                            .mapToInt(Take::quantity).sum();
                    assertThat(given).isEqualTo(held);
                }
            }
        }
    }
}
