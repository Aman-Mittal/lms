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
import java.util.List;
import java.util.UUID;

/**
 * One line of an enquiry, with the rate its customer's contract suggested and
 * the quotes made on it (oldest revision first). {@code quotes} is empty on a
 * line the query did not load them for.
 */
public record EnquiryLineView(
        UUID id,
        int lineNo,
        UUID originTerminalId,
        String originCode,
        UUID destinationTerminalId,
        String destinationCode,
        String loadType,
        String material,
        BigDecimal quantity,
        String uom,
        BigDecimal weightKg,
        LocalDate wantedPickupOn,
        LocalDate wantedDeliveryOn,
        String status,
        BigDecimal wonQuantity,
        String lostReason,
        Instant outcomeAt,
        String suggestedContractCode,
        String suggestedLaneCode,
        String suggestedRateBasis,
        BigDecimal suggestedRate,
        BigDecimal suggestedAmount,
        String currency,
        List<QuoteView> quotes) {

    public EnquiryLineView {
        quotes = quotes == null ? List.of() : List.copyOf(quotes);
    }
}
