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
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * What a client still owes, invoice by invoice, and how overdue it is.
 *
 * <p>A credit note corrects one invoice, so it reduces that invoice and no
 * other. A payment is recorded against the client, not an invoice, so it is
 * applied oldest invoice first -- the way a ledger clerk would, and the only
 * rule that needs no remittance advice. Whatever the payments exceed is
 * unapplied credit: the client is ahead.
 *
 * <p>Pure arithmetic over what the caller read, with no clock and no
 * database, so every rule here is a plain unit test.
 */
final class ClientAgeing {

    /** One issued invoice, oldest first by the caller. */
    record Invoice(UUID id, String invoiceNo, LocalDate periodStart, LocalDate issuedOn,
                   BigDecimal total, BigDecimal credited) {
    }

    /** Days since issue are counted as of {@code asOf}; a future invoice is not overdue. */
    record Row(UUID invoiceId, String invoiceNo, LocalDate periodStart, LocalDate issuedOn,
               BigDecimal total, BigDecimal credited, BigDecimal paid, BigDecimal outstanding,
               long ageDays, String bucket) {
    }

    record Result(List<Row> rows, BigDecimal unappliedCredit) {
    }

    static final String CURRENT = "0-30";
    static final String DAYS_31_60 = "31-60";
    static final String DAYS_61_90 = "61-90";
    static final String OVER_90 = "90+";

    private ClientAgeing() {
    }

    static String bucketOf(long ageDays) {
        if (ageDays <= 30) {
            return CURRENT;
        }
        if (ageDays <= 60) {
            return DAYS_31_60;
        }
        return ageDays <= 90 ? DAYS_61_90 : OVER_90;
    }

    /**
     * @param invoices      the client's invoices, oldest first
     * @param totalReceived everything the client has paid, all payments added
     */
    static Result allocate(List<Invoice> invoices, BigDecimal totalReceived, LocalDate asOf) {
        BigDecimal remainingPayment = totalReceived;
        List<Row> rows = new ArrayList<>(invoices.size());
        for (Invoice invoice : invoices) {
            BigDecimal owed = invoice.total().subtract(invoice.credited()).max(BigDecimal.ZERO);
            BigDecimal paid = owed.min(remainingPayment);
            remainingPayment = remainingPayment.subtract(paid);
            long age = Math.max(0, ChronoUnit.DAYS.between(invoice.issuedOn(), asOf));
            rows.add(new Row(invoice.id(), invoice.invoiceNo(), invoice.periodStart(),
                    invoice.issuedOn(), invoice.total(), invoice.credited(), paid,
                    owed.subtract(paid), age, bucketOf(age)));
        }
        return new Result(rows, remainingPayment);
    }
}
