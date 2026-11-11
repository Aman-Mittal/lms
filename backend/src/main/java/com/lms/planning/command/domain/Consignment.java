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
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
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
import org.springframework.data.relational.core.mapping.Embedded;
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
        /**
         * The consignment this is a part of, when a consignment too big for one
         * vehicle was split; null otherwise. See {@link #split}.
         */
        UUID parentConsignmentId,
        /**
         * What is said about the consignment rather than what it carries: the
         * consignee's contact and reference, the delivery window, the client's
         * reference and remarks. The one part that may be amended after
         * generation; see {@link #amended}.
         */
        @Embedded.Empty ConsignmentParticulars particulars,
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
     *
     * <p>A full truckload too big for one vehicle can be split while PLANNED:
     * <pre>
     *   PLANNED -> SPLIT             replaced by parts, each PLANNED on its own
     * </pre>
     * SPLIT is terminal. The consignment stays as the reference the customer
     * quoted, and its progress is its parts'.
     */
    public enum ConsignmentStatus {
        PLANNED, ASSIGNED_TO_LOAD, IN_TRANSIT, ARRIVED_AT_HUB, ASSIGNED_TO_DELIVERY,
        OUT_FOR_DELIVERY, DELIVERED, CANCELLED, SPLIT
    }

    /** The fewest parts a split makes; one part would be the same consignment renamed. */
    public static final int MIN_PARTS = 2;

    /**
     * The most parts one split makes. Enough for a few thousand tonnes on
     * ordinary lorries, and a bound on what one request writes.
     */
    public static final int MAX_PARTS = 500;

    /** The scale of the weight and volume columns. */
    private static final int SCALE = 3;

    private static final Set<ConsignmentStatus> HUB_STATES = EnumSet.of(
            ConsignmentStatus.ARRIVED_AT_HUB, ConsignmentStatus.ASSIGNED_TO_DELIVERY,
            ConsignmentStatus.OUT_FOR_DELIVERY);

    private static final Map<ConsignmentStatus, Set<ConsignmentStatus>> TRANSITIONS =
            new EnumMap<>(ConsignmentStatus.class);

    static {
        TRANSITIONS.put(ConsignmentStatus.PLANNED,
                EnumSet.of(ConsignmentStatus.ASSIGNED_TO_LOAD, ConsignmentStatus.CANCELLED,
                        ConsignmentStatus.SPLIT));
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
        TRANSITIONS.put(ConsignmentStatus.SPLIT, EnumSet.noneOf(ConsignmentStatus.class));
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
                null, null, ConsignmentParticulars.NONE, null, Instant.now(), Instant.now());
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
                handlingUnitId, null, ConsignmentParticulars.NONE, null, Instant.now(), Instant.now());
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

    /**
     * A delivery that rested on a proof the platform has since reversed: back
     * to where the proof found it -- in transit for a full truckload, out for
     * delivery for a docket. Only a DELIVERED consignment moves; the state
     * machine has no edge out of DELIVERED otherwise, and this is not one a
     * person can take by hand.
     */
    public Consignment proofReversed() {
        if (status != ConsignmentStatus.DELIVERED) {
            throw new BusinessRuleViolationException("consignment-illegal-transition",
                    "Consignment " + trackingRef + " is " + status + ", not delivered");
        }
        ConsignmentStatus back = isDocket() ? ConsignmentStatus.OUT_FOR_DELIVERY : ConsignmentStatus.IN_TRANSIT;
        return new Consignment(id, tenantId, orderId, trackingRef, consigneePartnerId,
                destinationTerminalId, totalDeadWeightKg, totalVolumeM3, chargeableWeightKg,
                containsHazmat, back, materialClasses, freightMode, customerPartnerId,
                originHubTerminalId, destinationHubTerminalId, handlingUnitId, parentConsignmentId,
                particulars, version, createdAt, Instant.now());
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
                originHubTerminalId, destinationHubTerminalId, handlingUnitId, parentConsignmentId,
                particulars, version, createdAt, Instant.now());
    }

    /**
     * The consignment taken off a load that has not left: back to the status
     * it boarded from, so it can be planned onto another vehicle. PLANNED from
     * a line haul or full truckload, ARRIVED_AT_HUB from a delivery load.
     *
     * <p>Anything else has already moved with its vehicle and is refused by
     * the state machine.
     */
    public Consignment releasedFromLoad() {
        if (status == ConsignmentStatus.ASSIGNED_TO_DELIVERY) {
            return transitionTo(ConsignmentStatus.ARRIVED_AT_HUB);
        }
        if (status == ConsignmentStatus.ASSIGNED_TO_LOAD) {
            return transitionTo(ConsignmentStatus.PLANNED);
        }
        throw new BusinessRuleViolationException("consignment-illegal-transition",
                "Consignment " + trackingRef + " is " + status + " and cannot be taken off its load");
    }

    /** Whether this consignment is one part of a split one. */
    public boolean isPart() {
        return parentConsignmentId != null;
    }

    /** The particulars, never null: a row read before they existed has none. */
    public ConsignmentParticulars particularsOrNone() {
        return particulars == null ? ConsignmentParticulars.NONE : particulars;
    }

    /**
     * The consignment with its particulars replaced and everything it carries
     * kept.
     *
     * <p>Only while PLANNED, which also means it is on no load: once a load
     * has been built around it, the vehicle, the drop order and the paperwork
     * were planned against what it said, and changing it then is a conversation
     * with the carrier rather than an edit. Refused with
     * {@code consignment-not-amendable} otherwise. The contents -- lines,
     * weight, volume, material -- are never amended here: they are fixed at
     * generation, and the planner cancels and regenerates instead.
     *
     * @param expectedVersion the version the caller read; the save is guarded on it
     */
    public Consignment amended(ConsignmentParticulars next, Long expectedVersion) {
        if (status != ConsignmentStatus.PLANNED) {
            throw new BusinessRuleViolationException("consignment-not-amendable",
                    "Consignment " + trackingRef + " is " + status + "; its particulars can be "
                            + "amended only while it is planned and not on a load");
        }
        return new Consignment(id, tenantId, orderId, trackingRef, consigneePartnerId,
                destinationTerminalId, totalDeadWeightKg, totalVolumeM3, chargeableWeightKg,
                containsHazmat, status, materialClasses, freightMode, customerPartnerId,
                originHubTerminalId, destinationHubTerminalId, handlingUnitId, parentConsignmentId,
                next, expectedVersion, createdAt, Instant.now());
    }

    /**
     * Refuses, by code, a consignment that cannot be cancelled on its own.
     *
     * <p>Cancellable while PLANNED or ASSIGNED_TO_LOAD -- the load it is on is
     * checked separately, since a load can leave before its consignments are
     * moved on. Refused: a repeat ({@code consignment-already-cancelled}); a
     * warehouse handling unit, which only the warehouse may withdraw
     * ({@code consignment-from-warehouse}); a split consignment, whose
     * progress is its parts' ({@code consignment-split}); a part, which is a
     * share of the consignment the customer booked
     * ({@code consignment-is-split-part}); and anything that has moved
     * ({@code consignment-dispatched}).
     */
    public void requireCancellable() {
        if (status == ConsignmentStatus.CANCELLED) {
            throw new BusinessRuleViolationException("consignment-already-cancelled",
                    "Consignment " + trackingRef + " is already cancelled");
        }
        if (handlingUnitId != null) {
            throw new BusinessRuleViolationException("consignment-from-warehouse",
                    "Consignment " + trackingRef + " carries a warehouse handling unit; only the "
                            + "warehouse can withdraw it");
        }
        if (status == ConsignmentStatus.SPLIT) {
            throw new BusinessRuleViolationException("consignment-split",
                    "Consignment " + trackingRef + " has been split; cancel its order, or "
                            + "its parts' loads, instead");
        }
        if (isPart()) {
            throw new BusinessRuleViolationException("consignment-is-split-part",
                    "Consignment " + trackingRef + " is one part of a split consignment and "
                            + "is not cancelled on its own");
        }
        if (status != ConsignmentStatus.PLANNED && status != ConsignmentStatus.ASSIGNED_TO_LOAD) {
            throw new BusinessRuleViolationException("consignment-dispatched",
                    "Consignment " + trackingRef + " is " + status
                            + " and can no longer be cancelled");
        }
    }

    /** What one part carries, as the planner states it. */
    public record PartSpec(BigDecimal deadWeightKg, BigDecimal volumeM3) {
    }

    /** The outcome of a split: this consignment, now SPLIT, and its parts in order. */
    public record Split(Consignment parent, List<Consignment> parts) {
    }

    /**
     * Replaces this consignment with parts, each its own full truckload.
     *
     * <p>Every part goes to the same consignee and destination, for the same
     * customer, under the same order, and carries the same material classes and
     * the same dangerous-goods flag as the whole: what is in a part was never
     * declared line by line, so each is treated as possibly holding any of it.
     * The planner states each part's dead weight and volume, and they must add
     * up exactly to the whole -- a split moves freight between vehicles, it
     * does not create or lose any.
     *
     * <p>The chargeable weight is shared out in proportion to dead weight, each
     * share rounded down to the gram, and the last part takes what rounding left
     * over. Rounding down is what keeps that remainder from going negative,
     * however many parts and however small the last.
     *
     * <p>Refused, each by its own code, for a docket (consolidated and billed
     * as one with other customers'), for anything but a PLANNED full
     * truckload, for a part (one level only), and for a warehouse unit (one
     * packed unit, weighed at the door).
     *
     * @param partIds one fresh id per part
     * @param refs    one fresh tracking reference per part
     */
    public Split split(List<PartSpec> specs, List<UUID> partIds, List<String> refs) {
        if (isDocket()) {
            throw new BusinessRuleViolationException("docket-cannot-be-split",
                    "Docket " + trackingRef + " is consolidated with other customers' dockets "
                            + "and billed as one; only a full truckload can be split");
        }
        if (status != ConsignmentStatus.PLANNED || freightMode != FreightMode.FTL) {
            throw new BusinessRuleViolationException("consignment-not-splittable",
                    "Consignment " + trackingRef + " is " + status + " " + freightMode
                            + "; only a planned full truckload not yet on a load can be split");
        }
        if (isPart()) {
            throw new BusinessRuleViolationException("consignment-not-splittable",
                    "Consignment " + trackingRef + " is already one part of a split "
                            + "consignment, and parts are not split again");
        }
        if (handlingUnitId != null) {
            throw new BusinessRuleViolationException("consignment-not-splittable",
                    "Consignment " + trackingRef + " carries one warehouse handling unit, "
                            + "which travels whole");
        }
        int count = specs == null ? 0 : specs.size();
        if (count < MIN_PARTS || count > MAX_PARTS) {
            throw new BusinessRuleViolationException("split-part-count-invalid",
                    "A split makes " + MIN_PARTS + " to " + MAX_PARTS + " parts, not " + count);
        }
        if (partIds.size() != count || refs.size() != count) {
            throw new IllegalArgumentException("One id and one reference per part");
        }

        BigDecimal weight = BigDecimal.ZERO;
        BigDecimal volume = BigDecimal.ZERO;
        for (int i = 0; i < count; i++) {
            PartSpec spec = specs.get(i);
            if (spec == null || spec.deadWeightKg() == null || spec.deadWeightKg().signum() <= 0
                    || spec.volumeM3() == null || spec.volumeM3().signum() < 0
                    || spec.deadWeightKg().stripTrailingZeros().scale() > SCALE
                    || spec.volumeM3().stripTrailingZeros().scale() > SCALE) {
                throw new BusinessRuleViolationException("split-part-invalid",
                        "Part " + (i + 1) + " needs a weight above zero and a volume of zero or "
                                + "more, each to at most " + SCALE + " decimal places");
            }
            weight = weight.add(spec.deadWeightKg());
            volume = volume.add(spec.volumeM3());
        }
        BigDecimal wholeVolume = totalVolumeM3 == null ? BigDecimal.ZERO : totalVolumeM3;
        if (weight.compareTo(totalDeadWeightKg) != 0) {
            throw new BusinessRuleViolationException("split-weights-do-not-add-up",
                    "The parts weigh " + plain(weight) + " kg but consignment " + trackingRef
                            + " weighs " + plain(totalDeadWeightKg) + " kg, a difference of "
                            + plain(weight.subtract(totalDeadWeightKg)) + " kg");
        }
        if (volume.compareTo(wholeVolume) != 0) {
            throw new BusinessRuleViolationException("split-volumes-do-not-add-up",
                    "The parts measure " + plain(volume) + " m3 but consignment " + trackingRef
                            + " measures " + plain(wholeVolume) + " m3, a difference of "
                            + plain(volume.subtract(wholeVolume)) + " m3");
        }

        List<BigDecimal> chargeable = apportion(chargeableWeightKg, specs, totalDeadWeightKg);
        List<Consignment> parts = new ArrayList<>(count);
        Instant now = Instant.now();
        for (int i = 0; i < count; i++) {
            PartSpec spec = specs.get(i);
            parts.add(new Consignment(partIds.get(i), tenantId, orderId, refs.get(i),
                    consigneePartnerId, destinationTerminalId, spec.deadWeightKg(),
                    spec.volumeM3(), chargeable.get(i), containsHazmat, ConsignmentStatus.PLANNED,
                    materialClasses, freightMode, customerPartnerId, null, null, null, id,
                    particularsOrNone().withoutClientReference(), null, now, now));
        }
        return new Split(transitionTo(ConsignmentStatus.SPLIT), List.copyOf(parts));
    }

    /** A quantity as a person writes it: no trailing zeros, no exponent. */
    private static String plain(BigDecimal value) {
        return value.signum() == 0 ? "0" : value.stripTrailingZeros().toPlainString();
    }

    /**
     * Shares a total out in proportion to each part's weight: every share but
     * the last rounded down, the last taking the remainder.
     */
    static List<BigDecimal> apportion(BigDecimal total, List<PartSpec> specs, BigDecimal byWeight) {
        BigDecimal whole = total == null ? BigDecimal.ZERO : total;
        List<BigDecimal> shares = new ArrayList<>(specs.size());
        BigDecimal given = BigDecimal.ZERO;
        for (int i = 0; i < specs.size() - 1; i++) {
            BigDecimal share = whole.multiply(specs.get(i).deadWeightKg())
                    .divide(byWeight, SCALE, RoundingMode.DOWN);
            shares.add(share);
            given = given.add(share);
        }
        shares.add(whole.subtract(given).setScale(SCALE, RoundingMode.UNNECESSARY));
        return shares;
    }

    /**
     * The tracking reference of part {@code index} (from 1) of {@code count}:
     * the whole's, then a dash and the part number zero-padded to the width of
     * the count, never under two digits -- {@code LR-AB12-01} to
     * {@code LR-AB12-12}, {@code LR-AB12-001} to {@code LR-AB12-120}. The
     * customer's reference stays readable inside every part's.
     */
    public static String partRef(String wholeRef, int index, int count) {
        int width = Math.max(2, String.valueOf(count).length());
        return wholeRef + "-" + String.format(java.util.Locale.ROOT, "%0" + width + "d", index);
    }
}
