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
package com.lms.execution.command.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.relational.core.mapping.Table;

/**
 * A weighbridge ticket (vision document 3.6.2).
 *
 * <p>One tare and one gross per trip, enforced by a unique constraint. A second
 * reading of the same kind is a correction, and silently overwriting the first
 * would destroy the evidence the payload figure rests on.
 */
@Table("weighbridge_reading")
public record WeighbridgeReading(
        @Id UUID id,
        UUID tenantId,
        UUID tripId,
        UUID terminalId,
        ReadingType readingType,
        BigDecimal weightKg,
        Instant recordedAt,
        @Version Long version,
        Instant createdAt) {

    public enum ReadingType {
        /** The empty vehicle. */
        TARE,
        /** The laden vehicle. */
        GROSS
    }

    public static WeighbridgeReading of(UUID id, UUID tenantId, UUID tripId, UUID terminalId,
                                        ReadingType readingType, BigDecimal weightKg,
                                        Instant recordedAt) {
        if (weightKg == null || weightKg.signum() <= 0) {
            throw new IllegalArgumentException("A weighbridge reading must be a positive weight");
        }
        return new WeighbridgeReading(id, tenantId, tripId, terminalId, readingType, weightKg,
                recordedAt == null ? Instant.now() : recordedAt, null, Instant.now());
    }
}
