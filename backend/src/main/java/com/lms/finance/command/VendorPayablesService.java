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

package com.lms.finance.command;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import com.lms.finance.command.domain.FreightBill;
import com.lms.finance.command.domain.InvoiceMatch;
import com.lms.finance.command.domain.VendorAdvance;
import com.lms.finance.command.domain.VendorInvoice;
import com.lms.finance.command.domain.VendorPayment;
import com.lms.finance.events.VendorInvoiceCaptured;
import com.lms.finance.events.VendorPaymentRecorded;
import com.lms.identity.numbering.DocumentNumbers;
import com.lms.identity.numbering.DocumentType;
import com.lms.settings.api.EffectiveSettings;
import com.lms.settings.api.SettingContext;
import com.lms.settings.api.Settings;
import com.lms.shared.audit.TransitionAudit;
import com.lms.shared.config.Json;
import com.lms.shared.error.BusinessRuleViolationException;
import com.lms.shared.error.ResourceNotFoundException;
import com.lms.shared.tenant.TenantContext;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Closing out what a vendor is owed: capturing the vendor's invoice against
 * approved freight bills and recording the payments made against it
 * (issue #225).
 *
 * <p>The invoice is matched, not trusted. Its amount must agree with what the
 * bills it covers were approved at -- the vendor's claim the bill was approved
 * against, else the computed amount -- inside the same allowance the freight
 * bill's own match uses ({@code finance.match.tolerancePercent} and
 * {@code .toleranceAbsolute}, for the bills' unit and vendor); an invoice
 * outside it is refused with {@code vendor-invoice-mismatch}, and the vendor
 * is asked for a corrected one. Its due date is the invoice date plus the
 * vendor's {@code finance.payables.paymentTermsDays}, settled when it is
 * captured and kept on it. Advances paid against the covered trips are taken
 * off at capture. Payments may be part or whole, never more than is owed.
 */
@Service
public class VendorPayablesService {

    static final String AUDIT_TYPE = "VENDOR_INVOICE";

    /** An invoice to capture. */
    public record CaptureCommand(String vendorInvoiceNo, LocalDate invoiceDate, BigDecimal amount,
                                 BigDecimal taxAmount, List<UUID> freightBillIds, String documentRef,
                                 String remarks) {
    }

    /** A payment to record. */
    public record PaymentCommand(BigDecimal amount, LocalDate paidOn, VendorPayment.Mode mode, String reference) {
    }

    private final FreightBillRepository bills;
    private final VendorInvoiceRepository invoices;
    private final VendorPaymentRepository payments;
    private final VendorAdvanceRepository advances;
    private final Settings settings;
    private final DocumentNumbers numbers;
    private final TransitionAudit audit;
    private final ApplicationEventPublisher events;
    private final JdbcClient jdbc;

    public VendorPayablesService(FreightBillRepository bills, VendorInvoiceRepository invoices,
                                 VendorPaymentRepository payments, VendorAdvanceRepository advances,
                                 Settings settings, DocumentNumbers numbers, TransitionAudit audit,
                                 ApplicationEventPublisher events, JdbcClient jdbc) {
        this.bills = bills;
        this.invoices = invoices;
        this.payments = payments;
        this.advances = advances;
        this.settings = settings;
        this.numbers = numbers;
        this.audit = audit;
        this.events = events;
        this.jdbc = jdbc;
    }

    @Transactional
    @PreAuthorize("hasAuthority('PAYABLES_MANAGE')")
    public UUID capture(CaptureCommand command) {
        UUID tenantId = TenantContext.requireTenantId();
        Set<UUID> ids = command.freightBillIds() == null ? Set.of() : new LinkedHashSet<>(command.freightBillIds());
        if (ids.isEmpty()) {
            throw new BusinessRuleViolationException("vendor-invoice-bills-required",
                    "An invoice covers at least one approved freight bill");
        }
        List<FreightBill> covered = new ArrayList<>();
        for (UUID id : ids) {
            covered.add(bills.findById(id).orElseThrow(() -> new ResourceNotFoundException("FreightBill", id)));
        }
        FreightBill first = covered.get(0);
        for (FreightBill bill : covered) {
            if (!bill.vendorPartnerId().equals(first.vendorPartnerId())) {
                throw new BusinessRuleViolationException("vendor-invoice-mixed-vendors",
                        "An invoice is one vendor's: bills " + first.billNo() + " and " + bill.billNo()
                                + " are owed to different vendors");
            }
            if (!bill.currency().equals(first.currency())) {
                throw new BusinessRuleViolationException("vendor-invoice-mixed-currencies",
                        "An invoice is in one currency");
            }
            if (bill.status() != FreightBill.BillStatus.APPROVED_FOR_PAYMENT) {
                throw new BusinessRuleViolationException("vendor-invoice-bill-not-approved",
                        "Bill " + bill.billNo() + " is " + bill.status()
                                + "; an invoice is captured against bills approved for payment");
            }
        }
        Set<UUID> already = new LinkedHashSet<>(jdbc.sql("""
                        SELECT freight_bill_id FROM vendor_invoice_bill
                         WHERE tenant_id = :tenantId AND freight_bill_id IN (:ids)
                        """)
                .param("tenantId", tenantId)
                .param("ids", ids)
                .query(UUID.class)
                .list());
        if (!already.isEmpty()) {
            throw new BusinessRuleViolationException("vendor-invoice-bill-already-invoiced",
                    "Bill " + covered.stream().filter(b -> already.contains(b.id())).findFirst().orElseThrow()
                            .billNo() + " is already covered by a vendor invoice");
        }
        UUID vendor = first.vendorPartnerId();
        if (invoices.findByVendorNumber(tenantId, vendor, command.vendorInvoiceNo() == null ? "" : command.vendorInvoiceNo())
                .isPresent()) {
            throw duplicate(command.vendorInvoiceNo());
        }

        BigDecimal approved = covered.stream().map(VendorPayablesService::approvedAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        SettingContext context = SettingContext.ofOrgUnit(first.orgUnitId()).withVendor(vendor);
        EffectiveSettings effective = settings.resolveAll(List.of(FinanceSettingDefinitions.MATCH_TOLERANCE_PERCENT,
                FinanceSettingDefinitions.MATCH_TOLERANCE_ABSOLUTE, FinanceSettingDefinitions.PAYMENT_TERMS_DAYS),
                context);
        if (command.amount() == null || command.amount().signum() <= 0) {
            throw new BusinessRuleViolationException("vendor-invoice-amount-invalid",
                    "The invoice amount is above zero");
        }
        InvoiceMatch match = InvoiceMatch.of(approved, command.amount(),
                effective.get(FinanceSettingDefinitions.MATCH_TOLERANCE_PERCENT),
                effective.get(FinanceSettingDefinitions.MATCH_TOLERANCE_ABSOLUTE));
        if (!match.within()) {
            throw new BusinessRuleViolationException("vendor-invoice-mismatch",
                    "The invoice is for " + command.amount() + " against " + approved + " approved on "
                            + covered.size() + " bill(s), a difference of " + match.variance()
                            + " beyond the allowance of " + match.allowance()
                            + "; ask the vendor for a corrected invoice");
        }

        // The advances paid against these trips are taken off what is owed, oldest
        // first and only while they fit: an advance larger than what is left stays
        // paid and is settled elsewhere.
        BigDecimal total = command.amount().add(command.taxAmount() == null ? BigDecimal.ZERO : command.taxAmount());
        List<VendorAdvance> toDeduct = new ArrayList<>();
        BigDecimal deducted = BigDecimal.ZERO;
        for (FreightBill bill : covered) {
            if (bill.tripId() == null) {
                continue;
            }
            for (VendorAdvance advance : advancesOf(tenantId, bill.tripId())) {
                if (deducted.add(advance.amount()).compareTo(total) <= 0) {
                    deducted = deducted.add(advance.amount());
                    toDeduct.add(advance);
                }
            }
        }

        String number = numbers.next(DocumentType.VENDOR_INVOICE, first.orgUnitId())
                .orElse("VI-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT));
        VendorInvoice invoice = VendorInvoice.capture(UUID.randomUUID(), tenantId, number, first.orgUnitId(), vendor,
                command.vendorInvoiceNo(), command.invoiceDate(), command.amount(), command.taxAmount(),
                first.currency(), approved, command.documentRef(), command.remarks(),
                effective.get(FinanceSettingDefinitions.PAYMENT_TERMS_DAYS), deducted);
        VendorInvoice saved;
        try {
            saved = invoices.save(invoice);
        } catch (DuplicateKeyException race) {
            throw duplicate(command.vendorInvoiceNo());
        }
        for (FreightBill bill : covered) {
            jdbc.sql("""
                            INSERT INTO vendor_invoice_bill (tenant_id, invoice_id, freight_bill_id, approved_amount)
                            VALUES (:tenantId, :invoiceId, :billId, :approved)
                            """)
                    .param("tenantId", tenantId)
                    .param("invoiceId", saved.id())
                    .param("billId", bill.id())
                    .param("approved", approvedAmount(bill))
                    .update();
        }
        Instant now = Instant.now();
        for (VendorAdvance advance : toDeduct) {
            advances.save(advance.deducted(saved.id(), now));
        }

        audit.record("VENDOR_INVOICE_CAPTURED", AUDIT_TYPE, saved.id(), null,
                Json.object("invoiceNo", saved.invoiceNo(), "vendorInvoiceNo", saved.vendorInvoiceNo(),
                        "amount", saved.amount().toPlainString(), "tax", saved.taxAmount().toPlainString(),
                        "approved", approved.toPlainString(), "variance", match.variance().toPlainString(),
                        "advancesDeducted", deducted.toPlainString(), "dueDate", saved.dueDate().toString(),
                        "bills", String.valueOf(covered.size())));
        events.publishEvent(new VendorInvoiceCaptured(tenantId, saved.id(), saved.invoiceNo(), vendor,
                saved.vendorInvoiceNo(), saved.invoiceDate(), saved.dueDate(), saved.amount(), saved.taxAmount(),
                saved.totalAmount(), deducted, saved.currency(), covered.size(), now));
        return saved.id();
    }

    /**
     * Records a payment against an invoice: part or whole, never more than is
     * outstanding. The invoice row is locked first, so two payments racing for
     * the last of the balance are served one after the other and the second is
     * refused, not both let through.
     */
    @Transactional
    @PreAuthorize("hasAuthority('PAYABLES_MANAGE')")
    public UUID recordPayment(UUID invoiceId, PaymentCommand command) {
        UUID tenantId = TenantContext.requireTenantId();
        if (jdbc.sql("SELECT id FROM vendor_invoice WHERE id = :id FOR UPDATE").param("id", invoiceId)
                .query(UUID.class).optional().isEmpty()) {
            throw new ResourceNotFoundException("VendorInvoice", invoiceId);
        }
        VendorInvoice before = invoices.findById(invoiceId)
                .orElseThrow(() -> new ResourceNotFoundException("VendorInvoice", invoiceId));
        VendorPayment payment = VendorPayment.record(UUID.randomUUID(), tenantId, invoiceId,
                before.vendorPartnerId(), command.amount(), command.paidOn(), command.mode(), command.reference(),
                Actors.currentUserId());
        if (payment.paidOn().isBefore(before.invoiceDate())) {
            throw new BusinessRuleViolationException("vendor-payment-date-invalid",
                    "A payment is not dated before the invoice it pays (" + before.invoiceDate() + ")");
        }
        if (payment.paidOn().isAfter(LocalDate.now(ZoneOffset.UTC).plusDays(1))) {
            throw new BusinessRuleViolationException("vendor-payment-date-invalid",
                    "A payment is recorded when it is made, not dated in the future");
        }
        VendorInvoice after = invoices.save(before.paid(payment.amount()));
        VendorPayment saved;
        try {
            saved = payments.save(payment);
        } catch (DuplicateKeyException race) {
            throw new BusinessRuleViolationException("vendor-payment-reference-taken",
                    "A payment with reference " + payment.reference() + " is already recorded for this vendor");
        }
        audit.record("VENDOR_PAYMENT_RECORDED", AUDIT_TYPE, invoiceId,
                Json.object("status", before.status().name(), "paid", before.paidAmount().toPlainString(),
                        "outstanding", before.outstanding().toPlainString()),
                Json.object("status", after.status().name(), "paid", after.paidAmount().toPlainString(),
                        "outstanding", after.outstanding().toPlainString(), "payment", saved.reference(),
                        "amount", saved.amount().toPlainString()));
        events.publishEvent(new VendorPaymentRecorded(tenantId, saved.id(), invoiceId, after.invoiceNo(),
                after.vendorPartnerId(), saved.amount(), saved.paidOn(), saved.mode().name(), saved.reference(),
                after.outstanding(), after.status().name(), after.currency(), Instant.now()));
        return saved.id();
    }

    /** What a bill was approved at: the vendor's claim it was approved against, else what the platform computed. */
    static BigDecimal approvedAmount(FreightBill bill) {
        return bill.claimedAmount() != null ? bill.claimedAmount() : bill.computedAmount();
    }

    private List<VendorAdvance> advancesOf(UUID tenantId, UUID tripId) {
        jdbc.sql("SELECT id FROM vendor_advance WHERE tenant_id = :tenantId AND trip_id = :tripId "
                        + "AND status = 'PAID' FOR UPDATE")
                .param("tenantId", tenantId).param("tripId", tripId).query(UUID.class).list();
        return advances.findPaidOnTrip(tenantId, tripId);
    }

    private static BusinessRuleViolationException duplicate(String number) {
        return new BusinessRuleViolationException("vendor-invoice-duplicate",
                "This vendor's invoice " + number + " is already captured");
    }
}
