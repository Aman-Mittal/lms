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

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Decides which bin and lot each outbound line is picked from, for one order
 * or a whole wave.
 *
 * <p>Pure: it is handed what is wanted and what the bins hold, and answers
 * with the takes, touching nothing. The service then posts each take to the
 * ledger, which remains the only thing that moves stock -- and still refuses
 * a take the bins cannot cover if something else moved the stock first.
 *
 * <p>The rules:
 * <ul>
 *   <li>Demands are served in the order given -- the wave's order sequence,
 *       then line number -- and each takes all it can before the next starts.
 *       A shortfall therefore lands on the later demands, and is attributed
 *       to them and no others.</li>
 *   <li>Each SKU is taken by bin code, then lot, whichever order the
 *       demands arrive in: the same rule as a single-order pick.</li>
 *   <li>What one demand takes is gone for the next. The whole wave draws on
 *       one running balance per bin and lot, so it can never allocate more
 *       than a bin holds, however many orders want the same SKU.</li>
 * </ul>
 */
final class WaveAllocator {

    private WaveAllocator() {
    }

    /** One line's outstanding need. */
    record Demand(UUID orderId, int lineNo, UUID skuId, int quantity) {

        Demand {
            if (quantity < 0) {
                throw new IllegalArgumentException("A demand cannot be negative");
            }
        }
    }

    /** What one bin holds of one SKU and lot, available to pick. */
    record Source(UUID binId, String binCode, UUID skuId, String lotNo, int onHand) {
    }

    /** {@code quantity} of a demand's SKU, taken from one bin and lot. */
    record Take(int demandIndex, UUID binId, String lotNo, int quantity) {
    }

    /**
     * The outcome, aligned with the demands as given: {@code filled.get(i)}
     * is what demand {@code i} received, and the takes say where it came from.
     */
    record Allocation(List<Demand> demands, List<Take> takes, List<Integer> filled) {

        int filledFor(int demandIndex) {
            return filled.get(demandIndex);
        }

        int shortOf(int demandIndex) {
            return demands.get(demandIndex).quantity() - filled.get(demandIndex);
        }
    }

    private static final Comparator<Source> PICK_ORDER =
            Comparator.comparing(Source::binCode).thenComparing(Source::lotNo);

    static Allocation allocate(List<Demand> demands, List<Source> stock) {
        Map<UUID, List<int[]>> remainingBySku = new HashMap<>();
        Map<UUID, List<Source>> sourcesBySku = new HashMap<>();
        for (Source source : stock.stream().sorted(PICK_ORDER).toList()) {
            if (source.onHand() <= 0) {
                continue;
            }
            sourcesBySku.computeIfAbsent(source.skuId(), sku -> new ArrayList<>()).add(source);
            remainingBySku.computeIfAbsent(source.skuId(), sku -> new ArrayList<>())
                    .add(new int[] {source.onHand()});
        }

        List<Take> takes = new ArrayList<>();
        List<Integer> filled = new ArrayList<>(demands.size());
        for (int i = 0; i < demands.size(); i++) {
            Demand demand = demands.get(i);
            List<Source> sources = sourcesBySku.getOrDefault(demand.skuId(), List.of());
            List<int[]> remaining = remainingBySku.getOrDefault(demand.skuId(), List.of());
            int wanted = demand.quantity();
            for (int s = 0; s < sources.size() && wanted > 0; s++) {
                int[] left = remaining.get(s);
                int take = Math.min(wanted, left[0]);
                if (take == 0) {
                    continue;
                }
                left[0] -= take;
                wanted -= take;
                takes.add(new Take(i, sources.get(s).binId(), sources.get(s).lotNo(), take));
            }
            filled.add(demand.quantity() - wanted);
        }
        return new Allocation(List.copyOf(demands), List.copyOf(takes), List.copyOf(filled));
    }
}
