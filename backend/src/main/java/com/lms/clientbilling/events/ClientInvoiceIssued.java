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
package com.lms.clientbilling.events;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * A billing run closed a client's period and issued its invoice.
 *
 * <p>Published once per client and period. A rerun of the same period returns
 * the existing invoice and publishes nothing, so a listener -- a ledger
 * export, a notification -- never sees one invoice twice from this side.
 */
public record ClientInvoiceIssued(
        UUID tenantId,
        UUID invoiceId,
        String invoiceNo,
        UUID clientPartnerId,
        LocalDate periodStart,
        LocalDate periodEnd,
        String currency,
        BigDecimal totalAmount,
        int lineCount,
        Instant occurredAt) {
}
