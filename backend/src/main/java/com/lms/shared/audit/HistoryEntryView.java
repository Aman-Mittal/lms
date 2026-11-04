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
package com.lms.shared.audit;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * One step in a record's history: an audit row, shaped for a reader.
 *
 * @param action        what happened, in the {@code ORDER_CANCELLED} style
 * @param actorKind     {@code PLATFORM} when no user did it (a sweep, a listener),
 *                      {@code YOU} when the reader did, otherwise {@code USER}
 * @param actorId       who did it; null for the platform, and withheld from a
 *                      portal reader unless it was the reader
 * @param actorName     that user's name; withheld from a portal reader
 * @param correlationId the request that did it, for matching against logs
 * @param before        the record's state before, or null for a creation
 * @param after         the record's state after
 */
public record HistoryEntryView(
        UUID id,
        String action,
        String actorKind,
        UUID actorId,
        String actorName,
        String correlationId,
        Instant occurredAt,
        Map<String, Object> before,
        Map<String, Object> after) {
}
