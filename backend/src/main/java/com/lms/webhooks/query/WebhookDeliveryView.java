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
package com.lms.webhooks.query;

import java.time.Instant;
import java.util.UUID;

/** One delivery of one event to one subscription. The receiver's body is never kept. */
public record WebhookDeliveryView(
        UUID id,
        UUID subscriptionId,
        UUID eventId,
        String eventType,
        String status,
        int attempts,
        Integer lastStatusCode,
        String lastError,
        Instant nextAttemptAt,
        Instant deliveredAt,
        Instant createdAt) {
}
