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
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import com.lms.shared.error.BusinessRuleViolationException;
import com.lms.shared.query.Slice;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read side for client billing.
 *
 * <p>Every method is confined by {@link ClientAccess}: a portal user asking
 * for another client by identifier is refused, and one asking for another
 * client's invoice by its identifier is told it does not exist -- the same
 * answer as for an invoice that really does not, so identifiers cannot be
 * probed.
 */
@Service
@Transactional(readOnly = true)
@PreAuthorize("hasAuthority('CLIENT_BILLING_READ')")
public class ClientBillingQueryService {

    private static final String INVOICE = """
            SELECT i.id, i.invoice_no, i.client_partner_id, p.code AS client_code,
                   p.legal_name AS client_name, i.period_start, i.period_end, i.status,
                   i.currency, i.total_amount - i.tax_amount AS taxable_amount, i.tax_amount,
                   i.total_amount,
                   coalesce((SELECT sum(cn.total_amount) FROM client_credit_note cn
                              WHERE cn.tenant_id = i.tenant_id AND cn.invoice_id = i.id), 0)
                       AS credited_amount,
                   i.line_count, i.issued_at
              FROM client_invoice i
              JOIN business_partner p ON p.id = i.client_partner_id
            """;

    private final JdbcClient jdbc;
    private final ClientAccess access;

    public ClientBillingQueryService(JdbcClient jdbc, ClientAccess access) {
        this.jdbc = jdbc;
        this.access = access;
    }

    /** A client's contract versions, newest first. A client has a handful. */
    public List<ClientContractView> contractsOf(UUID clientPartnerId) {
        access.requireAccessTo(clientPartnerId);

        List<ContractRow> rows = jdbc.sql("""
                        SELECT id, code, client_partner_id, currency, effective_from, effective_to,
                               transport_rate_per_kg, transport_minimum_charge,
                               storage_rate_per_pallet_day, handling_in_rate_per_pallet,
                               handling_out_rate_per_pallet, transport_tax_rate_pct,
                               warehouse_tax_rate_pct, storage_units_per_pallet
                          FROM client_contract
                         WHERE client_partner_id = :clientPartnerId
                         ORDER BY effective_from DESC
                         LIMIT :limit
                        """)
                .param("clientPartnerId", clientPartnerId)
                .param("limit", Slice.MAX_LIMIT)
                .query(ContractRow.class)
                .list();
        if (rows.isEmpty()) {
            return List.of();
        }

        Map<UUID, List<ClientContractView.ServiceRate>> rates = jdbc.sql("""
                        SELECT contract_id, service_code, unit, rate
                          FROM client_contract_service_rate
                         WHERE contract_id IN (:ids)
                         ORDER BY service_code
                        """)
                .param("ids", rows.stream().map(ContractRow::id).toList())
                .query(RateRow.class)
                .list()
                .stream()
                .collect(Collectors.groupingBy(RateRow::contractId, Collectors.mapping(
                        r -> new ClientContractView.ServiceRate(r.serviceCode(), r.unit(),
                                r.rate()), Collectors.toList())));

        return rows.stream().map(r -> new ClientContractView(r.id(), r.code(),
                r.clientPartnerId(), r.currency(), r.effectiveFrom(), r.effectiveTo(),
                r.transportRatePerKg(), r.transportMinimumCharge(), r.storageRatePerPalletDay(),
                r.handlingInRatePerPallet(), r.handlingOutRatePerPallet(),
                r.transportTaxRatePct(), r.warehouseTaxRatePct(), r.storageUnitsPerPallet(),
                rates.getOrDefault(r.id(), List.of()))).toList();
    }

