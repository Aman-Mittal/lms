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
 * An order has been closed: by hand (normally, short or forced), or by the
 * platform when what was left was within the tenant's tolerance.
 *
 * <p>Published once, when the order moves to CLOSED. Closing a closed order is
 * refused, so there is no repeat to announce. Consignments already cut from
 * the order are not touched by a close and report their own progress.
 *
 * @param previousStatus the status the order left, by name
 * @param closeKind      NORMAL, SHORT, FORCE or AUTO
 * @param reason         why, as stated; null when none was given
 * @param linesClosed    lines closed by this close, the order's own and any
 *                       closed earlier excluded
 */
public record OrderClosed(
        UUID tenantId,
        UUID orderId,
        String orderNo,
        String previousStatus,
        String closeKind,
        String reason,
        int linesClosed,
        Instant occurredAt) {
}
