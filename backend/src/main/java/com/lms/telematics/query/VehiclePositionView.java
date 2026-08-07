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
package com.lms.telematics.query;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Where a vehicle is now, for a live map. */
public record VehiclePositionView(
        UUID vehicleId,
        String registrationNo,
        UUID tripId,
        String tripNo,
        String tripStatus,
        BigDecimal lat,
        BigDecimal lon,
        BigDecimal speedKph,
        BigDecimal headingDeg,
        Boolean ignitionOn,
        Instant recordedAt,
        /**
         * Seconds since the fix. A map that shows an hour-old position as
         * though it were current is worse than one that shows nothing, because
         * somebody will act on it.
         */
        Long ageSeconds) {
}