    /** The charges filed under one billing period, in capture order. */
    public Slice<ClientChargeView> chargesOf(UUID clientPartnerId, LocalDate period,
                                             String cursor, Integer limit) {
        access.requireAccessTo(clientPartnerId);
        int pageSize = Slice.clampLimit(limit);

        List<ClientChargeView> rows = jdbc.sql("""
                        SELECT id, client_partner_id, service_type, service_code, quantity, unit,
                               occurred_on, billing_period,
                               billing_period > date_trunc('month', occurred_on::timestamp)::date AS late,
                               reference, source_key, trip_id, invoice_id
                          FROM client_charge
                         WHERE client_partner_id = :clientPartnerId
                           AND billing_period = :period
                           AND (CAST(:after AS uuid) IS NULL OR id > CAST(:after AS uuid))
                         ORDER BY id
                         LIMIT :limit
                        """)
                .param("clientPartnerId", clientPartnerId)
                .param("period", period.withDayOfMonth(1))
                .param("after", parseUuidCursor(cursor))
                .param("limit", pageSize + 1)
                .query(ClientChargeView.class)
                .list();

        return Slice.of(rows, pageSize, view -> view.id().toString());
    }

    /** A client's invoices, most recent period first. */
    public Slice<ClientInvoiceView> invoicesOf(UUID clientPartnerId, String cursor,
                                               Integer limit) {
        access.requireAccessTo(clientPartnerId);
        int pageSize = Slice.clampLimit(limit);

        List<ClientInvoiceView> rows = jdbc.sql(INVOICE + """
                         WHERE i.client_partner_id = :clientPartnerId
                           AND (CAST(:before AS date) IS NULL
                                OR i.period_start < CAST(:before AS date))
                         ORDER BY i.period_start DESC
                         LIMIT :limit
                        """)
                .param("clientPartnerId", clientPartnerId)
                .param("before", parseDateCursor(cursor))
                .param("limit", pageSize + 1)
                .query(ClientInvoiceView.class)
                .list();

        return Slice.of(rows, pageSize, view -> view.periodStart().toString());
    }

    public Optional<ClientInvoiceView> findInvoice(UUID invoiceId) {
        return jdbc.sql(INVOICE + """
                         WHERE i.id = :id
                           AND (CAST(:bound AS uuid) IS NULL
                                OR i.client_partner_id = CAST(:bound AS uuid))
                        """)
                .param("id", invoiceId)
                .param("bound", access.boundClient().orElse(null))
                .query(ClientInvoiceView.class)
                .optional();
    }

    /** The lines of an invoice, in order; empty for an invoice the caller may not see. */
    public List<ClientInvoiceLineView> linesOf(UUID invoiceId) {
        return jdbc.sql("""
                        SELECT l.line_no, l.service_type, l.narrative, l.quantity, l.unit, l.rate,
                               l.amount, l.tax_rate, l.tax_amount,
                               l.amount + l.tax_amount AS line_total,
                               coalesce((SELECT sum(c.taxable_amount)
                                           FROM client_credit_note_line c
                                          WHERE c.tenant_id = l.tenant_id
                                            AND c.invoice_line_id = l.id), 0) AS credited_amount,
                               l.charge_id, ch.occurred_on, k.code AS contract_code
                          FROM client_invoice_line l
                          JOIN client_invoice i ON i.id = l.invoice_id
                          JOIN client_charge ch ON ch.id = l.charge_id
                          JOIN client_contract k ON k.id = l.contract_id
                         WHERE l.invoice_id = :invoiceId
                           AND (CAST(:bound AS uuid) IS NULL
                                OR i.client_partner_id = CAST(:bound AS uuid))
                         ORDER BY l.line_no
                        """)
                .param("invoiceId", invoiceId)
                .param("bound", access.boundClient().orElse(null))
                .query(ClientInvoiceLineView.class)
                .list();
    }

