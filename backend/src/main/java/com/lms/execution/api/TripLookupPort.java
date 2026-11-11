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
package com.lms.execution.api;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * What another context may know about a trip, by its identifier.
 *
 * <p>For the control tower, which raises tickets against trips and has to
 * know which vehicle to locate and whether the trip is still a live thing to
 * raise against; and for multimodal, which checks that a road leg's trip runs
 * between the leg's own terminals. Read-only and deliberately narrow: a caller
 * learns the trip's identity and state, never the aggregate.
 */
public interface TripLookupPort {

    Optional<TripSnapshot> find(UUID tripId);

    /**
     * What a setting kept per unit, vehicle or customer is resolved for when
     * it concerns this trip: the trip's own unit, its vehicle, and the
     * customers whose freight is aboard. Empty when there is no such trip.
     */
    Optional<TripScope> scopeOf(UUID tripId);

    /**
     * @param orgUnitId   the unit the trip was raised under
     * @param vehicleId   null until a vehicle is nominated
     * @param customerIds the ordering customers of the freight aboard, each
     *                    once; empty when nothing aboard came from an order
     * @param freightMode the load's freight mode by name; null when the trip
     *                    has no load
     */
    record TripScope(UUID tripId, UUID orgUnitId, UUID vehicleId, List<UUID> customerIds, String freightMode) {

        public TripScope {
            customerIds = customerIds == null ? List.of() : List.copyOf(customerIds);
        }

        /**
         * The customer a per-customer threshold is read for: the only one
         * aboard, or null for a load carrying several customers' freight,
         * which is then judged by its unit's and vehicle's values.
         */
        public UUID soleCustomerId() {
            return customerIds.size() == 1 ? customerIds.get(0) : null;
        }
    }

    /**
     * @param vehicleId             null until a vehicle is nominated, which is a
     *                              normal state for a trip that has not reached
     *                              the gate
     * @param loadId                the load the trip carries; added for the
     *                              milk-run payable, which finance prices
     *                              against the trip's load
     * @param originTerminalId      where the trip starts
     * @param destinationTerminalId where it ends; null when the trip was raised
     *                              without one
     */
    record TripSnapshot(
            UUID tripId,
            String tripNo,
            String status,
            UUID vehicleId,
            UUID vendorPartnerId,
            UUID loadId,
            UUID originTerminalId,
            UUID destinationTerminalId) {

        public boolean isCancelled() {
            return "CANCELLED".equals(status);
        }
    }
}
