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
 * A later pick filled what an earlier one left short on an outbound line.
 *
 * <p>Published once per line, when the line's back-order is filled in full,
 * so a consumer can key on {@code (orderId, lineNo)} and stay idempotent under
 * redelivery. {@code filledQty} is what this pick took for the line;
 * {@code waveId} is set when the pick was part of a wave.
 */
public record BackOrderFilled(
        UUID tenantId,
        UUID orderId,
        String orderNo,
        UUID warehouseId,
        int lineNo,
        UUID skuId,
        int orderedQty,
        int filledQty,
        UUID waveId,
        Instant filledAt) {
}
