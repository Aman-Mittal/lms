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
package com.lms.order.events;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Demand has passed hazmat and compatibility validation and is ready to plan.
 *
 * <p>Carries {@code tenantId} explicitly. Listeners registered with
 * {@code @ApplicationModuleListener} run on another thread after the publishing
 * transaction commits, and the tenant scope is a thread-local -- it does not
 * follow the hand-off. A listener that assumed ambient scope would either throw
 * or, worse, run under whatever scope that thread was last used for.
 */
public record OrderValidated(
        UUID tenantId,
        UUID orderId,
        String orderNo,
        UUID customerPartnerId,
        int lineCount,
        BigDecimal totalChargeableWeightKg,
        boolean containsHazmat,
        Instant occurredAt) {
}
