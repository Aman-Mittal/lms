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
import java.time.LocalDate;
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
        /** What an edit of the header restates, with the hubs of a part-load order. */
        UUID originTerminalId,
        UUID originHubTerminalId,
        UUID destinationHubTerminalId,
        Instant requestedPickupAt,
        Instant requestedDeliveryAt,
        int lineCount,
        int plannedLineCount,
        BigDecimal totalDeadWeightKg,
        boolean containsHazmat,
        String freightMode,
        Instant createdAt,
        /** The optimistic-lock version: the ETag, and what an edit sends back in If-Match. */
        Long version,
        /** How a CLOSED order was closed (NORMAL, SHORT, FORCE or AUTO); null otherwise. */
        String closeKind,
        String closeReason,
        Instant closedAt,
        /** The client contract version the order was resolved against; null when none was in force. */
        UUID contractId,
        String contractCode,
        /** The lane of that contract the order travels on; null for a direct order. */
        UUID contractLaneId,
        String contractLaneCode,
        /** The load or vehicle type the order is booked for; null when none is stated. */
        String loadType,
        /** Days the movement is promised to take: the lane's, else from distance. */
        Integer transitDays,
        /**
         * When delivery is expected: the requested pickup day (else the day the
         * order was raised) plus the transit days; null while those are unknown.
         */
        LocalDate expectedDeliveryDate) {
}
