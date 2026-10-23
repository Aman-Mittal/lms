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
import java.util.List;
import java.util.UUID;

/**
 * A client's receivable as of a day: what is owed in total and by age, and the
 * invoices that are still open, oldest first.
 *
 * <p>Age is counted from the day the invoice was issued; a contract does not
 * yet carry payment terms, so nothing here is "past due", only old. Payments
 * are applied to the oldest invoices first. {@code unappliedCredit} is what
 * the client has paid beyond what was invoiced.
 */
public record ClientAgeingView(
        UUID clientPartnerId,
        LocalDate asOf,
        String currency,
        BigDecimal outstanding,
        BigDecimal unappliedCredit,
        Buckets buckets,
        List<Invoice> invoices) {

    public ClientAgeingView {
        invoices = invoices == null ? List.of() : List.copyOf(invoices);
    }

    /** Outstanding by days since issue. */
    public record Buckets(BigDecimal days0to30, BigDecimal days31to60, BigDecimal days61to90,
                          BigDecimal over90) {
    }

    /** An invoice that still has something owing on it. */
    public record Invoice(
            UUID invoiceId,
            String invoiceNo,
            LocalDate periodStart,
            LocalDate issuedOn,
            BigDecimal total,
            BigDecimal credited,
            BigDecimal paid,
            BigDecimal outstanding,
            long ageDays,
            String bucket) {
    }
}
