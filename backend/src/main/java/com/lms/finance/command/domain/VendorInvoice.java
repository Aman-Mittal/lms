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

package com.lms.finance.command.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import com.lms.shared.error.BusinessRuleViolationException;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.relational.core.mapping.Table;

/**
 * The vendor's invoice for one or more approved freight bills, and what is
 * still owed on it.
 *
 * <p>Owed is arithmetic, never a stored guess: the total less the advances
 * paid against the covered trips, less the payments recorded. A payment may be
 * partial and may never be more than what is outstanding, and the status
 * follows from the balance: nothing paid yet is OPEN, something is
 * PARTIALLY_PAID, nothing left is PAID. Overdue is not a status but a
 * question of today's date, so it needs no job to keep it true.
 */
@Table("vendor_invoice")
public record VendorInvoice(
        @Id UUID id,
        UUID tenantId,
        String invoiceNo,
        UUID orgUnitId,
        UUID vendorPartnerId,
        String vendorInvoiceNo,
        LocalDate invoiceDate,
        BigDecimal amount,
        BigDecimal taxAmount,
        BigDecimal totalAmount,
        String currency,
        BigDecimal approvedAmount,
        BigDecimal varianceAmount,
        String documentRef,
        String remarks,
        int paymentTermsDays,
        LocalDate dueDate,
        BigDecimal advancesDeducted,
        BigDecimal paidAmount,
        Status status,
        @Version Long version,
        Instant createdAt,
        Instant updatedAt) {

    public enum Status {
        OPEN,
        PARTIALLY_PAID,
        PAID
    }

    public static VendorInvoice capture(UUID id, UUID tenantId, String invoiceNo, UUID orgUnitId, UUID vendorPartnerId,
                                        String vendorInvoiceNo, LocalDate invoiceDate, BigDecimal amount,
                                        BigDecimal taxAmount, String currency, BigDecimal approvedAmount,
                                        String documentRef, String remarks, int paymentTermsDays,
                                        BigDecimal advancesDeducted) {
        if (vendorInvoiceNo == null || vendorInvoiceNo.isBlank() || vendorInvoiceNo.strip().length() > 60) {
            throw new BusinessRuleViolationException("vendor-invoice-number-required",
                    "The vendor's invoice number is 1 to 60 characters");
        }
        if (invoiceDate == null) {
            throw new BusinessRuleViolationException("vendor-invoice-date-required", "The invoice has a date");
        }
        if (amount == null || amount.signum() <= 0) {
            throw new BusinessRuleViolationException("vendor-invoice-amount-invalid",
                    "The invoice amount is above zero");
        }
        BigDecimal tax = taxAmount == null ? BigDecimal.ZERO : taxAmount;
        if (tax.signum() < 0) {
            throw new BusinessRuleViolationException("vendor-invoice-tax-invalid", "The tax is zero or more");
        }
        BigDecimal total = amount.add(tax);
        BigDecimal advances = advancesDeducted == null ? BigDecimal.ZERO : advancesDeducted;
        Instant now = Instant.now();
        VendorInvoice invoice = new VendorInvoice(id, tenantId, invoiceNo, orgUnitId, vendorPartnerId,
                vendorInvoiceNo.strip(), invoiceDate, amount, tax, total, currency, approvedAmount,
                amount.subtract(approvedAmount), blankToNull(documentRef), blankToNull(remarks), paymentTermsDays,
                invoiceDate.plusDays(paymentTermsDays), advances, BigDecimal.ZERO, Status.OPEN, null, now, now);
        return invoice.withStatus();
    }

    /** What the vendor is owed once advances are taken off, before any payment. */
    public BigDecimal netPayable() {
        return totalAmount.subtract(advancesDeducted);
    }

    /** What is still to be paid. */
    public BigDecimal outstanding() {
        return netPayable().subtract(paidAmount);
    }

    /** Past its due date with something still owed. */
    public boolean overdueOn(LocalDate today) {
        return status != Status.PAID && dueDate.isBefore(today);
    }

    /** Records a payment of {@code paying}: partial or the whole balance, never more. */
    public VendorInvoice paid(BigDecimal paying) {
        if (paying == null || paying.signum() <= 0) {
            throw new BusinessRuleViolationException("vendor-payment-amount-invalid",
                    "A payment is an amount above zero");
        }
        if (paying.compareTo(outstanding()) > 0) {
            throw new BusinessRuleViolationException("vendor-payment-exceeds-balance",
                    "Invoice " + invoiceNo + " has " + outstanding() + " outstanding; a payment of " + paying
                            + " is more than is owed");
        }
        return new VendorInvoice(id, tenantId, invoiceNo, orgUnitId, vendorPartnerId, vendorInvoiceNo, invoiceDate,
                amount, taxAmount, totalAmount, currency, approvedAmount, varianceAmount, documentRef, remarks,
                paymentTermsDays, dueDate, advancesDeducted, paidAmount.add(paying), status, version, createdAt,
                Instant.now()).withStatus();
    }

    private VendorInvoice withStatus() {
        BigDecimal owed = outstanding();
        Status next = owed.signum() == 0 ? Status.PAID
                : paidAmount.signum() > 0 ? Status.PARTIALLY_PAID : Status.OPEN;
        return new VendorInvoice(id, tenantId, invoiceNo, orgUnitId, vendorPartnerId, vendorInvoiceNo, invoiceDate,
                amount, taxAmount, totalAmount, currency, approvedAmount, varianceAmount, documentRef, remarks,
                paymentTermsDays, dueDate, advancesDeducted, paidAmount, next, version, createdAt, updatedAt);
    }

    private static String blankToNull(String text) {
        return text == null || text.isBlank() ? null : text.strip();
    }
}