    /**
     * Receivable against carrier payable, per client, for one billed month.
     *
     * <p>The receivable is the 3PL's revenue: the invoice's taxable amount
     * less what credit notes against it have credited. Tax collected is owed
     * onwards and is not counted.
     *
     * <p>The carrier side is finance's freight bill for each movement on the
     * invoice, apportioned by the client's share of the load's chargeable
     * weight -- two clients on one lorry each carry their part of its cost.
     * Read straight from finance's table: a read across aggregates is what
     * the query side is for, and it keeps finance unaware that clients exist.
     *
     * <p>The 3PL's own number. Never shown to a client portal user.
     */
    @PreAuthorize("hasAuthority('CLIENT_MARGIN_READ')")
    public Slice<ClientMarginView> margins(LocalDate period, String cursor, Integer limit) {
        access.refusePortalUsers("The margin view");
        int pageSize = Slice.clampLimit(limit);

        List<ClientMarginView> rows = jdbc.sql("""
                        SELECT x.client_partner_id, x.client_code, x.client_name, x.invoice_id,
                               x.invoice_no, x.period_start, x.currency, x.receivable,
                               x.transport_receivable,
                               x.receivable - x.transport_receivable AS warehouse_receivable,
                               x.credited, x.carrier_payable,
                               x.receivable - x.carrier_payable AS margin,
                               CASE WHEN x.receivable = 0 THEN NULL
                                    ELSE round((x.receivable - x.carrier_payable) * 100
                                               / x.receivable, 2) END AS margin_pct,
                               x.movements, x.unpriced_movements
                          FROM (
                        SELECT i.client_partner_id, p.code AS client_code,
                               p.legal_name AS client_name, i.id AS invoice_id, i.invoice_no,
                               i.period_start, i.currency,
                               i.total_amount - i.tax_amount - coalesce(cr.taxable, 0)
                                   AS receivable,
                               coalesce(r.transport, 0) - coalesce(cr.transport, 0)
                                   AS transport_receivable,
                               coalesce(cr.taxable, 0) AS credited,
                               coalesce(c.payable, 0) AS carrier_payable,
                               coalesce(c.movements, 0) AS movements,
                               coalesce(c.unpriced, 0) AS unpriced_movements
                          FROM client_invoice i
                          JOIN business_partner p ON p.id = i.client_partner_id
                          LEFT JOIN LATERAL (
                                SELECT sum(l.amount) AS transport
                                  FROM client_invoice_line l
                                 WHERE l.tenant_id = i.tenant_id AND l.invoice_id = i.id
                                   AND l.service_type = 'TRANSPORT') r ON true
                          -- Credit notes reduce the period they correct.
                          LEFT JOIN LATERAL (
                                SELECT sum(cl.taxable_amount) AS taxable,
                                       sum(cl.taxable_amount) FILTER (
                                           WHERE cl.service_type = 'TRANSPORT') AS transport
                                  FROM client_credit_note cn
                                  JOIN client_credit_note_line cl
                                    ON cl.tenant_id = cn.tenant_id AND cl.credit_note_id = cn.id
                                 WHERE cn.tenant_id = i.tenant_id AND cn.invoice_id = i.id) cr ON true
                          LEFT JOIN LATERAL (
                                SELECT round(sum(coalesce(fb.computed_amount, 0) * ch.cost_share), 2)
                                           AS payable,
                                       count(*)::int AS movements,
                                       count(*) FILTER (WHERE fb.id IS NULL
                                                         OR fb.status = 'UNPRICED')::int AS unpriced
                                  FROM client_charge ch
                                  LEFT JOIN freight_bill fb
                                         ON fb.tenant_id = ch.tenant_id AND fb.trip_id = ch.trip_id
                                        -- A trip has one live bill; a cancelled one
                                        -- (superseded by a milk-run circuit's) is not
                                        -- a cost and must not double the row.
                                        AND fb.status <> 'CANCELLED'
                                 WHERE ch.tenant_id = i.tenant_id AND ch.invoice_id = i.id
                                   AND ch.service_type = 'TRANSPORT') c ON true
                         WHERE i.period_start = :period
                           AND (CAST(:after AS uuid) IS NULL
                                OR i.client_partner_id > CAST(:after AS uuid))
                         ORDER BY i.client_partner_id
                         LIMIT :limit
                               ) x
                         ORDER BY x.client_partner_id
                        """)
                .param("period", period.withDayOfMonth(1))
                .param("after", parseUuidCursor(cursor))
                .param("limit", pageSize + 1)
                .query(ClientMarginView.class)
                .list();

        return Slice.of(rows, pageSize, view -> view.clientPartnerId().toString());
    }

