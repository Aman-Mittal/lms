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
 * One stop of a trip's ordered plan, with where it is for the map and how late
 * it was reached. {@code lateMinutes} is null until the stop is reached or when
 * it has no planned arrival, and 0 when it was on time or early.
 */
public record TripStopView(
        UUID id,
        int seq,
        String kind,
        UUID terminalId,
        String terminalCode,
        String terminalName,
        BigDecimal lat,
        BigDecimal lon,
        Instant plannedArrivalAt,
        String status,
        Instant arrivedAt,
        Instant departedAt,
        Instant completedAt,
        Instant skippedAt,
        String skipReason,
        boolean outOfOrder,
        String arrivalSource,
        String departureSource,
        Long lateMinutes,
        Long version) {
}
