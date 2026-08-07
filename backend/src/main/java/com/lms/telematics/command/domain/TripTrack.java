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

import com.lms.shared.config.Json;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.relational.core.mapping.Table;

/**
 * A completed trip's path, simplified and kept after the raw points are gone.
 *
 * <p>The retention mechanism of a 1 GB database. One lorry reporting every
 * thirty seconds for a week is roughly twenty thousand rows; the simplified
 * line is a few hundred points, and it is all anyone looks at once the trip is
 * over.
 */
@Table("trip_track")
public record TripTrack(
        @Id UUID id,
        UUID tenantId,
        UUID tripId,
        /** [[lon, lat], ...] in GeoJSON order, ready to hand to a map library. */
        Json points,
        int pointCount,
        int rawCount,
        BigDecimal distanceM,
        Instant simplifiedAt,
        @Version Long version,
        Instant createdAt) {
}
