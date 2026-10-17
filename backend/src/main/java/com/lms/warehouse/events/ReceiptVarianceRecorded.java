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
package com.lms.warehouse.events;

import java.time.Instant;
import java.util.UUID;

/**
 * One ASN line arrived other than as notified: fewer, more, or damaged.
 *
 * <p>One event per line that differs, so a consumer raising exceptions can
 * key on {@code (asnId, lineNo)} and stay idempotent under redelivery.
 */
public record ReceiptVarianceRecorded(
        UUID tenantId,
        UUID asnId,
        String asnNo,
        UUID warehouseId,
        int lineNo,
        UUID skuId,
        String lotNo,
        int expectedQty,
        int receivedQty,
        int shortQty,
        int excessQty,
        int damagedQty,
        Instant recordedAt) {
}
