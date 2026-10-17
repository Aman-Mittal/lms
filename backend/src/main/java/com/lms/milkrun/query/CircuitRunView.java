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
package com.lms.milkrun.query;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * One circuit, without its stops.
 *
 * @param totalPickedUpKg summed over the stops as they stand now, so a
 *                        quantity recorded after the circuit reached the plant
 *                        is included
 */
public record CircuitRunView(
        UUID id,
        UUID routeId,
        String routeCode,
        LocalDate runDate,
        Instant plannedDeparture,
        UUID plantTerminalId,
        String status,
        UUID tripId,
        String tripNo,
        Instant startedAt,
        Instant completedAt,
        String completionSource,
        BigDecimal runRate,
        BigDecimal perStopFee,
        String currency,
        Integer stopsVisited,
        Integer stopsSkipped,
        BigDecimal chargeAmount,
        BigDecimal totalPickedUpKg) {
}
