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

/**
 * How far along a trip is.
 *
 * <p>The ETA is a straight-line estimate at an assumed average speed, and it is
 * labelled as such everywhere it surfaces. A road-network ETA needs a routing
 * engine; promising one and delivering this would be worse than delivering this
 * and saying so.
 */
public record TripProgressView(
        UUID tripId,
        String tripNo,
        String status,
        BigDecimal lat,
        BigDecimal lon,
        Instant lastFixAt,
        BigDecimal remainingDistanceM,
        Integer estimatedMinutesRemaining,
        BigDecimal completionPct,
        boolean offRoute,
        BigDecimal deviationM) {
}
