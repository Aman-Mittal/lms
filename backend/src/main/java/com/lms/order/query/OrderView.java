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
package com.lms.order.query;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * An order as an operations screen wants it: the header, the customer's name
 * rather than their identifier, and the totals that would otherwise be an N+1
 * over the lines.
 */
public record OrderView(
        UUID id,
        String orderNo,
        String status,
        UUID customerPartnerId,
        String customerName,
        String originTerminalCode,
        Instant requestedPickupAt,
        Instant requestedDeliveryAt,
        int lineCount,
        int plannedLineCount,
        BigDecimal totalDeadWeightKg,
        boolean containsHazmat,
        Instant createdAt) {
}
