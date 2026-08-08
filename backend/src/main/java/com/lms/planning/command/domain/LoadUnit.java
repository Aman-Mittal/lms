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
        @Version Long version,
        Instant createdAt,
        Instant updatedAt) {

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
        if (capacityWeightKg == null || capacityWeightKg.signum() <= 0) {
            throw new IllegalArgumentException("Load capacity weight must be positive");
        }
        if (capacityVolumeM3 == null || capacityVolumeM3.signum() <= 0) {
            throw new IllegalArgumentException("Load capacity volume must be positive");
        }
        return new LoadUnit(id, tenantId, orgUnitId, loadNo, originTerminalId, vehicleId,
                vehicleType, vehicleHazmatCertified, capacityWeightKg, capacityVolumeM3,
                BigDecimal.ZERO, BigDecimal.ZERO, false, null, LoadStatus.DRAFT, null,
                Instant.now(), Instant.now());
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
                version, createdAt, Instant.now());
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
                version, createdAt, Instant.now());
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
                vendorPartnerId, awarded.status, awarded.version, awarded.createdAt, Instant.now());
    }

    /** Percentage of weight capacity used, for the utilisation the platform exists to improve. */
    public BigDecimal weightUtilisationPct() {
        return plannedWeightKg.multiply(BigDecimal.valueOf(100))
                .divide(capacityWeightKg, 2, java.math.RoundingMode.HALF_UP);
    }
}