    /**
     * One credit note with its lines; empty for one the caller may not see,
     * the same answer as for one that does not exist.
     */
    public Optional<ClientCreditNoteView> findCreditNote(UUID creditNoteId) {
        Optional<CreditNoteRow> note = jdbc.sql("""
                        SELECT cn.id, cn.credit_note_no, cn.invoice_id, i.invoice_no,
                               cn.client_partner_id, cn.period_start, cn.reason, cn.currency,
                               cn.taxable_amount, cn.tax_amount, cn.total_amount, cn.line_count,
                               cn.issued_at
                          FROM client_credit_note cn
                          JOIN client_invoice i ON i.id = cn.invoice_id
                         WHERE cn.id = :id
                           AND (CAST(:bound AS uuid) IS NULL
                                OR cn.client_partner_id = CAST(:bound AS uuid))
                        """)
                .param("id", creditNoteId)
                .param("bound", access.boundClient().orElse(null))
                .query(CreditNoteRow.class)
                .optional();
        return note.map(n -> new ClientCreditNoteView(n.id(), n.creditNoteNo(), n.invoiceId(),
                n.invoiceNo(), n.clientPartnerId(), n.periodStart(), n.reason(), n.currency(),
                n.taxableAmount(), n.taxAmount(), n.totalAmount(), n.lineCount(), n.issuedAt(),
                jdbc.sql("""
                                SELECT c.line_no, l.line_no AS invoice_line_no, c.service_type,
                                       c.narrative, c.taxable_amount, c.tax_rate, c.tax_amount,
                                       c.taxable_amount + c.tax_amount AS line_total
                                  FROM client_credit_note_line c
                                  JOIN client_invoice_line l ON l.id = c.invoice_line_id
                                 WHERE c.credit_note_id = :id
                                 ORDER BY c.line_no
                                """)
                        .param("id", creditNoteId)
                        .query(ClientCreditNoteView.Line.class)
                        .list()));
    }

    /**
     * A client's statement: invoices and credit notes in the order they were
     * issued, each with the running balance after it.
     *
     * <p>Keyset-paged on (issued at, document id). The balance is over the
     * client's whole history, so a page after the first opens with the sum of
     * every entry before its cursor -- one indexed aggregate per page, over
     * a client's documents, which number a handful a month.
     */
    public Slice<ClientStatementEntryView> statementOf(UUID clientPartnerId, String cursor,
                                                      Integer limit) {
        access.requireAccessTo(clientPartnerId);
        int pageSize = Slice.clampLimit(limit);
        StatementCursor after = parseStatementCursor(cursor);

        BigDecimal opening = after == null ? BigDecimal.ZERO : jdbc.sql("""
                        SELECT coalesce(sum(signed), 0) FROM (
                            SELECT i.total_amount AS signed
                              FROM client_invoice i
                             WHERE i.client_partner_id = :client
                               AND (i.issued_at, i.id) <= (CAST(:afterAt AS timestamptz),
                                                           CAST(:afterId AS uuid))
                            UNION ALL
                            SELECT -cn.total_amount
                              FROM client_credit_note cn
                             WHERE cn.client_partner_id = :client
                               AND (cn.issued_at, cn.id) <= (CAST(:afterAt AS timestamptz),
                                                             CAST(:afterId AS uuid))
                        ) before_cursor
                        """)
                .param("client", clientPartnerId)
                .param("afterAt", after.issuedAt().atOffset(ZoneOffset.UTC))
                .param("afterId", after.documentId())
                .query(BigDecimal.class)
                .single();

        List<ClientStatementEntryView> rows = jdbc.sql("""
                        SELECT * FROM (
                            SELECT 'INVOICE' AS entry_type, i.id AS document_id,
                                   i.invoice_no AS document_no, i.issued_at, i.period_start,
                                   i.invoice_no, NULL AS reason, i.currency,
                                   i.total_amount AS debit, 0 AS credit, 0 AS balance
                              FROM client_invoice i
                             WHERE i.client_partner_id = :client
                               AND (CAST(:afterAt AS timestamptz) IS NULL
                                    OR (i.issued_at, i.id) > (CAST(:afterAt AS timestamptz),
                                                              CAST(:afterId AS uuid)))
                            UNION ALL
                            SELECT 'CREDIT_NOTE', cn.id, cn.credit_note_no, cn.issued_at,
                                   cn.period_start, ci.invoice_no, cn.reason, cn.currency,
                                   0, cn.total_amount, 0
                              FROM client_credit_note cn
                              JOIN client_invoice ci ON ci.id = cn.invoice_id
                             WHERE cn.client_partner_id = :client
                               AND (CAST(:afterAt AS timestamptz) IS NULL
                                    OR (cn.issued_at, cn.id) > (CAST(:afterAt AS timestamptz),
                                                                CAST(:afterId AS uuid)))
                        ) entries
                         ORDER BY issued_at, document_id
                         LIMIT :limit
                        """)
                .param("client", clientPartnerId)
                .param("afterAt", after == null ? null : after.issuedAt().atOffset(ZoneOffset.UTC))
                .param("afterId", after == null ? null : after.documentId())
                .param("limit", pageSize + 1)
                .query(ClientStatementEntryView.class)
                .list();

        List<ClientStatementEntryView> withBalance = new ArrayList<>(rows.size());
        BigDecimal balance = opening;
        for (ClientStatementEntryView row : rows) {
            balance = balance.add(row.debit()).subtract(row.credit());
            withBalance.add(row.withBalance(balance));
        }
        return Slice.of(withBalance, pageSize,
                view -> view.issuedAt() + "_" + view.documentId());
    }

