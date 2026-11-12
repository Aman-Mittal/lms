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

package com.lms.finance.query;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.lms.identity.access.AccessSubject;
import com.lms.shared.query.Search;
import com.lms.shared.query.Slice;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read side of the vendor payables desk: invoices with their balances, the
 * bills each covers, payments, the overdue list and the balance settlement of
 * a freight bill.
 *
 * <p>The list is newest first on {@code vendor_invoice_list_idx}; the overdue
 * list is what is still owed past its due date, earliest first on the partial
 * {@code vendor_invoice_due_idx} (keyset on due date and id). A vendor-bound
 * caller sees only that vendor's own.
 */
@Service
@Transactional(readOnly = true)
@PreAuthorize("hasAnyAuthority('PAYABLES_MANAGE', 'INVOICE_READ')")
public class PayablesQueryService {

    private static final String INVOICE_SELECT = """
            SELECT i.id, i.invoice_no, i.vendor_partner_id, vp.legal_name AS vendor_name, i.vendor_invoice_no,
                   i.invoice_date, i.due_date, i.payment_terms_days, i.amount, i.tax_amount, i.total_amount,
                   i.currency, i.approved_amount, i.variance_amount, i.advances_deducted,
                   (i.total_amount - i.advances_deducted) AS net_payable, i.paid_amount,
                   (i.total_amount - i.advances_deducted - i.paid_amount) AS outstanding, i.status,
                   (i.status <> 'PAID' AND i.due_date < CAST(:today AS date)) AS overdue,
                   CASE WHEN i.status <> 'PAID' AND i.due_date < CAST(:today AS date)
                        THEN (CAST(:today AS date) - i.due_date) ELSE 0 END AS days_overdue,
                   (SELECT count(*) FROM vendor_invoice_bill b WHERE b.invoice_id = i.id) AS bill_count,
                   i.document_ref, i.remarks, i.version, i.created_at
              FROM vendor_invoice i
              JOIN business_partner vp ON vp.id = i.vendor_partner_id
            """;

    private static final String INVOICE_SCOPE = """
               AND (CAST(:partner AS uuid) IS NULL OR i.vendor_partner_id = CAST(:partner AS uuid))
            """;

    private final JdbcClient jdbc;

    public PayablesQueryService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    private static LocalDate today() {
        return LocalDate.now(ZoneOffset.UTC);
    }

    /** Invoices newest first; {@code q} matches our number and the vendor's. */
    public Slice<VendorInvoiceView> invoices(String status, UUID vendorId, String q, String cursor, Integer limit) {
        int pageSize = Slice.clampLimit(limit);
        Keyset after = Keyset.parseTime(cursor);
        List<VendorInvoiceView> rows = jdbc.sql(INVOICE_SELECT + """
                         WHERE (CAST(:status AS text) IS NULL OR i.status = CAST(:status AS text))
                           AND (CAST(:vendorId AS uuid) IS NULL OR i.vendor_partner_id = CAST(:vendorId AS uuid))
                           AND (CAST(:q AS text) IS NULL OR lower(i.invoice_no) LIKE CAST(:q AS text)
                                OR lower(i.vendor_invoice_no) LIKE CAST(:q AS text))
                           AND (CAST(:atBefore AS timestamptz) IS NULL
                                OR (i.created_at, i.id) < (CAST(:atBefore AS timestamptz), CAST(:idBefore AS uuid)))
                        """ + INVOICE_SCOPE + """
                         ORDER BY i.created_at DESC, i.id DESC
                         LIMIT :limit
                        """)
                .param("today", today())
                .param("status", status)
                .param("vendorId", vendorId)
                .param("q", Search.containsPattern(q))
                .param("atBefore", after == null ? null : after.atParam())
                .param("idBefore", after == null ? null : after.id())
                .param("partner", AccessSubject.current().partnerScope())
                .param("limit", pageSize + 1)
                .query(VendorInvoiceView.class)
                .list();
        return Slice.of(rows, pageSize, row -> Keyset.ofTime(row.createdAt(), row.id()));
    }

