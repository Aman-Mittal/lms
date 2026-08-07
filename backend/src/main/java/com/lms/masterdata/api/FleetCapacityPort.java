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
package com.lms.masterdata.api;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

/**
 * What a vehicle can physically and lawfully carry.
 *
 * <p>Read by planning when a load is opened. Like
 * {@link FleetCompliancePort}, this is a synchronous read rather than an event
 * because the answer gates the operator's next click: a load cannot be sized
 * against a capacity that arrives eventually.
 */
public interface FleetCapacityPort {

    /**
     * Capacity of an available vehicle.
     *
     * <p>Empty when the vehicle does not exist, or exists but is not available
     * -- a vehicle in maintenance or already on a trip has no capacity to
     * offer, and returning its numbers would let planning build a load against
     * a lorry that cannot move.
     */
    Optional<VehicleCapacity> capacityOf(UUID vehicleId);

    /**
     * @param payloadCapacityKg gross weight less tare, which is what a load may
     *                          actually weigh -- not the laden total, which
     *                          would over-allocate every vehicle by its own mass
     */
    record VehicleCapacity(
            UUID vehicleId,
            String registrationNo,
            String vehicleType,
            BigDecimal payloadCapacityKg,
            BigDecimal maxVolumeM3,
            boolean hazmatCertified,
            boolean reeferCapable) {
    }
}
