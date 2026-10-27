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
package com.lms.clientbilling.command;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import com.lms.clientbilling.command.domain.ClientCreditNote;
import com.lms.clientbilling.command.domain.ClientCreditNoteLine;
import com.lms.clientbilling.command.domain.ClientInvoice;
import com.lms.clientbilling.command.domain.CreditNoteAllocation;
import com.lms.clientbilling.command.domain.ServiceType;
import com.lms.clientbilling.events.ClientCreditNoteIssued;
import com.lms.clientbilling.query.ClientAccess;
import com.lms.shared.audit.AuditTrail;
import com.lms.shared.config.Json;
import com.lms.shared.error.ResourceNotFoundException;
import com.lms.shared.tenant.TenantContext;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Correcting an issued client invoice with a credit note.
 *
 * <p>The invoice itself never changes. A credit note names it and credits its
 * lines -- named lines, or an amount spread over all of them -- never past
 * what remains creditable on each, and reverses the tax that went with what
 * it credits ({@link CreditNoteAllocation}). It takes the same per-client lock
 * as the billing run and charge capture, so two credit notes against one
 * invoice cannot each see room the other is about to use.
 */
@Service
public class ClientCreditNoteService {

    private static final DateTimeFormatter PERIOD = DateTimeFormatter.ofPattern("yyyyMM");

    /** One invoice line to credit; a null amount credits what remains on it. */
    public record LineCredit(Integer lineNo, BigDecimal amount) {
    }

    private final ClientInvoiceRepository invoices;
    private final ClientCreditNoteRepository creditNotes;
    private final ClientCreditNoteLineRepository creditNoteLines;
    private final ClientPeriods periods;
    private final ClientAccess access;
    private final JdbcClient jdbc;
    private final AuditTrail audit;
    private final ApplicationEventPublisher events;

    public ClientCreditNoteService(ClientInvoiceRepository invoices,
                                   ClientCreditNoteRepository creditNotes,
                                   ClientCreditNoteLineRepository creditNoteLines,
                                   ClientPeriods periods, ClientAccess access, JdbcClient jdbc,
                                   AuditTrail audit, ApplicationEventPublisher events) {
        this.invoices = invoices;
        this.creditNotes = creditNotes;
        this.creditNoteLines = creditNoteLines;
        this.periods = periods;
        this.access = access;
        this.jdbc = jdbc;
        this.audit = audit;
        this.events = events;
    }

    /**
     * Issues a credit note against an invoice.
     *
     * <p>Exactly one of {@code amount} and {@code lines}: an amount (before
     * tax) spread over the invoice, or named lines.
     *
     * @return the credit note
     */
    @Transactional
    @PreAuthorize("hasAuthority('CLIENT_CREDIT_NOTE_ISSUE')"
            + " and @abac.permits('CLIENT_CREDIT_NOTE_ISSUE', 'CLIENT_INVOICE', #invoiceId)")
    public UUID issue(UUID invoiceId, String reason, BigDecimal amount, List<LineCredit> lines) {
        UUID tenantId = TenantContext.requireTenantId();
        ClientInvoice invoice = invoices.findById(invoiceId)
                .filter(found -> access.boundClient()
                        .map(bound -> bound.equals(found.clientPartnerId()))
                        .orElse(true))
                .orElseThrow(() -> new ResourceNotFoundException("ClientInvoice", invoiceId));

        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException(
                    "A credit note must say why: it is the record a client's auditor reads");
        }
        boolean byLine = lines != null && !lines.isEmpty();
        if (byLine == (amount != null)) {
            throw new IllegalArgumentException(
                    "A credit note is either for an amount or for named lines, not both or neither");
        }

        periods.lock(tenantId, invoice.clientPartnerId());

        List<CreditNoteAllocation.Creditable> creditable = creditableLines(invoice.id());
        List<CreditNoteAllocation.Credit> credits;
        if (byLine) {
            CreditNoteAllocation.requireDistinct(lines.stream().map(LineCredit::lineNo).toList());
            Map<Integer, BigDecimal> requested = new LinkedHashMap<>();
            lines.forEach(line -> requested.put(line.lineNo(), line.amount()));
            credits = CreditNoteAllocation.forLines(creditable, requested);
        } else {
            credits = CreditNoteAllocation.forAmount(creditable, amount);
        }

        BigDecimal taxable = credits.stream()
                .map(CreditNoteAllocation.Credit::taxable)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal tax = credits.stream()
                .map(CreditNoteAllocation.Credit::tax)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        Instant now = Instant.now();
        ClientCreditNote note = creditNotes.save(ClientCreditNote.issue(UUID.randomUUID(),
                tenantId, invoice, newCreditNoteNo(invoice), reason, taxable, tax,
                credits.size(), now));
        int lineNo = 1;
        for (CreditNoteAllocation.Credit credit : credits) {
            creditNoteLines.save(ClientCreditNoteLine.of(UUID.randomUUID(), tenantId, note.id(),
                    lineNo++, credit));
        }

        audit.recordAtomically("CLIENT_CREDIT_NOTE_ISSUED", "CLIENT_CREDIT_NOTE", note.id(), null,
                Json.object("invoice", invoice.invoiceNo(),
                        "client", invoice.clientPartnerId().toString(),
                        "period", invoice.periodStart().toString(),
                        "reason", note.reason(),
                        "taxable", taxable.toPlainString(), "tax", tax.toPlainString(),
                        "total", note.totalAmount().toPlainString(),
                        "lines", String.valueOf(credits.size())));
        events.publishEvent(new ClientCreditNoteIssued(tenantId, note.id(), note.creditNoteNo(),
                invoice.id(), invoice.clientPartnerId(), invoice.periodStart(),
                note.currency(), taxable, tax, note.totalAmount(), now));
        return note.id();
    }

    private record CreditableRow(UUID invoiceLineId, int lineNo, String serviceType,
                                 String narrative, BigDecimal taxable, BigDecimal taxRate,
                                 BigDecimal tax, BigDecimal creditedTaxable,
                                 BigDecimal creditedTax) {
    }

    /** The invoice's lines with what has been credited on each so far. */
    private List<CreditNoteAllocation.Creditable> creditableLines(UUID invoiceId) {
        return jdbc.sql("""
                        SELECT l.id AS invoice_line_id, l.line_no, l.service_type, l.narrative,
                               l.amount AS taxable, l.tax_rate, l.tax_amount AS tax,
                               coalesce(sum(c.taxable_amount), 0) AS credited_taxable,
                               coalesce(sum(c.tax_amount), 0) AS credited_tax
                          FROM client_invoice_line l
                          LEFT JOIN client_credit_note_line c
                                 ON c.tenant_id = l.tenant_id AND c.invoice_line_id = l.id
                         WHERE l.invoice_id = :invoiceId
                         GROUP BY l.id, l.line_no, l.service_type, l.narrative, l.amount,
                                  l.tax_rate, l.tax_amount
                         ORDER BY l.line_no
                        """)
                .param("invoiceId", invoiceId)
                .query(CreditableRow.class)
                .list()
                .stream()
                .map(r -> new CreditNoteAllocation.Creditable(r.invoiceLineId(), r.lineNo(),
                        ServiceType.valueOf(r.serviceType()), r.narrative(), r.taxable(),
                        r.taxRate(), r.tax(), r.creditedTaxable(), r.creditedTax()))
                .toList();
    }

    /** Readable and collision-resistant, like an invoice number. */
    private static String newCreditNoteNo(ClientInvoice invoice) {
        return "CN-" + PERIOD.format(invoice.periodStart()) + "-"
                + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
    }
}