    /** What is owed past its due date, earliest due first. */
    public Slice<VendorInvoiceView> overdue(UUID vendorId, String cursor, Integer limit) {
        int pageSize = Slice.clampLimit(limit);
        Keyset after = Keyset.parseDay(cursor);
        List<VendorInvoiceView> rows = jdbc.sql(INVOICE_SELECT + """
                         WHERE i.status <> 'PAID' AND i.due_date < CAST(:today AS date)
                           AND (CAST(:vendorId AS uuid) IS NULL OR i.vendor_partner_id = CAST(:vendorId AS uuid))
                           AND (CAST(:dueAfter AS date) IS NULL
                                OR (i.due_date, i.id) > (CAST(:dueAfter AS date), CAST(:idAfter AS uuid)))
                        """ + INVOICE_SCOPE + """
                         ORDER BY i.due_date, i.id
                         LIMIT :limit
                        """)
                .param("today", today())
                .param("vendorId", vendorId)
                .param("dueAfter", after == null ? null : after.day())
                .param("idAfter", after == null ? null : after.id())
                .param("partner", AccessSubject.current().partnerScope())
                .param("limit", pageSize + 1)
                .query(VendorInvoiceView.class)
                .list();
        return Slice.of(rows, pageSize, row -> Keyset.ofDay(row.dueDate(), row.id()));
    }

    public Optional<VendorInvoiceView> invoice(UUID id) {
        return jdbc.sql(INVOICE_SELECT + " WHERE i.id = :id" + INVOICE_SCOPE)
                .param("today", today())
                .param("id", id)
                .param("partner", AccessSubject.current().partnerScope())
                .query(VendorInvoiceView.class)
                .optional();
    }

    /** The bills an invoice covers. */
    public List<VendorInvoiceBillView> billsOf(UUID invoiceId) {
        return jdbc.sql("""
                        SELECT b.freight_bill_id AS bill_id, fb.bill_no, fb.trip_id, t.trip_no, b.approved_amount
                          FROM vendor_invoice_bill b
                          JOIN freight_bill fb ON fb.id = b.freight_bill_id
                          LEFT JOIN trip t ON t.id = fb.trip_id
                         WHERE b.invoice_id = :invoiceId
                           AND EXISTS (SELECT 1 FROM vendor_invoice i WHERE i.id = b.invoice_id
                                          AND (CAST(:partner AS uuid) IS NULL
                                               OR i.vendor_partner_id = CAST(:partner AS uuid)))
                         ORDER BY fb.bill_no
                        """)
                .param("invoiceId", invoiceId)
                .param("partner", AccessSubject.current().partnerScope())
                .query(VendorInvoiceBillView.class)
                .list();
    }

    /** Payments, newest first; one invoice's when {@code invoiceId} is given. */
    public Slice<VendorPaymentView> payments(UUID invoiceId, UUID vendorId, String cursor, Integer limit) {
        int pageSize = Slice.clampLimit(limit);
        Keyset after = Keyset.parseTime(cursor);
        List<VendorPaymentView> rows = jdbc.sql("""
                        SELECT p.id, p.invoice_id, i.invoice_no, i.vendor_invoice_no, p.vendor_partner_id,
                               vp.legal_name AS vendor_name, p.amount, i.currency, p.paid_on, p.mode, p.reference,
                               p.created_at
                          FROM vendor_payment p
                          JOIN vendor_invoice i ON i.id = p.invoice_id
                          JOIN business_partner vp ON vp.id = p.vendor_partner_id
                         WHERE (CAST(:invoiceId AS uuid) IS NULL OR p.invoice_id = CAST(:invoiceId AS uuid))
                           AND (CAST(:vendorId AS uuid) IS NULL OR p.vendor_partner_id = CAST(:vendorId AS uuid))
                           AND (CAST(:partner AS uuid) IS NULL OR p.vendor_partner_id = CAST(:partner AS uuid))
                           AND (CAST(:atBefore AS timestamptz) IS NULL
                                OR (p.created_at, p.id) < (CAST(:atBefore AS timestamptz), CAST(:idBefore AS uuid)))
                         ORDER BY p.created_at DESC, p.id DESC
                         LIMIT :limit
                        """)
                .param("invoiceId", invoiceId)
                .param("vendorId", vendorId)
                .param("partner", AccessSubject.current().partnerScope())
                .param("atBefore", after == null ? null : after.atParam())
                .param("idBefore", after == null ? null : after.id())
                .param("limit", pageSize + 1)
                .query(VendorPaymentView.class)
                .list();
        return Slice.of(rows, pageSize, row -> Keyset.ofTime(row.createdAt(), row.id()));
    }

