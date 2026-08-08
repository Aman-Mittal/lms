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
package com.lms.telematics.events;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * A trip has left its permitted corridor (vision document 3.7.2).
 *
 * <p>Published once per excursion rather than once per point. A lorry twenty
 * kilometres off route reports every thirty seconds, and an alert per report is
 * an alert nobody reads.
 */
public record RouteDeviationDetected(
        UUID tenantId,
        UUID tripId,
        String tripNo,
        BigDecimal distanceM,
        BigDecimal corridorM,
        BigDecimal lat,
        BigDecimal lon,
        Instant detectedAt,
        Instant publishedAt) {
}
