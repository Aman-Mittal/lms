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
 * A supplier on a circuit was not collected from.
 *
 * <p>Either the vehicle reached a later stop -- or the plant -- first, or a
 * person recorded the skip by hand. Either way the parts that supplier had
 * ready are still sitting on its dock, which is what makes this worth a
 * listener: the plant's line-side stock is now short.
 */
public record CircuitStopSkipped(
        UUID tenantId,
        UUID runId,
        String routeCode,
        LocalDate runDate,
        UUID tripId,
        int sequence,
        UUID terminalId,
        String reason,
        Instant occurredAt) {
}
