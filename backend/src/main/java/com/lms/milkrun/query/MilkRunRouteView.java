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
import java.time.LocalTime;
import java.util.UUID;

/**
 * One version of a route, without its stops.
 *
 * @param daysOfWeek canonical and Monday first, e.g. {@code MON,WED,FRI}
 */
public record MilkRunRouteView(
        UUID id,
        String code,
        String name,
        UUID plantTerminalId,
        String plantCode,
        LocalDate effectiveFrom,
        LocalDate effectiveTo,
        String daysOfWeek,
        LocalTime departureTime,
        String timeZone,
        BigDecimal runRate,
        BigDecimal perStopFee,
        String currency,
        Instant createdAt) {
}
