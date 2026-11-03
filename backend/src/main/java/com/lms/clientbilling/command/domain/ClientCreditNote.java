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
 * A correction to an issued client invoice, which itself is never edited.
 *
 * <p>Carries the credited invoice's month, not the month it was issued in: a
 * credit note reduces the receivable and the margin of the period it
 * corrects. Like an invoice it is a sent document, so the database refuses an
 * update or a delete.
 */
@Table("client_credit_note")
public record ClientCreditNote(
        @Id UUID id,
        UUID tenantId,
        UUID clientPartnerId,
        UUID invoiceId,
        String creditNoteNo,
        LocalDate periodStart,
        String reason,
        String currency,
        BigDecimal taxableAmount,
        BigDecimal taxAmount,
        BigDecimal totalAmount,
        int lineCount,
        Instant issuedAt,
        @Version Long version,
        Instant createdAt) {

    public static ClientCreditNote issue(UUID id, UUID tenantId, ClientInvoice invoice,
                                         String creditNoteNo, String reason,
                                         BigDecimal taxableAmount, BigDecimal taxAmount,
                                         int lineCount, Instant at) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException(
                    "A credit note must say why: it is the record a client's auditor reads");
        }
        if (taxableAmount == null || taxableAmount.signum() <= 0) {
            throw new IllegalArgumentException("A credit note must credit something");
        }
        return new ClientCreditNote(id, tenantId, invoice.clientPartnerId(), invoice.id(),
                creditNoteNo, invoice.periodStart(), reason.trim(), invoice.currency(),
                taxableAmount, taxAmount, taxableAmount.add(taxAmount), lineCount, at, null, at);
    }
}
