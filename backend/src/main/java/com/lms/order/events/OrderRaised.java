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

import java.time.Instant;
import java.util.UUID;

/**
 * An order has been captured, as a draft, under the number it will keep.
 *
 * <p>Published in the raising transaction and delivered after it commits, so a
 * listener never hears of an order that was rolled back. Carries the tenant for
 * the reason {@link OrderValidated} does.
 *
 * @param orderNo      the number the caller gave, or the one the tenant's sequence assigned
 * @param freightMode  FTL or LTL, by name
 * @param numberAssigned true when the platform chose the number
 */
public record OrderRaised(
        UUID tenantId,
        UUID orderId,
        String orderNo,
        UUID customerPartnerId,
        String freightMode,
        String source,
        boolean numberAssigned,
        Instant occurredAt) {
}
