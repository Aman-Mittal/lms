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
package com.lms.clientbilling.command;

import java.math.BigDecimal;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;

import com.lms.clientbilling.command.domain.ContractLane;

/**
 * A lane as a caller states it, before it has an identity: what a draft is
 * written with and revised by.
 *
 * <p>An end is a terminal or a zone, one of the two; slabs are weight bands
 * that win over {@code rate} where they cover the weight.
 */
public record LaneTerm(String laneCode, UUID originTerminalId, String originZone, UUID destinationTerminalId,
                       String destinationZone, String freightMode, String loadType,
                       ContractLane.RateBasis rateBasis, BigDecimal rate, Integer transitDays,
                       List<SlabTerm> slabs, List<UUID> consignorIds, List<UUID> consigneeIds) {

    /** One weight band of a lane. */
    public record SlabTerm(BigDecimal minWeightKg, BigDecimal maxWeightKg, ContractLane.RateBasis rateBasis,
                           BigDecimal rate) {
    }

    /** The lane under a version, with a fresh identity. */
    ContractLane toLane(UUID contractId) {
        return new ContractLane(UUID.randomUUID(), contractId, laneCode,
                new ContractLane.Place(originTerminalId, originZone),
                new ContractLane.Place(destinationTerminalId, destinationZone), freightMode, loadType,
                rateBasis == null ? ContractLane.RateBasis.PER_KG : rateBasis, rate, transitDays,
                slabs == null ? List.of() : slabs.stream()
                        .map(s -> new ContractLane.Slab(s.minWeightKg(), s.maxWeightKg(),
                                s.rateBasis() == null ? ContractLane.RateBasis.PER_KG : s.rateBasis(), s.rate()))
                        .toList(),
                consignorIds == null ? null : new LinkedHashSet<>(consignorIds),
                consigneeIds == null ? null : new LinkedHashSet<>(consigneeIds));
    }
}
