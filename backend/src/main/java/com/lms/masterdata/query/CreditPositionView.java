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
package com.lms.masterdata.query;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * A customer's credit position: limits, what it owes or is committed to, and
 * what is left. Defined by {@link CreditExposureService}.
 *
 * @param trading   false when the customer is SUSPENDED or BLACKLISTED
 * @param headroom  hard limit minus exposure, negative once over; null without a limit
 */
public record CreditPositionView(
        UUID partnerId,
        String partnerCode,
        String partnerStatus,
        boolean trading,
        String currency,
        BigDecimal creditLimit,
        BigDecimal softLimit,
        Exposure exposure,
        BigDecimal headroom,
        LimitState limitState,
        UUID pendingChangeId,
        Instant asOf) {

    public enum LimitState {
        NO_LIMIT, WITHIN, SOFT_EXCEEDED, HARD_EXCEEDED
    }

    /** The three parts of exposure and their sum. */
    public record Exposure(
            BigDecimal openOrders,
            int openOrderCount,
            int unpricedOpenOrderCount,
            BigDecimal unbilledCharges,
            int unbilledChargeCount,
            int unpricedChargeCount,
            BigDecimal outstandingInvoices,
            BigDecimal total) {
    }
}
