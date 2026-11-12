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

package com.lms.finance.events;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * A manual charge is in force: raised under the approval threshold, or
 * approved above it. A receivable has been put on the customer's next invoice
 * ({@code clientChargeId}, null when the customer holds no client contract);
 * a payable is a line of the trip's open freight bill or waits for the
 * vendor's next one ({@code freightBillId}, null while it waits).
 */
public record ManualChargeApproved(
        UUID tenantId,
        UUID chargeId,
        String chargeNo,
        String direction,
        String chargeCode,
        String subjectType,
        UUID subjectId,
        UUID tripId,
        UUID customerPartnerId,
        UUID vendorPartnerId,
        BigDecimal amount,
        String currency,
        UUID clientChargeId,
        UUID freightBillId,
        Instant occurredAt) {
}
