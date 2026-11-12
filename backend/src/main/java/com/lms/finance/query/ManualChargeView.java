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

package com.lms.finance.query;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * A manual charge and where it went: the client charge a receivable became (and
 * whether that is on an invoice yet), or the freight bill a payable is a line
 * of. {@code billed} is true once it can no longer be cancelled.
 */
public record ManualChargeView(
        UUID id,
        String chargeNo,
        UUID catalogueId,
        String chargeCode,
        String chargeName,
        String basis,
        String direction,
        String subjectType,
        UUID subjectId,
        String subjectRef,
        UUID tripId,
        UUID customerPartnerId,
        String customerName,
        UUID vendorPartnerId,
        String vendorName,
        BigDecimal quantity,
        BigDecimal rate,
        BigDecimal amount,
        String currency,
        String reason,
        String evidenceRef,
        String status,
        UUID approvalId,
        UUID clientChargeId,
        UUID clientInvoiceId,
        UUID freightBillId,
        String freightBillNo,
        boolean billed,
        String cancelReason,
        Long version,
        Instant createdAt,
        Instant updatedAt) {
}
