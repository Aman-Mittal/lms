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

import java.util.Optional;
import java.util.UUID;

/**
 * What another context may know about a trip, by its identifier.
 *
 * <p>For the control tower, which raises tickets against trips and has to
 * know which vehicle to locate and whether the trip is still a live thing to
 * raise against. Read-only and deliberately narrow: a caller learns the
 * trip's identity and state, never the aggregate.
 */
public interface TripLookupPort {

    Optional<TripSnapshot> find(UUID tripId);

    /**
     * @param vehicleId null until a vehicle is nominated, which is a normal
     *                  state for a trip that has not reached the gate
     */
    record TripSnapshot(
            UUID tripId,
            String tripNo,
            String status,
            UUID vehicleId,
            UUID vendorPartnerId) {

        public boolean isCancelled() {
            return "CANCELLED".equals(status);
        }
    }
}
