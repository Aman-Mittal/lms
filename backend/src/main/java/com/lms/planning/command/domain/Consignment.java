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

import com.lms.order.api.FreightMode;
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
        /** The order it was cut from; null for one received from the warehouse. */
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
        /**
         * Inherited from the order. An LTL consignment is a <em>docket</em>:
         * the same tracking unit, numbered in the docket series and carrying
         * its own customer and hub pair, because it will share a vehicle with
         * other customers' dockets and be billed on its own.
         */
        FreightMode freightMode,
        /** Who is billed for a docket; recorded for FTL too, since it is free to know. */
        UUID customerPartnerId,
        /** The hub a docket is consolidated at; null for FTL. */
        UUID originHubTerminalId,
        /** The hub a docket's line haul runs to; null for FTL. */
        UUID destinationHubTerminalId,
        /**
         * The warehouse handling unit this consignment carries, when it came
         * from a warehouse hand-off rather than an order. Exactly one of this
         * and {@code orderId} is set, and a unit becomes at most one
         * consignment however often its dispatch is announced.
         */
        UUID handlingUnitId,
        @Version Long version,
        Instant createdAt,
        Instant updatedAt) {

    /**
     * Where a consignment is in its journey.
     *
     * <p>A full truckload moves once, origin to consignee, on one load:
     * <pre>
     *   PLANNED -> ASSIGNED_TO_LOAD -> IN_TRANSIT -> DELIVERED
     * </pre>
     *
     * <p>A part-load docket moves twice. The line haul carries it from its
     * origin hub to its destination hub with other customers' dockets; a local
     * delivery load then carries it from that hub to its consignee:
     * <pre>
     *   PLANNED
     *     -> ASSIGNED_TO_LOAD        on a LINE_HAUL load at its origin hub
     *     -> IN_TRANSIT              the line haul is dispatched
     *     -> ARRIVED_AT_HUB          the line haul's trip completes at the destination hub
     *     -> ASSIGNED_TO_DELIVERY    on a DELIVERY load from that hub
     *     -> OUT_FOR_DELIVERY        the delivery load is dispatched
     *     -> DELIVERED               the delivery load's trip completes
     * </pre>
     *
     * <p>Each hand-over can be undone only while nothing has moved:
     * ASSIGNED_TO_LOAD falls back to PLANNED, and ASSIGNED_TO_DELIVERY to
     * ARRIVED_AT_HUB, when the load is broken up before it leaves. A docket at
     * a hub is still somebody's freight in somebody's yard, so it can no
     * longer be cancelled -- only delivered.
     *
     * <p>The three hub states are part-load only; the database refuses them
     * on a full-truckload row.
     */
    public enum ConsignmentStatus {
        PLANNED, ASSIGNED_TO_LOAD, IN_TRANSIT, ARRIVED_AT_HUB, ASSIGNED_TO_DELIVERY,
        OUT_FOR_DELIVERY, DELIVERED, CANCELLED
    }

    private static final Set<ConsignmentStatus> HUB_STATES = EnumSet.of(
            ConsignmentStatus.ARRIVED_AT_HUB, ConsignmentStatus.ASSIGNED_TO_DELIVERY,
            ConsignmentStatus.OUT_FOR_DELIVERY);

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
        // DELIVERED for a full truckload; ARRIVED_AT_HUB for a docket, whose
        // line haul ends at a hub rather than at the consignee.
        TRANSITIONS.put(ConsignmentStatus.IN_TRANSIT,
                EnumSet.of(ConsignmentStatus.DELIVERED, ConsignmentStatus.ARRIVED_AT_HUB));
        TRANSITIONS.put(ConsignmentStatus.ARRIVED_AT_HUB,
                EnumSet.of(ConsignmentStatus.ASSIGNED_TO_DELIVERY));
        TRANSITIONS.put(ConsignmentStatus.ASSIGNED_TO_DELIVERY,
                EnumSet.of(ConsignmentStatus.OUT_FOR_DELIVERY, ConsignmentStatus.ARRIVED_AT_HUB));
        TRANSITIONS.put(ConsignmentStatus.OUT_FOR_DELIVERY,
                EnumSet.of(ConsignmentStatus.DELIVERED));
        TRANSITIONS.put(ConsignmentStatus.DELIVERED, EnumSet.noneOf(ConsignmentStatus.class));
        TRANSITIONS.put(ConsignmentStatus.CANCELLED, EnumSet.noneOf(ConsignmentStatus.class));
    }

    /**
     * A consignment for one consignee at one destination.
     *
     * @param originHubTerminalId      required for LTL, must be null otherwise
     * @param destinationHubTerminalId required for LTL, must be null otherwise
     */
    public static Consignment generate(UUID id, UUID tenantId, UUID orderId, String trackingRef,
                                       UUID consigneePartnerId, UUID destinationTerminalId,
                                       BigDecimal totalDeadWeightKg, BigDecimal totalVolumeM3,
                                       BigDecimal chargeableWeightKg, boolean containsHazmat,
                                       Collection<MaterialClass> materialClasses,
                                       FreightMode freightMode, UUID customerPartnerId,
                                       UUID originHubTerminalId, UUID destinationHubTerminalId) {
        if (totalDeadWeightKg == null || totalDeadWeightKg.signum() <= 0) {
            throw new IllegalArgumentException("A consignment must carry some weight");
        }
        FreightMode mode = FreightMode.orDefault(freightMode);
        if (mode == FreightMode.LTL && (originHubTerminalId == null
                || destinationHubTerminalId == null || customerPartnerId == null)) {
            throw new IllegalArgumentException(
                    "A docket needs a customer and both hubs to be consolidated and billed");
        }
        return new Consignment(id, tenantId, orderId, trackingRef, consigneePartnerId,
                destinationTerminalId, totalDeadWeightKg, totalVolumeM3, chargeableWeightKg,
                containsHazmat, ConsignmentStatus.PLANNED, encodeClasses(materialClasses),
                mode, customerPartnerId, originHubTerminalId, destinationHubTerminalId,
                null, null, Instant.now(), Instant.now());
    }

    /**
     * A consignment for one warehouse handling unit handed to transport.
     *
     * <p>One unit, one consignment: the unit was packed for one destination
     * and one consignee, and it travels under one lorry receipt. Always a
     * full-truckload consignment -- a part-load docket needs a customer to
     * bill and a hub pair, which a warehouse hand-off does not carry. The
     * unit's contents carry no dangerous-goods classification, so it is
     * treated as general cargo.
     */
    public static Consignment fromHandlingUnit(UUID id, UUID tenantId, UUID handlingUnitId,
                                               String trackingRef, UUID consigneePartnerId,
                                               UUID destinationTerminalId,
                                               BigDecimal grossWeightKg, BigDecimal volumeM3,
                                               BigDecimal chargeableWeightKg) {
        if (handlingUnitId == null) {
            throw new IllegalArgumentException("A warehouse consignment names its handling unit");
        }
        if (grossWeightKg == null || grossWeightKg.signum() <= 0) {
            throw new IllegalArgumentException("A consignment must carry some weight");
        }
        return new Consignment(id, tenantId, null, trackingRef, consigneePartnerId,
                destinationTerminalId, grossWeightKg, volumeM3, chargeableWeightKg, false,
                ConsignmentStatus.PLANNED, "", FreightMode.FTL, null, null, null,
                handlingUnitId, null, Instant.now(), Instant.now());
    }

    /** Whether this is an LTL docket rather than a full-truckload consignment. */
    public boolean isDocket() {
        return freightMode == FreightMode.LTL;
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
        if (HUB_STATES.contains(target) && !isDocket()) {
            throw new BusinessRuleViolationException("consignment-illegal-transition",
                    "Consignment " + trackingRef + " is a full truckload and never passes "
                            + "through a hub, so it cannot be " + target);
        }
        return new Consignment(id, tenantId, orderId, trackingRef, consigneePartnerId,
                destinationTerminalId, totalDeadWeightKg, totalVolumeM3, chargeableWeightKg,
                containsHazmat, target, materialClasses, freightMode, customerPartnerId,
                originHubTerminalId, destinationHubTerminalId, handlingUnitId, version, createdAt,
                Instant.now());
    }
}
