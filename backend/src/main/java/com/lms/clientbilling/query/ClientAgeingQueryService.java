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
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The receivables ageing of one client. Confined by {@link ClientAccess} like
 * the rest of the read side: a portal user sees their own client's and no
 * other's.
 */
@Service
@Transactional(readOnly = true)
@PreAuthorize("hasAuthority('CLIENT_BILLING_READ')")
public class ClientAgeingQueryService {

    private final JdbcClient jdbc;
    private final ClientAccess access;

    public ClientAgeingQueryService(JdbcClient jdbc, ClientAccess access) {
        this.jdbc = jdbc;
        this.access = access;
    }

    private record InvoiceRow(UUID id, String invoiceNo, LocalDate periodStart,
                              java.time.OffsetDateTime issuedAt, String currency,
                              BigDecimal totalAmount, BigDecimal creditedAmount) {
    }

    /** @param asOf the day to age to; today (UTC) when null */
    public ClientAgeingView ageing(UUID clientPartnerId, LocalDate asOf) {
        access.requireAccessTo(clientPartnerId);
        LocalDate day = asOf == null ? LocalDate.now(ZoneOffset.UTC) : asOf;

        List<InvoiceRow> invoices = jdbc.sql("""
                        SELECT i.id, i.invoice_no, i.period_start, i.issued_at, i.currency,
                               i.total_amount,
                               coalesce((SELECT sum(cn.total_amount) FROM client_credit_note cn
                                          WHERE cn.tenant_id = i.tenant_id
                                            AND cn.invoice_id = i.id), 0) AS credited_amount
                          FROM client_invoice i
                         WHERE i.client_partner_id = :client
                           AND i.issued_at < CAST(:end AS timestamptz)
                         ORDER BY i.issued_at, i.id
                        """)
                .param("client", clientPartnerId)
                .param("end", day.plusDays(1).atStartOfDay().atOffset(ZoneOffset.UTC))
                .query(InvoiceRow.class)
                .list();

        BigDecimal received = jdbc.sql("""
                        SELECT coalesce(sum(amount), 0) FROM client_payment
                         WHERE client_partner_id = :client
                           AND recorded_at < CAST(:end AS timestamptz)
                        """)
                .param("client", clientPartnerId)
                .param("end", day.plusDays(1).atStartOfDay().atOffset(ZoneOffset.UTC))
                .query(BigDecimal.class)
                .single();

        ClientAgeing.Result result = ClientAgeing.allocate(invoices.stream()
                .map(r -> new ClientAgeing.Invoice(r.id(), r.invoiceNo(), r.periodStart(),
                        r.issuedAt().withOffsetSameInstant(ZoneOffset.UTC).toLocalDate(),
                        r.totalAmount(), r.creditedAmount()))
                .toList(), received, day);

        List<ClientAgeing.Row> open = result.rows().stream()
                .filter(row -> row.outstanding().signum() > 0)
                .toList();
        return new ClientAgeingView(clientPartnerId, day,
                invoices.isEmpty() ? null : invoices.get(invoices.size() - 1).currency(),
                sum(open, null), result.unappliedCredit(),
                new ClientAgeingView.Buckets(sum(open, ClientAgeing.CURRENT),
                        sum(open, ClientAgeing.DAYS_31_60), sum(open, ClientAgeing.DAYS_61_90),
                        sum(open, ClientAgeing.OVER_90)),
                open.stream().map(row -> new ClientAgeingView.Invoice(row.invoiceId(),
                        row.invoiceNo(), row.periodStart(), row.issuedOn(), row.total(),
                        row.credited(), row.paid(), row.outstanding(), row.ageDays(),
                        row.bucket())).toList());
    }

    private static BigDecimal sum(List<ClientAgeing.Row> rows, String bucket) {
        return rows.stream()
                .filter(row -> bucket == null || bucket.equals(row.bucket()))
                .map(ClientAgeing.Row::outstanding)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
