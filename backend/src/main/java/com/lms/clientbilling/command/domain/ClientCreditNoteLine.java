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
import java.util.UUID;

import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.relational.core.mapping.Table;

/** What one credit note takes off one invoice line, and the tax it reverses. */
@Table("client_credit_note_line")
public record ClientCreditNoteLine(
        @Id UUID id,
        UUID tenantId,
        UUID creditNoteId,
        int lineNo,
        UUID invoiceLineId,
        ServiceType serviceType,
        String narrative,
        BigDecimal taxableAmount,
        BigDecimal taxRate,
        BigDecimal taxAmount,
        @Version Long version,
        Instant createdAt) {

    public static ClientCreditNoteLine of(UUID id, UUID tenantId, UUID creditNoteId, int lineNo,
                                          CreditNoteAllocation.Credit credit) {
        CreditNoteAllocation.Creditable line = credit.line();
        return new ClientCreditNoteLine(id, tenantId, creditNoteId, lineNo, line.invoiceLineId(),
                line.serviceType(), "Credit against line " + line.lineNo() + ": "
                        + line.narrative(),
                credit.taxable(), line.taxRate(), credit.tax(), null, Instant.now());
    }
}
