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
import java.util.UUID;

import com.lms.shared.error.BusinessRuleViolationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The life of a manual charge, without a database: raised, approved or closed, cancelled only while it can be. */
class ManualChargeTest {

    private static final ChargeCatalogueItem ENTRY = ChargeCatalogueItem.create(UUID.randomUUID(), UUID.randomUUID(),
            "HALT", "Halting", ChargeBasis.PER_DAY, new BigDecimal("400"), ChargeDirection.BOTH, true);
    private static final ManualCharge.Subject SUBJECT = new ManualCharge.Subject(ManualCharge.SubjectType.TRIP,
            UUID.randomUUID(), "TR-1", UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());

    private static ManualCharge raise(String quantity, String rate, boolean needsApproval, ChargeDirection.Side side) {
        return ManualCharge.raise(UUID.randomUUID(), UUID.randomUUID(), "MC-1", ENTRY, side, SUBJECT, UUID.randomUUID(),
                new BigDecimal(quantity), new BigDecimal(rate), "Waited two days", " TOLL-77 ", needsApproval, null);
    }

    private static void refused(Runnable action, String code) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(BusinessRuleViolationException.class,
                e -> assertThat(e.code()).isEqualTo(code));
    }

    @Test
    @DisplayName("the amount is quantity times rate by the entry's basis, and the customer or vendor is the one party")
    void raised() {
        ManualCharge receivable = raise("2", "400", false, ChargeDirection.Side.RECEIVABLE);
        assertThat(receivable.amount()).isEqualByComparingTo("800");
        assertThat(receivable.status()).isEqualTo(ManualCharge.Status.APPROVED);
        assertThat(receivable.customerPartnerId()).isNotNull();
        assertThat(receivable.vendorPartnerId()).isNull();
        assertThat(receivable.evidenceRef()).isEqualTo("TOLL-77");
        ManualCharge payable = raise("1", "50", true, ChargeDirection.Side.PAYABLE);
        assertThat(payable.status()).isEqualTo(ManualCharge.Status.PENDING_APPROVAL);
        assertThat(payable.vendorPartnerId()).isNotNull();
        assertThat(payable.customerPartnerId()).isNull();
    }

    @Test
    @DisplayName("nothing is raised for no quantity, a negative rate, a zero amount or no reason")
    void refusedWhenEmpty() {
        refused(() -> raise("0", "10", false, ChargeDirection.Side.RECEIVABLE), "manual-charge-quantity-invalid");
        refused(() -> raise("1", "-1", false, ChargeDirection.Side.RECEIVABLE), "manual-charge-rate-invalid");
        refused(() -> raise("1", "0", false, ChargeDirection.Side.RECEIVABLE), "manual-charge-amount-zero");
        refused(() -> raise("0.001", "0.01", false, ChargeDirection.Side.RECEIVABLE), "manual-charge-amount-zero");
        refused(() -> ManualCharge.raise(UUID.randomUUID(), UUID.randomUUID(), "MC-2", ENTRY,
                ChargeDirection.Side.RECEIVABLE, SUBJECT, UUID.randomUUID(), BigDecimal.ONE, BigDecimal.ONE, " ", null,
                false, null), "manual-charge-reason-required");
    }

    @Test
    @DisplayName("a pending charge is approved, rejected or cancelled; an approved one only cancelled; no other move")
    void transitions() {
        ManualCharge pending = raise("1", "100", true, ChargeDirection.Side.RECEIVABLE);
        assertThat(pending.approved(Instant.now()).status()).isEqualTo(ManualCharge.Status.APPROVED);
        assertThat(pending.closedWithoutApproval(Instant.now()).status()).isEqualTo(ManualCharge.Status.REJECTED);
        ManualCharge cancelled = pending.cancelled("Raised in error", Instant.now());
        assertThat(cancelled.status()).isEqualTo(ManualCharge.Status.CANCELLED);
        assertThat(cancelled.cancelReason()).isEqualTo("Raised in error");
        assertThat(cancelled.cancelledAt()).isNotNull();

        ManualCharge approved = pending.approved(Instant.now());
        refused(() -> approved.approved(Instant.now()), "manual-charge-illegal-transition");
        refused(() -> approved.closedWithoutApproval(Instant.now()), "manual-charge-illegal-transition");
        assertThat(approved.cancelled("Customer disputed", Instant.now()).status())
                .isEqualTo(ManualCharge.Status.CANCELLED);
        refused(() -> cancelled.cancelled("Again", Instant.now()), "manual-charge-illegal-transition");
        refused(() -> cancelled.approved(Instant.now()), "manual-charge-illegal-transition");
        refused(() -> approved.cancelled(" ", Instant.now()), "manual-charge-cancel-reason-required");
    }

    @Test
    @DisplayName("a charge knows the bill it is a line of, and lets go of it")
    void attachment() {
        ManualCharge approved = raise("1", "100", false, ChargeDirection.Side.PAYABLE);
        UUID bill = UUID.randomUUID();
        ManualCharge attached = approved.attachedTo(bill, Instant.now());
        assertThat(attached.freightBillId()).isEqualTo(bill);
        assertThat(attached.attachedAt()).isNotNull();
        assertThat(attached.detached().freightBillId()).isNull();
        assertThat(attached.detached().attachedAt()).isNull();
    }
}
