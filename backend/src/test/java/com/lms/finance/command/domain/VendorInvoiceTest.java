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
import java.time.LocalDate;
import java.util.UUID;

import com.lms.shared.error.BusinessRuleViolationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** What is owed on a vendor invoice, and when, without a database. */
class VendorInvoiceTest {

    private static final LocalDate DAY = LocalDate.of(2026, 3, 1);

    private static VendorInvoice invoice(String amount, String tax, int terms, String advances) {
        return VendorInvoice.capture(UUID.randomUUID(), UUID.randomUUID(), "VI-1", UUID.randomUUID(), UUID.randomUUID(),
                " V-77 ", DAY, new BigDecimal(amount), tax == null ? null : new BigDecimal(tax), "INR",
                new BigDecimal(amount), null, null, terms, new BigDecimal(advances));
    }

    private static void refused(Runnable action, String code) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(BusinessRuleViolationException.class,
                e -> assertThat(e.code()).isEqualTo(code));
    }

    @Test
    @DisplayName("the total is amount and tax, the due date follows the terms, and nothing is paid yet")
    void captured() {
        VendorInvoice invoice = invoice("10000", "1800", 30, "0");
        assertThat(invoice.vendorInvoiceNo()).isEqualTo("V-77");
        assertThat(invoice.totalAmount()).isEqualByComparingTo("11800");
        assertThat(invoice.dueDate()).isEqualTo(DAY.plusDays(30));
        assertThat(invoice.outstanding()).isEqualByComparingTo("11800");
        assertThat(invoice.status()).isEqualTo(VendorInvoice.Status.OPEN);
        assertThat(invoice(  "500", null, 0, "0").taxAmount()).isEqualByComparingTo("0");
        assertThat(invoice("500", null, 0, "0").dueDate()).as("terms of zero days: due on the invoice date")
                .isEqualTo(DAY);
    }

    @Test
    @DisplayName("advances are taken off what is owed before anything is paid")
    void advancesAreTakenOff() {
        VendorInvoice invoice = invoice("10000", "0", 30, "4000");
        assertThat(invoice.netPayable()).isEqualByComparingTo("6000");
        assertThat(invoice.outstanding()).isEqualByComparingTo("6000");
        VendorInvoice settled = invoice("10000", "0", 30, "10000");
        assertThat(settled.status()).as("advances covering the whole invoice leave nothing to pay")
                .isEqualTo(VendorInvoice.Status.PAID);
    }

    @Test
    @DisplayName("part payments reduce the balance until it is paid; more than is owed is refused")
    void payments() {
        VendorInvoice invoice = invoice("10000", "0", 30, "2000");
        VendorInvoice part = invoice.paid(new BigDecimal("3000"));
        assertThat(part.status()).isEqualTo(VendorInvoice.Status.PARTIALLY_PAID);
        assertThat(part.paidAmount()).isEqualByComparingTo("3000");
        assertThat(part.outstanding()).isEqualByComparingTo("5000");
        refused(() -> part.paid(new BigDecimal("5000.01")), "vendor-payment-exceeds-balance");
        VendorInvoice paid = part.paid(new BigDecimal("5000"));
        assertThat(paid.status()).isEqualTo(VendorInvoice.Status.PAID);
        assertThat(paid.outstanding()).isEqualByComparingTo("0");
        refused(() -> paid.paid(new BigDecimal("0.01")), "vendor-payment-exceeds-balance");
        refused(() -> invoice.paid(BigDecimal.ZERO), "vendor-payment-amount-invalid");
        refused(() -> invoice.paid(new BigDecimal("-1")), "vendor-payment-amount-invalid");
    }

    @Test
    @DisplayName("overdue is the due date passing with something still owed, and never a paid invoice")
    void overdue() {
        VendorInvoice invoice = invoice("1000", "0", 30, "0");
        assertThat(invoice.overdueOn(DAY.plusDays(30))).as("due that day is not yet late").isFalse();
        assertThat(invoice.overdueOn(DAY.plusDays(31))).isTrue();
        assertThat(invoice.paid(new BigDecimal("1000")).overdueOn(DAY.plusDays(90))).isFalse();
        assertThat(invoice.paid(new BigDecimal("400")).overdueOn(DAY.plusDays(90))).isTrue();
    }

    @Test
    @DisplayName("an invoice needs the vendor's number, a date and an amount")
    void refusedWhenIncomplete() {
        refused(() -> VendorInvoice.capture(UUID.randomUUID(), UUID.randomUUID(), "VI-2", UUID.randomUUID(),
                UUID.randomUUID(), " ", DAY, BigDecimal.TEN, null, "INR", BigDecimal.TEN, null, null, 30,
                BigDecimal.ZERO), "vendor-invoice-number-required");
        refused(() -> VendorInvoice.capture(UUID.randomUUID(), UUID.randomUUID(), "VI-2", UUID.randomUUID(),
                UUID.randomUUID(), "V-1", null, BigDecimal.TEN, null, "INR", BigDecimal.TEN, null, null, 30,
                BigDecimal.ZERO), "vendor-invoice-date-required");
        refused(() -> invoice("0", null, 30, "0"), "vendor-invoice-amount-invalid");
        refused(() -> invoice("10", "-1", 30, "0"), "vendor-invoice-tax-invalid");
    }

    @Test
    @DisplayName("the match allows the greater of a percentage and an absolute floor, in either direction")
    void match() {
        BigDecimal two = new BigDecimal("2");
        BigDecimal hundred = new BigDecimal("100");
        InvoiceMatch large = InvoiceMatch.of(new BigDecimal("50000"), new BigDecimal("50900"), two, hundred);
        assertThat(large.allowance()).isEqualByComparingTo("1000");
        assertThat(large.variance()).isEqualByComparingTo("900");
        assertThat(large.within()).isTrue();
        assertThat(InvoiceMatch.of(new BigDecimal("50000"), new BigDecimal("51001"), two, hundred).within()).isFalse();
        assertThat(InvoiceMatch.of(new BigDecimal("50000"), new BigDecimal("48999"), two, hundred).within())
                .as("an underclaim is not free money").isFalse();
        InvoiceMatch small = InvoiceMatch.of(new BigDecimal("1000"), new BigDecimal("1090"), two, hundred);
        assertThat(small.allowance()).as("the floor is what makes a small bill usable").isEqualByComparingTo("100");
        assertThat(small.within()).isTrue();
        assertThat(InvoiceMatch.of(new BigDecimal("1000"), new BigDecimal("1101"), two, hundred).within()).isFalse();
    }
}
