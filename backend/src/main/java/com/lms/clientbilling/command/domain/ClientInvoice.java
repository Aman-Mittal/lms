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
package com.lms.clientbilling.command.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.relational.core.mapping.Table;

/**
 * A client's invoice for one calendar month.
 *
 * <p>At most one per client and month, by unique constraint. That single
 * constraint is both the idempotency of the billing run and the period close:
 * once the invoice exists, the month accepts no new charges.
 */
@Table("client_invoice")
public record ClientInvoice(
        @Id UUID id,
        UUID tenantId,
        UUID clientPartnerId,
        String invoiceNo,
        LocalDate periodStart,
        LocalDate periodEnd,
        Status status,
        String currency,
        BigDecimal totalAmount,
        int lineCount,
        Instant issuedAt,
        @Version Long version,
        Instant createdAt) {

    public enum Status {
        /** Sent. Corrections are credit notes, which are not built yet. */
        ISSUED
    }

    public static ClientInvoice issue(UUID id, UUID tenantId, UUID clientPartnerId,
                                      String invoiceNo, LocalDate periodStart, String currency,
                                      BigDecimal totalAmount, int lineCount, Instant at) {
        return new ClientInvoice(id, tenantId, clientPartnerId, invoiceNo, periodStart,
                periodEndOf(periodStart), Status.ISSUED, currency, totalAmount, lineCount, at,
                null, at);
    }

    /** The last day of the month that starts on {@code periodStart}. */
    public static LocalDate periodEndOf(LocalDate periodStart) {
        return periodStart.plusMonths(1).minusDays(1);
    }
}
