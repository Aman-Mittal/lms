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
 */package com.lms.finance.query;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * A freight bill as a settlement screen shows it.
 *
 * <p>Carries the tariff code alongside the amounts. "Priced at 42 000" is not
 * something anybody can act on; "priced at 42 000 under WEST-LANE-2026-01,
 * which was in force on the dispatch date" is the beginning of a conversation
 * with the vendor.
 */
public record FreightBillView(
        UUID id,
        String billNo,
        String status,
        String currency,
        UUID tripId,
        String tripNo,
        UUID loadId,
        String loadNo,
        String vendorName,
        String tariffCode,
        LocalDate pricedOn,
        BigDecimal chargeableWeightKg,
        BigDecimal detentionHours,
        Integer dropCount,
        BigDecimal baseFreight,
        BigDecimal detentionAmount,
        BigDecimal multiDropAmount,
        BigDecimal fuelSurchargeAmount,
        BigDecimal computedAmount,
        BigDecimal claimedAmount,
        BigDecimal varianceAmount,
        BigDecimal variancePct,
        String resolution,
        Instant matchedAt,
        Instant createdAt) {
}
