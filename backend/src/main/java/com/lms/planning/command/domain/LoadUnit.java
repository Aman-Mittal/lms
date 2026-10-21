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
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.lms.order.api.FreightMode;
import com.lms.shared.error.BusinessRuleViolationException;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.relational.core.mapping.Table;

/**
 * One physical transport unit: consignments aggregated onto a single vehicle
 * (vision document 3.4.2).
 *
 * <p>Capacities are copied onto the load when it is built rather than looked up
 * from the vehicle each time. A load planned against yesterday's fleet
 * definition must keep being judged against yesterday's numbers -- if someone
 * corrects a vehicle's tare weight next week, loads already planned must not
 * silently become over- or under-loaded.
 */
@Table("load_unit")
public record LoadUnit(
        @Id UUID id,
        UUID tenantId,
        UUID orgUnitId,
        String loadNo,
        UUID originTerminalId,
        UUID vehicleId,
        String vehicleType,
        boolean vehicleHazmatCertified,
        BigDecimal capacityWeightKg,
        BigDecimal capacityVolumeM3,
        BigDecimal plannedWeightKg,
        BigDecimal plannedVolumeM3,
        boolean requiresHazmat,
        UUID awardedVendorPartnerId,
        LoadStatus status,
        /**
         * FTL loads carry FTL consignments and LTL loads carry dockets; the
         * two never mix. A docket on an FTL lorry would be billed per docket
         * for space the customer of the lorry had already paid for.
         */
        FreightMode freightMode,
        /**
         * Which leg of a part-load journey the load runs. A LINE_HAUL load
         * consolidates dockets at their origin hub and carries them to their
         * destination hub; a DELIVERY load starts at a destination hub and
         * drops dockets that have arrived there at their consignees. Always
         * LINE_HAUL for a full truckload, which is its own delivery.
         */
        LtlLeg ltlLeg,
        @Version Long version,
        Instant createdAt,
        Instant updatedAt) {

    /** See {@link #ltlLeg()}. */
    public enum LtlLeg {
        LINE_HAUL, DELIVERY
    }

    public enum LoadStatus {
        DRAFT, PLANNED, AWARDED, DISPATCHED, COMPLETED, CANCELLED
    }

    private static final Map<LoadStatus, Set<LoadStatus>> TRANSITIONS = new EnumMap<>(LoadStatus.class);

    static {
        TRANSITIONS.put(LoadStatus.DRAFT, EnumSet.of(LoadStatus.PLANNED, LoadStatus.CANCELLED));
        TRANSITIONS.put(LoadStatus.PLANNED, EnumSet.of(LoadStatus.AWARDED, LoadStatus.DRAFT, LoadStatus.CANCELLED));
        TRANSITIONS.put(LoadStatus.AWARDED, EnumSet.of(LoadStatus.DISPATCHED, LoadStatus.CANCELLED));
        TRANSITIONS.put(LoadStatus.DISPATCHED, EnumSet.of(LoadStatus.COMPLETED));
        // Terminal states.
        TRANSITIONS.put(LoadStatus.COMPLETED, EnumSet.noneOf(LoadStatus.class));
        TRANSITIONS.put(LoadStatus.CANCELLED, EnumSet.noneOf(LoadStatus.class));
    }

    /**
     * Opens a load sized against a nominal vehicle type, with no specific
     * vehicle behind it and therefore no dangerous goods certification.
     */
    public static LoadUnit open(UUID id, UUID tenantId, UUID orgUnitId, String loadNo,
                                UUID originTerminalId, String vehicleType,
                                BigDecimal capacityWeightKg, BigDecimal capacityVolumeM3) {
        return open(id, tenantId, orgUnitId, loadNo, originTerminalId, null, vehicleType,
                false, capacityWeightKg, capacityVolumeM3);
    }

    /**
     * Opens a load against a specific vehicle, carrying its certification.
     *
     * <p>{@code vehicleHazmatCertified} is copied in rather than looked up on
     * each assignment, for the same reason the capacities are: the plan must
     * keep being judged against the fleet definition it was built from.
     */
    public static LoadUnit open(UUID id, UUID tenantId, UUID orgUnitId, String loadNo,
                                UUID originTerminalId, UUID vehicleId, String vehicleType,
                                boolean vehicleHazmatCertified,
                                BigDecimal capacityWeightKg, BigDecimal capacityVolumeM3) {
        return open(id, tenantId, orgUnitId, loadNo, originTerminalId, vehicleId, vehicleType,
                vehicleHazmatCertified, capacityWeightKg, capacityVolumeM3, FreightMode.FTL);
    }

    /**
     * Opens a load in a stated freight mode.
     *
     * <p>An LTL load is a consolidation: its origin is the hub its dockets are
     * gathered at, and it is open to dockets from any number of customers.
     */
    public static LoadUnit open(UUID id, UUID tenantId, UUID orgUnitId, String loadNo,
                                UUID originTerminalId, UUID vehicleId, String vehicleType,
                                boolean vehicleHazmatCertified,
                                BigDecimal capacityWeightKg, BigDecimal capacityVolumeM3,
                                FreightMode freightMode) {
        return open(id, tenantId, orgUnitId, loadNo, originTerminalId, vehicleId, vehicleType,
                vehicleHazmatCertified, capacityWeightKg, capacityVolumeM3, freightMode,
                LtlLeg.LINE_HAUL);
    }

    /**
     * Opens a load in a stated freight mode and, for a part load, a stated leg.
     *
     * <p>A DELIVERY load's origin is the destination hub its dockets have
     * arrived at. Only a part load has a delivery leg: a full truckload
     * already ends at its consignee, and a second load for it would be a
     * second lorry nobody needs.
     */
    public static LoadUnit open(UUID id, UUID tenantId, UUID orgUnitId, String loadNo,
                                UUID originTerminalId, UUID vehicleId, String vehicleType,
                                boolean vehicleHazmatCertified,
                                BigDecimal capacityWeightKg, BigDecimal capacityVolumeM3,
                                FreightMode freightMode, LtlLeg ltlLeg) {
        FreightMode mode = FreightMode.orDefault(freightMode);
        if (!mode.isSupported()) {
            throw new BusinessRuleViolationException("freight-mode-not-supported",
                    "Freight mode " + mode + " is reserved and cannot be planned yet");
        }
        LtlLeg leg = ltlLeg == null ? LtlLeg.LINE_HAUL : ltlLeg;
        if (leg == LtlLeg.DELIVERY && mode != FreightMode.LTL) {
            throw new BusinessRuleViolationException("delivery-leg-only-for-ltl",
                    "Load " + loadNo + " is " + mode + "; only a part load has a separate "
                            + "delivery leg");
        }
        if (capacityWeightKg == null || capacityWeightKg.signum() <= 0) {
            throw new IllegalArgumentException("Load capacity weight must be positive");
        }
        if (capacityVolumeM3 == null || capacityVolumeM3.signum() <= 0) {
            throw new IllegalArgumentException("Load capacity volume must be positive");
        }
        return new LoadUnit(id, tenantId, orgUnitId, loadNo, originTerminalId, vehicleId,
                vehicleType, vehicleHazmatCertified, capacityWeightKg, capacityVolumeM3,
                BigDecimal.ZERO, BigDecimal.ZERO, false, null, LoadStatus.DRAFT, mode, leg, null,
                Instant.now(), Instant.now());
    }

    /**
     * Refuses a consignment of the other freight mode.
     *
     * <p>Checked before capacity, because it is the more fundamental refusal:
     * telling a planner a docket would overload an FTL lorry invites them to
     * find a bigger FTL lorry, which is the wrong fix.
     */
    public void requireSameMode(FreightMode consignmentMode, String trackingRef) {
        if (FreightMode.orDefault(consignmentMode) != freightMode) {
            throw new BusinessRuleViolationException("load-freight-mode-mismatch",
                    "Load " + loadNo + " is " + freightMode + " and cannot take "
                            + FreightMode.orDefault(consignmentMode) + " consignment "
                            + trackingRef);
        }
    }

    /** Whether this load carries dockets from a destination hub to their consignees. */
    public boolean isDelivery() {
        return ltlLeg == LtlLeg.DELIVERY;
    }

    public BigDecimal remainingWeightKg() {
        return capacityWeightKg.subtract(plannedWeightKg);
    }

    public BigDecimal remainingVolumeM3() {
        return capacityVolumeM3.subtract(plannedVolumeM3);
    }

    /** Whether the load could take this much more without exceeding capacity. */
    public boolean canAccommodate(BigDecimal weightKg, BigDecimal volumeM3) {
        return plannedWeightKg.add(weightKg).compareTo(capacityWeightKg) <= 0
                && plannedVolumeM3.add(volumeM3).compareTo(capacityVolumeM3) <= 0;
    }

    /**
     * Adds a consignment's weight and volume.
     *
     * <p>The hard stop of 3.4.2: "The aggregated weight and volume of the
     * Consignments MUST NOT exceed the selected Vehicle Type's maximum
     * capacity." Both dimensions are checked, and both are reported, because a
     * load can be within weight and over volume -- light bulky freight fills a
     * trailer long before it reaches the axle limit, and an operator told only
     * about weight would keep trying.
     */
    public LoadUnit addConsignment(BigDecimal weightKg, BigDecimal volumeM3, boolean hazmat) {
        if (status != LoadStatus.DRAFT && status != LoadStatus.PLANNED) {
            throw new BusinessRuleViolationException("load-not-open",
                    "Load " + loadNo + " is " + status + " and can no longer be built");
        }

        // 3.2.2 read through to load building: certification belongs to the
        // individual vehicle, not to its type, so this cannot be inferred from
        // `vehicleType` and has to be carried on the load.
        if (hazmat && vehicleId != null && !vehicleHazmatCertified) {
            throw new BusinessRuleViolationException("load-not-hazmat-certified",
                    "Load " + loadNo + " is built on a vehicle that is not certified "
                            + "to carry dangerous goods");
        }

        BigDecimal newWeight = plannedWeightKg.add(weightKg);
        BigDecimal newVolume = plannedVolumeM3.add(volumeM3);

        boolean overWeight = newWeight.compareTo(capacityWeightKg) > 0;
        boolean overVolume = newVolume.compareTo(capacityVolumeM3) > 0;

        if (overWeight || overVolume) {
            StringBuilder detail = new StringBuilder("Load " + loadNo + " would exceed ");
            if (overWeight) {
                detail.append("weight capacity (")
                        .append(newWeight).append(" kg against ")
                        .append(capacityWeightKg).append(" kg)");
            }
            if (overWeight && overVolume) {
                detail.append(" and ");
            }
            if (overVolume) {
                detail.append("volume capacity (")
                        .append(newVolume).append(" m3 against ")
                        .append(capacityVolumeM3).append(" m3)");
            }
            throw new BusinessRuleViolationException("load-capacity-exceeded", detail.toString());
        }

        return new LoadUnit(id, tenantId, orgUnitId, loadNo, originTerminalId, vehicleId,
                vehicleType, vehicleHazmatCertified, capacityWeightKg, capacityVolumeM3,
                newWeight, newVolume, requiresHazmat || hazmat, awardedVendorPartnerId, status,
                freightMode, ltlLeg, version, createdAt, Instant.now());
    }

    public LoadUnit transitionTo(LoadStatus target) {
        if (status == target) {
            return this;
        }
        if (!TRANSITIONS.getOrDefault(status, Set.of()).contains(target)) {
            throw new BusinessRuleViolationException("load-illegal-transition",
                    "Load " + loadNo + " cannot move from " + status + " to " + target);
        }
        return new LoadUnit(id, tenantId, orgUnitId, loadNo, originTerminalId, vehicleId,
                vehicleType, vehicleHazmatCertified, capacityWeightKg, capacityVolumeM3,
                plannedWeightKg, plannedVolumeM3, requiresHazmat, awardedVendorPartnerId, target,
                freightMode, ltlLeg, version, createdAt, Instant.now());
    }

    /**
     * Records the vendor that has taken the load, and moves it to AWARDED.
     *
     * <p>The vendor is kept on the load, not only on the allocation that
     * produced it. Who is moving a load is a fact about the load: a load
     * allocated by a phone call tomorrow has a vendor and no allocation record
     * at all, and execution must not have to know which of those happened.
     */
    public LoadUnit awardTo(UUID vendorPartnerId) {
        if (vendorPartnerId == null) {
            throw new IllegalArgumentException("An award must name a vendor");
        }
        LoadUnit awarded = transitionTo(LoadStatus.AWARDED);
        return new LoadUnit(awarded.id, awarded.tenantId, awarded.orgUnitId, awarded.loadNo,
                awarded.originTerminalId, awarded.vehicleId, awarded.vehicleType,
                awarded.vehicleHazmatCertified, awarded.capacityWeightKg, awarded.capacityVolumeM3,
                awarded.plannedWeightKg, awarded.plannedVolumeM3, awarded.requiresHazmat,
                vendorPartnerId, awarded.status, awarded.freightMode, awarded.ltlLeg,
                awarded.version,
                awarded.createdAt, Instant.now());
    }

    /** Percentage of weight capacity used, for the utilisation the platform exists to improve. */
    public BigDecimal weightUtilisationPct() {
        return plannedWeightKg.multiply(BigDecimal.valueOf(100))
                .divide(capacityWeightKg, 2, java.math.RoundingMode.HALF_UP);
    }
}
