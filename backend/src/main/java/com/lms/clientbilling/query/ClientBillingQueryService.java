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
import java.time.format.DateTimeParseException;
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
                   i.currency, i.total_amount, i.line_count, i.issued_at
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
                               handling_out_rate_per_pallet
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
                               l.amount, l.charge_id, ch.occurred_on, k.code AS contract_code
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
                        SELECT i.client_partner_id, p.code AS client_code,
                               p.legal_name AS client_name, i.id AS invoice_id, i.invoice_no,
                               i.period_start, i.currency,
                               i.total_amount AS receivable,
                               coalesce(r.transport, 0) AS transport_receivable,
                               i.total_amount - coalesce(r.transport, 0) AS warehouse_receivable,
                               coalesce(c.payable, 0) AS carrier_payable,
                               i.total_amount - coalesce(c.payable, 0) AS margin,
                               CASE WHEN i.total_amount = 0 THEN NULL
                                    ELSE round((i.total_amount - coalesce(c.payable, 0)) * 100
                                               / i.total_amount, 2) END AS margin_pct,
                               coalesce(c.movements, 0) AS movements,
                               coalesce(c.unpriced, 0) AS unpriced_movements
                          FROM client_invoice i
                          JOIN business_partner p ON p.id = i.client_partner_id
                          LEFT JOIN LATERAL (
                                SELECT sum(l.amount) AS transport
                                  FROM client_invoice_line l
                                 WHERE l.tenant_id = i.tenant_id AND l.invoice_id = i.id
                                   AND l.service_type = 'TRANSPORT') r ON true
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
                        """)
                .param("period", period.withDayOfMonth(1))
                .param("after", parseUuidCursor(cursor))
                .param("limit", pageSize + 1)
                .query(ClientMarginView.class)
                .list();

        return Slice.of(rows, pageSize, view -> view.clientPartnerId().toString());
    }

    record ContractRow(UUID id, String code, UUID clientPartnerId, String currency,
                       LocalDate effectiveFrom, LocalDate effectiveTo,
                       BigDecimal transportRatePerKg, BigDecimal transportMinimumCharge,
                       BigDecimal storageRatePerPalletDay, BigDecimal handlingInRatePerPallet,
                       BigDecimal handlingOutRatePerPallet) {
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
