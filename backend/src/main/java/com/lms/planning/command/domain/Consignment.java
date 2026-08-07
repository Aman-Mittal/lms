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
package com.lms.planning.command.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collection;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;

import com.lms.order.api.MaterialClass;
import com.lms.shared.error.BusinessRuleViolationException;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.relational.core.mapping.Table;

/**
 * Goods travelling from one origin to one consignee at one destination
 * (vision document 3.4.1).
 *
 * <p>The unit the outside world tracks. Its {@code trackingRef} is the lorry
 * receipt or air waybill number a customer quotes on the phone, so it is
 * unique per tenant, generated once, and never reused -- reissuing a reference
 * would make two different shipments indistinguishable in every downstream
 * record that ever quoted it.
 */
@Table("consignment")
public record Consignment(
        @Id UUID id,
        UUID tenantId,
        UUID orderId,
        String trackingRef,
        UUID consigneePartnerId,
        UUID destinationTerminalId,
        BigDecimal totalDeadWeightKg,
        BigDecimal totalVolumeM3,
        BigDecimal chargeableWeightKg,
        boolean containsHazmat,
        ConsignmentStatus status,
        /**
         * The material classes inside, as a sorted comma-separated set.
         *
         * <p>Denormalised from the order lines so that putting two consignments
         * on one load does not require joining back through both orders. Safe
         * to denormalise because a consignment's contents are fixed the moment
         * it is generated.
         */
        String materialClasses,
        @Version Long version,
        Instant createdAt,
        Instant updatedAt) {

    public enum ConsignmentStatus {
        PLANNED, ASSIGNED_TO_LOAD, IN_TRANSIT, DELIVERED, CANCELLED
    }

    private static final Map<ConsignmentStatus, Set<ConsignmentStatus>> TRANSITIONS =
            new EnumMap<>(ConsignmentStatus.class);

    static {
        TRANSITIONS.put(ConsignmentStatus.PLANNED,
                EnumSet.of(ConsignmentStatus.ASSIGNED_TO_LOAD, ConsignmentStatus.CANCELLED));
        // Back to PLANNED when a load is broken up before it leaves: the goods
        // still need moving, they just need a different lorry.
        TRANSITIONS.put(ConsignmentStatus.ASSIGNED_TO_LOAD,
                EnumSet.of(ConsignmentStatus.IN_TRANSIT, ConsignmentStatus.PLANNED,
                        ConsignmentStatus.CANCELLED));
        TRANSITIONS.put(ConsignmentStatus.IN_TRANSIT,
                EnumSet.of(ConsignmentStatus.DELIVERED));
        TRANSITIONS.put(ConsignmentStatus.DELIVERED, EnumSet.noneOf(ConsignmentStatus.class));
        TRANSITIONS.put(ConsignmentStatus.CANCELLED, EnumSet.noneOf(ConsignmentStatus.class));
    }

    public static Consignment generate(UUID id, UUID tenantId, UUID orderId, String trackingRef,
                                       UUID consigneePartnerId, UUID destinationTerminalId,
                                       BigDecimal totalDeadWeightKg, BigDecimal totalVolumeM3,
                                       BigDecimal chargeableWeightKg, boolean containsHazmat,
                                       Collection<MaterialClass> materialClasses) {
        if (totalDeadWeightKg == null || totalDeadWeightKg.signum() <= 0) {
            throw new IllegalArgumentException("A consignment must carry some weight");
        }
        return new Consignment(id, tenantId, orderId, trackingRef, consigneePartnerId,
                destinationTerminalId, totalDeadWeightKg, totalVolumeM3, chargeableWeightKg,
                containsHazmat, ConsignmentStatus.PLANNED, encodeClasses(materialClasses),
                null, Instant.now(), Instant.now());
    }

    /**
     * Sorted before joining so the stored value is canonical: two consignments
     * holding the same classes must compare equal as strings, whatever order
     * their lines happened to be entered in.
     */
    private static String encodeClasses(Collection<MaterialClass> classes) {
        if (classes == null || classes.isEmpty()) {
            return "";
        }
        return String.join(",", classes.stream().map(Enum::name).collect(Collectors.toCollection(TreeSet::new)));
    }

    /** The material classes inside, decoded. */
    public Set<MaterialClass> materialClassSet() {
        if (materialClasses == null || materialClasses.isBlank()) {
            return EnumSet.noneOf(MaterialClass.class);
        }
        return Arrays.stream(materialClasses.split(","))
                .map(MaterialClass::valueOf)
                .collect(Collectors.toCollection(() -> EnumSet.noneOf(MaterialClass.class)));
    }

    public Consignment transitionTo(ConsignmentStatus target) {
        if (status == target) {
            return this;
        }
        if (!TRANSITIONS.getOrDefault(status, Set.of()).contains(target)) {
            throw new BusinessRuleViolationException("consignment-illegal-transition",
                    "Consignment " + trackingRef + " cannot move from " + status + " to " + target);
        }
        return new Consignment(id, tenantId, orderId, trackingRef, consigneePartnerId,
                destinationTerminalId, totalDeadWeightKg, totalVolumeM3, chargeableWeightKg,
                containsHazmat, target, materialClasses, version, createdAt, Instant.now());
    }
}
