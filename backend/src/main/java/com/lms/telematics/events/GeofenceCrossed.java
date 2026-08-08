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
package com.lms.telematics.events;

import java.time.Instant;
import java.util.UUID;

/**
 * A trip has entered or left a terminal's geofence.
 *
 * <p>Published for anything that wants to watch movement without being in the
 * ingest path -- a notification channel, a control tower, an SLA scorer. The
 * trip state change itself is not done here: it is done synchronously through
 * {@code execution::api}, because a state machine driven eventually would
 * report a lorry arriving after it had already left.
 */
public record GeofenceCrossed(
        UUID tenantId,
        UUID tripId,
        String tripNo,
        UUID terminalId,
        String eventType,
        Instant occurredAt,
        Instant publishedAt) {
}
