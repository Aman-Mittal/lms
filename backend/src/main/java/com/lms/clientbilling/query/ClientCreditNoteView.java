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
package com.lms.clientbilling.query;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * A credit note, with its lines.
 *
 * @param periodStart the credited invoice's month, whose receivable it reduces
 */
public record ClientCreditNoteView(
        UUID id,
        String creditNoteNo,
        UUID invoiceId,
        String invoiceNo,
        UUID clientPartnerId,
        LocalDate periodStart,
        String reason,
        String currency,
        BigDecimal taxableAmount,
        BigDecimal taxAmount,
        BigDecimal totalAmount,
        int lineCount,
        Instant issuedAt,
        List<Line> lines) {

    public ClientCreditNoteView {
        lines = lines == null ? List.of() : List.copyOf(lines);
    }

    /** What one credit-note line took off one invoice line. */
    public record Line(int lineNo, int invoiceLineNo, String serviceType, String narrative,
                       BigDecimal taxableAmount, BigDecimal taxRate, BigDecimal taxAmount,
                       BigDecimal lineTotal) {
    }
}
