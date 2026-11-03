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
import java.util.UUID;

/**
 * One document on a client's statement, with the balance after it.
 *
 * <p>Amounts are what the client owes, tax included: an invoice is a debit of
 * its total, a credit note or a payment a credit of its amount, so the
 * balance is what has been invoiced less what has been credited or paid. A
 * negative balance is a client in credit.
 *
 * @param entryType  {@code INVOICE}, {@code CREDIT_NOTE} or {@code PAYMENT}
 * @param periodStart the invoiced month; null for a payment, which belongs to
 *                   the client's account rather than to a month
 * @param invoiceNo  the invoice itself, or the invoice a credit note corrects;
 *                   null for a payment
 * @param reason     a credit note's reason, or a payment's remittance
 *                   reference; null for an invoice
 * @param balance    running balance after this entry, over the client's whole
 *                   history, not only this page
 */
public record ClientStatementEntryView(
        String entryType,
        UUID documentId,
        String documentNo,
        Instant issuedAt,
        LocalDate periodStart,
        String invoiceNo,
        String reason,
        String currency,
        BigDecimal debit,
        BigDecimal credit,
        BigDecimal balance) {

    ClientStatementEntryView withBalance(BigDecimal runningBalance) {
        return new ClientStatementEntryView(entryType, documentId, documentNo, issuedAt,
                periodStart, invoiceNo, reason, currency, debit, credit, runningBalance);
    }
}
