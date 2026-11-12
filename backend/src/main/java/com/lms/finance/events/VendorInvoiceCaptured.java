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
import java.time.LocalDate;
import java.util.UUID;

/** A vendor's invoice was captured against approved freight bills and agreed with them. */
public record VendorInvoiceCaptured(
        UUID tenantId,
        UUID invoiceId,
        String invoiceNo,
        UUID vendorPartnerId,
        String vendorInvoiceNo,
        LocalDate invoiceDate,
        LocalDate dueDate,
        BigDecimal amount,
        BigDecimal taxAmount,
        BigDecimal totalAmount,
        BigDecimal advancesDeducted,
        String currency,
        int billCount,
        Instant occurredAt) {
}
