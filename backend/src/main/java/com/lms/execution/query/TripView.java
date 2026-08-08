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
package com.lms.execution.query;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * A trip as a control screen shows it.
 *
 * <p>Carries {@code originDwellMinutes} because that is the number detention is
 * billed on, and computing it per row in the application would mean fetching
 * every timestamp to display a list.
 */
public record TripView(
        UUID id,
        String tripNo,
        String status,
        UUID loadId,
        String loadNo,
        String vendorName,
        String vehicleRegistrationNo,
        String driverName,
        String originTerminalCode,
        String destinationTerminalCode,
        Instant plannedStartAt,
        Instant gateInAt,
        Instant dispatchedAt,
        Instant arrivedAt,
        Instant completedAt,
        BigDecimal tareWeightKg,
        BigDecimal grossWeightKg,
        BigDecimal payloadWeightKg,
        BigDecimal plannedWeightKg,
        Long originDwellMinutes,
        int documentCount,
        Instant createdAt) {
}
