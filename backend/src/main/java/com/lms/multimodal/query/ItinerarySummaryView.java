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
package com.lms.multimodal.query;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * One itinerary on a listing.
 *
 * @param status          PLANNED until a leg moves, COMPLETED once the last leg
 *                        has arrived, IN_PROGRESS between
 * @param totalCost       every leg's cost, summed; each leg's carrier is still
 *                        paid separately
 * @param connectionMissed whether any leg has missed its connection
 */
public record ItinerarySummaryView(
        UUID id,
        String itineraryNo,
        String shipmentRef,
        String status,
        String currency,
        int legCount,
        BigDecimal totalCost,
        boolean connectionMissed,
        Instant createdAt) {
}
