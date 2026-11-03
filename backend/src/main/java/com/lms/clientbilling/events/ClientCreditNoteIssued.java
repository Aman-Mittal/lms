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
 * A credit note was issued against a client invoice.
 *
 * <p>Published once per credit note. The invoice it corrects is unchanged; a
 * listener that exported the invoice exports this as a separate document.
 *
 * @param periodStart the credited invoice's month, which is the period whose
 *                    receivable this reduces
 */
public record ClientCreditNoteIssued(
        UUID tenantId,
        UUID creditNoteId,
        String creditNoteNo,
        UUID invoiceId,
        UUID clientPartnerId,
        LocalDate periodStart,
        String currency,
        BigDecimal taxableAmount,
        BigDecimal taxAmount,
        BigDecimal totalAmount,
        Instant occurredAt) {
}
