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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The advance cap and the advance's life, without a database. */
class VendorAdvanceTest {

    private static VendorAdvance request(String amount, boolean needsApproval) {
        return VendorAdvance.request(UUID.randomUUID(), UUID.randomUUID(), "ADV-1", UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), VendorAdvance.Mode.BANK, new BigDecimal(amount), "INR",
                "Fuel", new BigDecimal("10000"), "BILL", new BigDecimal("70"), needsApproval, null);
    }

    private static void refused(Runnable action, String code) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(BusinessRuleViolationException.class,
                e -> assertThat(e.code()).isEqualTo(code));
    }

    @Test
    @DisplayName("the cap is a percentage of freight, rounded down, across every live advance")
    void cap() {
        BigDecimal freight = new BigDecimal("10000");
        BigDecimal seventy = new BigDecimal("70");
        assertThat(AdvanceCap.maximum(freight, seventy)).isEqualByComparingTo("7000");
        assertThat(AdvanceCap.maximum(new BigDecimal("999.99"), seventy)).isEqualByComparingTo("699.99");
        assertThat(AdvanceCap.maximum(new BigDecimal("1999.99"), seventy)).as("1399.993 is never rounded up")
                .isEqualByComparingTo("1399.99");
        assertThat(AdvanceCap.remaining(freight, seventy, new BigDecimal("4000"))).isEqualByComparingTo("3000");
        assertThat(AdvanceCap.remaining(freight, seventy, new BigDecimal("9000"))).as("never negative")
                .isEqualByComparingTo("0");
        assertThat(AdvanceCap.fits(freight, seventy, new BigDecimal("4000"), new BigDecimal("3000"))).isTrue();
        assertThat(AdvanceCap.fits(freight, seventy, new BigDecimal("4000"), new BigDecimal("3000.01"))).isFalse();
        assertThat(AdvanceCap.fits(freight, BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("0.01"))).isFalse();
        assertThat(AdvanceCap.fits(freight, new BigDecimal("100"), BigDecimal.ZERO, freight)).isTrue();
    }

    @Test
    @DisplayName("an advance is requested, approved, paid and deducted, in that order only")
    void journey() {
        VendorAdvance pending = request("4000", true);
        assertThat(pending.status()).isEqualTo(VendorAdvance.Status.PENDING_APPROVAL);
        refused(() -> pending.paid("NEFT-1", LocalDate.now(), null), "advance-illegal-transition");

        VendorAdvance approved = pending.approved(Instant.now());
        VendorAdvance paid = approved.paid(" NEFT-1 ", LocalDate.of(2026, 3, 2), UUID.randomUUID());
        assertThat(paid.status()).isEqualTo(VendorAdvance.Status.PAID);
        assertThat(paid.paidReference()).isEqualTo("NEFT-1");
        refused(() -> paid.paid("NEFT-2", LocalDate.now(), null), "advance-illegal-transition");
        refused(() -> approved.paid(" ", LocalDate.now(), null), "advance-reference-required");
        refused(() -> approved.paid("NEFT-1", null, null), "advance-paid-date-required");

        UUID invoice = UUID.randomUUID();
        VendorAdvance deducted = paid.deducted(invoice, Instant.now());
        assertThat(deducted.status()).isEqualTo(VendorAdvance.Status.DEDUCTED);
        assertThat(deducted.deductedInvoiceId()).isEqualTo(invoice);
        refused(() -> approved.deducted(invoice, Instant.now()), "advance-illegal-transition");
        assertThat(request("100", false).status()).as("without a policy asking, approved at once")
                .isEqualTo(VendorAdvance.Status.APPROVED);
    }

    @Test
    @DisplayName("an advance is cancelled before it is paid and never after")
    void cancellation() {
        VendorAdvance pending = request("4000", true);
        VendorAdvance cancelled = pending.cancelled("Not needed", Instant.now());
        assertThat(cancelled.status()).isEqualTo(VendorAdvance.Status.CANCELLED);
        assertThat(cancelled.status().live()).as("a cancelled advance gives the cap and the mode back").isFalse();
        assertThat(pending.approved(Instant.now()).cancelled("Changed", Instant.now()).status())
                .isEqualTo(VendorAdvance.Status.CANCELLED);
        VendorAdvance paid = pending.approved(Instant.now()).paid("NEFT-1", LocalDate.now(), null);
        refused(() -> paid.cancelled("Too late", Instant.now()), "advance-already-paid");
        refused(() -> paid.deducted(UUID.randomUUID(), Instant.now()).cancelled("Too late", Instant.now()),
                "advance-already-paid");
        refused(() -> pending.cancelled(" ", Instant.now()), "advance-cancel-reason-required");
        refused(() -> cancelled.cancelled("Twice", Instant.now()), "advance-illegal-transition");
        assertThat(pending.closedWithoutApproval().status().live()).isFalse();
        assertThat(VendorAdvance.Status.PAID.live()).isTrue();
    }

    @Test
    @DisplayName("an advance needs a mode, an amount and a reason")
    void refusedWhenIncomplete() {
        refused(() -> VendorAdvance.request(UUID.randomUUID(), UUID.randomUUID(), "ADV-2", UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), null, BigDecimal.TEN, "INR", "x", BigDecimal.TEN, "BILL",
                BigDecimal.TEN, false, null), "advance-mode-required");
        refused(() -> request("0", false), "advance-amount-invalid");
        refused(() -> VendorAdvance.request(UUID.randomUUID(), UUID.randomUUID(), "ADV-2", UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), VendorAdvance.Mode.CASH, BigDecimal.TEN, "INR", " ",
                BigDecimal.TEN, "BILL", BigDecimal.TEN, false, null), "advance-reason-required");
    }
}
