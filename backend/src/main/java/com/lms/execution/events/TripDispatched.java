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
package com.lms.execution.events;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * A vehicle has passed the compliance gate and left the origin.
 *
 * <p>The moment the platform's promise becomes a physical commitment. Finance
 * prices the trip against the tariff version active on this date (3.9.1), so
 * the timestamp is part of the contract, not telemetry.
 */
public record TripDispatched(
        UUID tenantId,
        UUID tripId,
        String tripNo,
        UUID loadId,
        UUID vehicleId,
        UUID driverId,
        UUID vendorPartnerId,
        UUID originTerminalId,
        UUID destinationTerminalId,
        BigDecimal payloadWeightKg,
        Instant dispatchedAt,
        Instant occurredAt) {
}
