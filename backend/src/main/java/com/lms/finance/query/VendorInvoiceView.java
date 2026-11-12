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
import java.time.LocalDate;
import java.util.UUID;

/**
 * A vendor invoice with its balance: what it is for, what the platform
 * approved, the advances taken off, what has been paid and what is still
 * outstanding. {@code overdue} is read against today, not stored.
 */
public record VendorInvoiceView(
        UUID id,
        String invoiceNo,
        UUID vendorPartnerId,
        String vendorName,
        String vendorInvoiceNo,
        LocalDate invoiceDate,
        LocalDate dueDate,
        int paymentTermsDays,
        BigDecimal amount,
        BigDecimal taxAmount,
        BigDecimal totalAmount,
        String currency,
        BigDecimal approvedAmount,
        BigDecimal varianceAmount,
        BigDecimal advancesDeducted,
        BigDecimal netPayable,
        BigDecimal paidAmount,
        BigDecimal outstanding,
        String status,
        boolean overdue,
        long daysOverdue,
        int billCount,
        String documentRef,
        String remarks,
        Long version,
        Instant createdAt) {
}
