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
 * An outbound line could not be picked in full: the bins did not hold enough.
 *
 * <p>Published by an order's first pick only, alone or in a wave: a later
 * pick of the back-order that still falls short owes nothing new. So there is
 * at most one of these per line, keyed by {@code (orderId, lineNo)}.
 * {@code waveId} is set when the first pick was part of a wave, and the
 * shortfall is still this order's own: a wave attributes what it could not
 * fill to each order separately.
 */
public record PickShortfallRecorded(
        UUID tenantId,
        UUID orderId,
        String orderNo,
        UUID warehouseId,
        int lineNo,
        UUID skuId,
        int orderedQty,
        int pickedQty,
        int shortQty,
        UUID waveId,
        Instant recordedAt) {
}
