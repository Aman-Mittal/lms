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

import java.time.Instant;
import java.util.UUID;

/** A stop was passed by, with the reason a person gave. */
public record TripStopSkipped(
        UUID tenantId,
        UUID tripId,
        String tripNo,
        UUID stopId,
        int seq,
        String kind,
        UUID terminalId,
        String reason,
        Instant skippedAt,
        Instant occurredAt) {
}
