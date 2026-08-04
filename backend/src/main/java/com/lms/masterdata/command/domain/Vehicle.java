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
package com.lms.masterdata.command.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.relational.core.mapping.Table;

/**
 * The digital twin of a physical vehicle (vision document 3.2.2).
 *
 * <p>Carries the hard capacities that load building must not exceed, and the
 * flags that gate what it may carry.
 */
@Table("vehicle")
public record Vehicle(
        @Id UUID id,
        UUID tenantId,
        UUID orgUnitId,
        UUID ownerPartnerId,
        String registrationNo,
        String category,
        String vehicleType,
        String axleConfig,
        BigDecimal grossWeightKg,
        BigDecimal tareWeightKg,
        BigDecimal maxVolumeM3,
        BigDecimal lengthM,
        BigDecimal widthM,
        BigDecimal heightM,
        VehicleStatus status,
        boolean hazmatCertified,
        boolean reeferCapable,
        @Version Long version,
        Instant createdAt,
        Instant updatedAt) {

    public enum VehicleStatus {
        AVAILABLE, ON_TRIP, MAINTENANCE, RETIRED
    }

    public static Vehicle register(UUID id, UUID tenantId, UUID orgUnitId, UUID ownerPartnerId,
                                   String registrationNo, String category, String vehicleType,
                                   String axleConfig, BigDecimal grossWeightKg, BigDecimal tareWeightKg,
                                   BigDecimal maxVolumeM3, BigDecimal lengthM, BigDecimal widthM,
                                   BigDecimal heightM, boolean hazmatCertified, boolean reeferCapable) {
        if (grossWeightKg == null || tareWeightKg == null || maxVolumeM3 == null) {
            throw new IllegalArgumentException("Gross weight, tare weight and volume are mandatory");
        }
        if (tareWeightKg.compareTo(grossWeightKg) >= 0) {
            // Mirrors the database check. Caught here so the caller gets a clear
            // message rather than a constraint violation, and because a vehicle
            // with negative payload capacity would silently reject every load.
            throw new IllegalArgumentException(
                    "Tare weight " + tareWeightKg + " must be less than gross weight " + grossWeightKg);
        }
        if (maxVolumeM3.signum() <= 0) {
            throw new IllegalArgumentException("Maximum volume must be positive");
        }

        return new Vehicle(id, tenantId, orgUnitId, ownerPartnerId, registrationNo, category,
                vehicleType, axleConfig, grossWeightKg, tareWeightKg, maxVolumeM3,
                lengthM, widthM, heightM, VehicleStatus.AVAILABLE,
                hazmatCertified, reeferCapable, null, Instant.now(), Instant.now());
    }

    /**
     * Usable payload: gross vehicle weight less its own tare.
     *
     * <p>The number load building checks against, not {@link #grossWeightKg},
     * which is the laden total and would over-allocate every vehicle by its own
     * mass.
     */
    public BigDecimal payloadCapacityKg() {
        return grossWeightKg.subtract(tareWeightKg);
    }

    public boolean isAvailable() {
        return status == VehicleStatus.AVAILABLE;
    }

    public Vehicle withStatus(VehicleStatus newStatus) {
        return new Vehicle(id, tenantId, orgUnitId, ownerPartnerId, registrationNo, category,
                vehicleType, axleConfig, grossWeightKg, tareWeightKg, maxVolumeM3,
                lengthM, widthM, heightM, newStatus, hazmatCertified, reeferCapable,
                version, createdAt, Instant.now());
    }
}
