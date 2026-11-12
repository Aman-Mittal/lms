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
package com.lms.clientbilling.query;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * One lane of a contract version as a screen shows it: the places named by
 * code and terminal, the price, and the lists of who may consign and receive.
 *
 * @param originTerminalId      set when the origin is a terminal; otherwise {@code originZone} is
 * @param destinationTerminalId set when the destination is a terminal; otherwise {@code destinationZone} is
 * @param freightMode           FTL or LTL; null for either
 * @param loadType              null for any
 * @param rateBasis             how {@code rate} prices a movement: PER_KG, PER_TRIP or PER_TONNE
 * @param rate                  the price when no slab covers the weight; null when only slabs price the lane
 */
public record ContractLaneView(
        UUID id,
        UUID contractId,
        String laneCode,
        UUID originTerminalId,
        String originTerminalCode,
        String originZone,
        UUID destinationTerminalId,
        String destinationTerminalCode,
        String destinationZone,
        String freightMode,
        String loadType,
        String rateBasis,
        BigDecimal rate,
        Integer transitDays,
        List<Slab> slabs,
        List<UUID> consignorIds,
        List<UUID> consigneeIds) {

    public ContractLaneView {
        slabs = slabs == null ? List.of() : List.copyOf(slabs);
        consignorIds = consignorIds == null ? List.of() : List.copyOf(consignorIds);
        consigneeIds = consigneeIds == null ? List.of() : List.copyOf(consigneeIds);
    }

    /** A half-open weight band, [min, max) in kilograms. */
    public record Slab(BigDecimal minWeightKg, BigDecimal maxWeightKg, String rateBasis, BigDecimal rate) {
    }
}
