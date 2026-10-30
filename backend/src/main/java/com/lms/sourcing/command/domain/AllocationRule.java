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
package com.lms.sourcing.command.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.relational.core.mapping.Table;

/**
 * One tenant-written statement about who should carry a load.
 *
 * <p>Every condition it states must hold for a load; one left empty is not
 * tested. The weight band is inclusive at both ends, so adjoining bands (up to
 * 5000, from 5000) overlap on exactly 5000 and priority decides which wins.
 */
@Table("allocation_rule")
public record AllocationRule(
        @Id UUID id,
        UUID tenantId,
        String name,
        int priority,
        boolean active,
        UUID originTerminalId,
        UUID destinationTerminalId,
        BigDecimal minWeightKg,
        BigDecimal maxWeightKg,
        String materialClass,
        RuleAction action,
        UUID[] vendorPartnerIds,
        @Version Long version,
        Instant createdAt,
        Instant updatedAt) {

    /** What a rule does to the vendors a load would be offered to. */
    public enum RuleAction {
        /** Ask the listed vendors first, in the order listed, then everyone else as usual. */
        PREFER,
        /** Never ask the listed vendors for this load. */
        EXCLUDE
    }

    /** The facts about a load that a rule can test. */
    public record LoadFacts(UUID originTerminalId, UUID destinationTerminalId,
                            BigDecimal weightKg, Set<String> materialClasses) {
    }

    public static AllocationRule of(UUID tenantId, String name, int priority, boolean active,
                                    UUID origin, UUID destination, BigDecimal minWeightKg,
                                    BigDecimal maxWeightKg, String materialClass, RuleAction action,
                                    UUID[] vendors) {
        Instant now = Instant.now();
        return new AllocationRule(UUID.randomUUID(), tenantId, name, priority, active, origin,
                destination, minWeightKg, maxWeightKg, materialClass, action, vendors, null, now, now);
    }

    public AllocationRule revised(String name, int priority, boolean active, UUID origin,
                                  UUID destination, BigDecimal minWeightKg, BigDecimal maxWeightKg,
                                  String materialClass, RuleAction action, UUID[] vendors) {
        return new AllocationRule(id, tenantId, name, priority, active, origin, destination,
                minWeightKg, maxWeightKg, materialClass, action, vendors, version, createdAt,
                Instant.now());
    }

    /** Whether every condition this rule states holds for the load. */
    public boolean matches(LoadFacts load) {
        if (originTerminalId != null && !originTerminalId.equals(load.originTerminalId())) {
            return false;
        }
        if (destinationTerminalId != null && !destinationTerminalId.equals(load.destinationTerminalId())) {
            return false;
        }
        BigDecimal weight = load.weightKg();
        if (minWeightKg != null && (weight == null || weight.compareTo(minWeightKg) < 0)) {
            return false;
        }
        if (maxWeightKg != null && (weight == null || weight.compareTo(maxWeightKg) > 0)) {
            return false;
        }
        return materialClass == null || load.materialClasses().contains(materialClass);
    }

    /** Whether the vendor is one the rule names. */
    public boolean names(UUID vendorPartnerId) {
        for (UUID vendor : vendorPartnerIds) {
            if (vendor.equals(vendorPartnerId)) {
                return true;
            }
        }
        return false;
    }

    /** The position of the vendor in the rule's list, or -1. */
    public int positionOf(UUID vendorPartnerId) {
        for (int i = 0; i < vendorPartnerIds.length; i++) {
            if (vendorPartnerIds[i].equals(vendorPartnerId)) {
                return i;
            }
        }
        return -1;
    }
}