    /**
     * A client's ledger-derived storage for one month, day by day. Not
     * computed yet is an answer, not an error: {@code computed} is false and
     * there are no days.
     */
    public ClientStorageView storageOf(UUID clientPartnerId, LocalDate period) {
        access.requireAccessTo(clientPartnerId);
        LocalDate periodStart = period.withDayOfMonth(1);
        List<ClientStorageView.Day> days = jdbc.sql("""
                        SELECT held_on, units_held, units_per_pallet, pallets, charge_id
                          FROM client_storage_day
                         WHERE client_partner_id = :client
                           AND period_start = :period
                         ORDER BY held_on
                        """)
                .param("client", clientPartnerId)
                .param("period", periodStart)
                .query(ClientStorageView.Day.class)
                .list();
        return new ClientStorageView(clientPartnerId, periodStart, !days.isEmpty(),
                days.stream().mapToLong(ClientStorageView.Day::unitsHeld).sum(),
                days.stream().mapToLong(ClientStorageView.Day::pallets).sum(), days);
    }

    record CreditNoteRow(UUID id, String creditNoteNo, UUID invoiceId, String invoiceNo,
                         UUID clientPartnerId, LocalDate periodStart, String reason,
                         String currency, BigDecimal taxableAmount, BigDecimal taxAmount,
                         BigDecimal totalAmount, int lineCount, Instant issuedAt) {
    }

    record StatementCursor(Instant issuedAt, UUID documentId) {
    }

    private static StatementCursor parseStatementCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }
        int split = cursor.lastIndexOf('_');
        if (split <= 0) {
            throw invalidCursor();
        }
        try {
            return new StatementCursor(Instant.parse(cursor.substring(0, split)),
                    UUID.fromString(cursor.substring(split + 1)));
        } catch (DateTimeParseException | IllegalArgumentException e) {
            throw invalidCursor();
        }
    }

    record ContractRow(UUID id, String code, UUID clientPartnerId, String currency,
                       LocalDate effectiveFrom, LocalDate effectiveTo,
                       BigDecimal transportRatePerKg, BigDecimal transportMinimumCharge,
                       BigDecimal storageRatePerPalletDay, BigDecimal handlingInRatePerPallet,
                       BigDecimal handlingOutRatePerPallet, BigDecimal transportTaxRatePct,
                       BigDecimal warehouseTaxRatePct, int storageUnitsPerPallet) {
    }

    record RateRow(UUID contractId, String serviceCode, String unit, BigDecimal rate) {
    }

    private static UUID parseUuidCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(cursor);
        } catch (IllegalArgumentException e) {
            throw invalidCursor();
        }
    }

    private static LocalDate parseDateCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(cursor);
        } catch (DateTimeParseException e) {
            throw invalidCursor();
        }
    }

    private static BusinessRuleViolationException invalidCursor() {
        return new BusinessRuleViolationException("invalid-cursor",
                "The cursor was not issued by this listing");
    }
}
