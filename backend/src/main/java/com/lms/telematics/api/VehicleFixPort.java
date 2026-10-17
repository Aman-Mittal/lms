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
package com.lms.telematics.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Where a vehicle last reported itself.
 *
 * <p>The control tower asks this at the moment a ticket is raised, because
 * 3.8 requires the position to be captured then -- not reconstructed later
 * from a track that may by then have been simplified away by retention.
 */
public interface VehicleFixPort {

    /** The newest accepted position for the vehicle, by device time. */
    Optional<Fix> latestFix(UUID vehicleId);

    /** @param recordedAt when the device took it, which is what its age is measured from */
    record Fix(BigDecimal lat, BigDecimal lon, Instant recordedAt) {
    }
}
