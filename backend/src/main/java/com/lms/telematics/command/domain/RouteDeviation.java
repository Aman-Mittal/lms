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
package com.lms.telematics.command.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.relational.core.mapping.Table;

/**
 * A trip found outside its permitted corridor (vision document 3.7.2).
 *
 * <p>One row per excursion, not per point. A lorry twenty kilometres off route
 * reports every thirty seconds, and an alert per report is an alert nobody
 * reads -- which is the failure mode that makes monitoring systems get switched
 * off.
 */
@Table("route_deviation")
public record RouteDeviation(
        @Id UUID id,
        UUID tenantId,
        UUID tripId,
        Instant detectedAt,
        BigDecimal lat,
        BigDecimal lon,
        BigDecimal distanceM,
        BigDecimal corridorM,
        Instant resolvedAt,
        @Version Long version,
        Instant createdAt) {

    public static RouteDeviation open(UUID id, UUID tenantId, UUID tripId, Instant detectedAt,
                                      BigDecimal lat, BigDecimal lon,
                                      BigDecimal distanceM, BigDecimal corridorM) {
        return new RouteDeviation(id, tenantId, tripId, detectedAt, lat, lon,
                distanceM, corridorM, null, null, Instant.now());
    }

    public boolean isOpen() {
        return resolvedAt == null;
    }

    /** Closed when the vehicle comes back inside the corridor. */
    public RouteDeviation resolve(Instant at) {
        return new RouteDeviation(id, tenantId, tripId, detectedAt, lat, lon,
                distanceM, corridorM, at, version, createdAt);
    }

    /**
     * Widens the recorded excursion if the vehicle has gone further out.
     *
     * <p>The alert keeps the worst distance reached rather than the first,
     * because "went 2 km off route" and "went 40 km off route" are different
     * events and only the second is worth waking somebody for.
     */
    public RouteDeviation deepen(BigDecimal distance) {
        if (distanceM != null && distance.compareTo(distanceM) <= 0) {
            return this;
        }
        return new RouteDeviation(id, tenantId, tripId, detectedAt, lat, lon,
                distance, corridorM, resolvedAt, version, createdAt);
    }
}