    /**
     * The balance settlement of a bill: approved freight and charges, the
     * advances paid on its trip and what is payable after them.
     */
    public Optional<BillSettlementView> settlementOf(UUID billId) {
        record Head(UUID id, String billNo, String status, String currency, UUID tripId, String tripNo,
                    java.math.BigDecimal computed, java.math.BigDecimal claimed, java.math.BigDecimal charges) {
        }
        Optional<Head> head = jdbc.sql("""
                        SELECT b.id, b.bill_no, b.status, b.currency, b.trip_id, t.trip_no,
                               b.computed_amount AS computed, b.claimed_amount AS claimed,
                               coalesce((SELECT sum(l.amount) FROM freight_bill_line l
                                          WHERE l.bill_id = b.id AND l.charge_type = 'MANUAL_CHARGE'), 0) AS charges
                          FROM freight_bill b
                          LEFT JOIN trip t ON t.id = b.trip_id
                         WHERE b.id = :id
                           AND (CAST(:partner AS uuid) IS NULL OR b.vendor_partner_id = CAST(:partner AS uuid))
                        """)
                .param("id", billId)
                .param("partner", AccessSubject.current().partnerScope())
                .query(Head.class)
                .optional();
        if (head.isEmpty()) {
            return Optional.empty();
        }
        Head h = head.get();
        List<VendorAdvanceView> advances = h.tripId() == null ? List.of() : jdbc.sql(VendorAdvanceQueryService.SELECT + """
                         WHERE a.trip_id = :tripId AND a.status IN ('PAID', 'DEDUCTED')
                         ORDER BY a.paid_on, a.created_at, a.id
                        """)
                .param("tripId", h.tripId())
                .query(VendorAdvanceView.class)
                .list();
        java.math.BigDecimal paid = advances.stream().map(VendorAdvanceView::amount)
                .reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add);
        java.math.BigDecimal deducted = advances.stream().filter(a -> "DEDUCTED".equals(a.status()))
                .map(VendorAdvanceView::amount).reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add);
        java.math.BigDecimal approved = h.claimed() != null && "APPROVED_FOR_PAYMENT".equals(h.status())
                ? h.claimed() : h.computed();
        record Inv(UUID id, String invoiceNo, String status, java.math.BigDecimal outstanding) {
        }
        Optional<Inv> invoice = jdbc.sql("""
                        SELECT i.id, i.invoice_no, i.status,
                               (i.total_amount - i.advances_deducted - i.paid_amount) AS outstanding
                          FROM vendor_invoice_bill b JOIN vendor_invoice i ON i.id = b.invoice_id
                         WHERE b.freight_bill_id = :billId
                        """)
                .param("billId", billId)
                .query(Inv.class)
                .optional();
        return Optional.of(new BillSettlementView(h.id(), h.billNo(), h.status(), h.currency(), h.tripId(),
                h.tripNo(), approved.subtract(h.charges()), h.charges(), approved, paid, deducted,
                approved.subtract(paid), invoice.map(Inv::id).orElse(null), invoice.map(Inv::invoiceNo).orElse(null),
                invoice.map(Inv::status).orElse(null), invoice.map(Inv::outstanding).orElse(null), advances));
    }
}
