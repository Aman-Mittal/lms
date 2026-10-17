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
package com.lms.milkrun.events;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * A vehicle reached a supplier after the pickup window had closed.
 *
 * <p>Carries both instants rather than only the lateness, so a listener can
 * judge the gap itself -- ten minutes on a window of two hours is not the same
 * conversation as ten minutes on a window of fifteen.
 */
public record CircuitStopLate(
        UUID tenantId,
        UUID runId,
        String routeCode,
        LocalDate runDate,
        UUID tripId,
        int sequence,
        UUID terminalId,
        Instant windowClosesAt,
        Instant arrivedAt,
        int minutesLate) {
}
