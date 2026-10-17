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
 * A count found a bin holding other than the ledger said, and the ledger was
 * corrected. {@code quantity} is signed: negative is a loss.
 */
public record StockAdjusted(
        UUID tenantId,
        UUID movementId,
        UUID warehouseId,
        UUID binId,
        UUID skuId,
        String lotNo,
        int quantity,
        String reason,
        String actor,
        Instant adjustedAt) {
}
