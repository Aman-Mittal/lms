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
package com.lms.clientbilling;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import com.lms.clientbilling.command.domain.ClientContract;
import com.lms.clientbilling.command.domain.ClientContract.Status;
import com.lms.shared.error.BusinessRuleViolationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The state machine of a contract version, with no database. */
class ClientContractLifecycleTest {

    private static final LocalDate FROM = LocalDate.of(2026, 3, 1);
    private static final ClientContract.Rates RATES = new ClientContract.Rates(
            new BigDecimal("5"), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);

    private static ClientContract draft(LocalDate validTill) {
        return ClientContract.draft(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "C-1",
                UUID.randomUUID(), "INR", FROM, validTill, RATES, false);
    }

    private static void refusedWith(Runnable action, String code) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(BusinessRuleViolationException.class,
                e -> assertThat(e.code()).isEqualTo(code));
    }

    @Test
    @DisplayName("a draft applies to nothing: it is in force on no day")
    void draftCoversNoDay() {
        ClientContract draft = draft(null);
        assertThat(draft.status()).isEqualTo(Status.DRAFT);
        assertThat(draft.coversOn(FROM.plusDays(10))).isFalse();
        assertThat(draft.submitted().coversOn(FROM.plusDays(10))).isFalse();
        assertThat(draft.submitted().activated().coversOn(FROM.plusDays(10))).isTrue();
    }

    @Test
    @DisplayName("draft, pending, active; a draft may be edited and submitted, nothing else may")
    void happyPath() {
        ClientContract draft = draft(null);
        ClientContract revised = draft.revised(draft.orgUnitId(), "C-1B", "INR", FROM.plusDays(1), null, RATES, true);
        assertThat(revised.code()).isEqualTo("C-1B");
        assertThat(revised.id()).isEqualTo(draft.id());
        assertThat(revised.billOnDispatch()).isTrue();

        ClientContract pending = revised.submitted();
        assertThat(pending.status()).isEqualTo(Status.PENDING_VERIFICATION);
        refusedWith(() -> pending.revised(draft.orgUnitId(), "X", "INR", FROM, null, RATES, false),
                "client-contract-not-draft");
        refusedWith(pending::submitted, "client-contract-not-draft");
        refusedWith(pending::requireDraft, "client-contract-not-draft");

        ClientContract active = pending.activated();
        assertThat(active.status()).isEqualTo(Status.ACTIVE);
        refusedWith(active::activated, "client-contract-not-pending");
        refusedWith(() -> active.returnedToDraft("no"), "client-contract-not-pending");
    }

    @Test
    @DisplayName("a rejection returns the version to draft carrying the reason, and a resubmission clears it")
    void rejection() {
        ClientContract back = draft(null).submitted().returnedToDraft("Attach the rate sheet");
        assertThat(back.status()).isEqualTo(Status.DRAFT);
        assertThat(back.rejectionReason()).isEqualTo("Attach the rate sheet");
        assertThat(back.submitted().rejectionReason()).isNull();
        refusedWith(() -> draft(null).returnedToDraft("x"), "client-contract-not-pending");
    }

    @Test
    @DisplayName("an end date before the start is refused, on creation and on edit")
    void validityIsSane() {
        refusedWith(() -> draft(FROM.minusDays(1)), "client-contract-validity-invalid");
        refusedWith(() -> draft(null).revised(null, "C", "INR", FROM, FROM.minusDays(1), RATES, false),
                "client-contract-validity-invalid");
        assertThat(draft(FROM).validTill()).isEqualTo(FROM);
    }

    @Test
    @DisplayName("expiry is derived from the end date and never stored")
    void expiryIsDerived() {
        ClientContract active = draft(FROM.plusDays(30)).submitted().activated();
        assertThat(active.stateOn(FROM.plusDays(30))).isEqualTo("ACTIVE");
        assertThat(active.stateOn(FROM.plusDays(31))).isEqualTo("EXPIRED");
        assertThat(active.coversOn(FROM.plusDays(30))).isTrue();
        assertThat(active.coversOn(FROM.plusDays(31))).isFalse();
        assertThat(active.status()).isEqualTo(Status.ACTIVE);
        assertThat(draft(FROM.plusDays(30)).stateOn(FROM.plusYears(5))).as("a draft does not expire")
                .isEqualTo("DRAFT");
        assertThat(draft(null).submitted().activated().stateOn(FROM.plusYears(50))).isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("an extension needs a contract in force with an end date, not closed, moved later")
    void extension() {
        ClientContract active = draft(FROM.plusDays(30)).submitted().activated();
        active.requireExtendableTo(FROM.plusDays(31));
        refusedWith(() -> active.requireExtendableTo(FROM.plusDays(30)), "client-contract-extension-not-later");
        refusedWith(() -> active.requireExtendableTo(FROM.plusDays(10)), "client-contract-extension-not-later");
        refusedWith(() -> active.requireExtendableTo(null), "client-contract-extension-not-later");
        refusedWith(() -> draft(FROM.plusDays(30)).requireExtendableTo(FROM.plusDays(40)),
                "client-contract-not-active");
        refusedWith(() -> draft(null).submitted().activated().requireExtendableTo(FROM.plusDays(40)),
                "client-contract-no-end-date");
        refusedWith(() -> active.closeOn(FROM.plusDays(5)).requireExtendableTo(FROM.plusDays(40)),
                "client-contract-already-closed");
    }

    @Test
    @DisplayName("a successor carries the terms, is in force at once and keeps no history of its own")
    void successor() {
        ClientContract active = draft(FROM.plusDays(90)).submitted().activated();
        UUID id = UUID.randomUUID();
        ClientContract next = active.successor(id, FROM.plusDays(10));
        assertThat(next.id()).isEqualTo(id);
        assertThat(next.status()).isEqualTo(Status.ACTIVE);
        assertThat(next.effectiveFrom()).isEqualTo(FROM.plusDays(10));
        assertThat(next.effectiveTo()).isNull();
        assertThat(next.validTill()).isEqualTo(active.validTill());
        assertThat(next.transportRatePerKg()).isEqualByComparingTo(active.transportRatePerKg());
        assertThat(next.version()).isNull();
        assertThat(next.coversOn(FROM.plusDays(9))).isFalse();
    }

    @Test
    @DisplayName("closing a version by a successor keeps its status and end date")
    void closing() {
        ClientContract active = draft(FROM.plusDays(90)).submitted().activated();
        ClientContract closed = active.closeOn(FROM.plusDays(20));
        assertThat(closed.status()).isEqualTo(Status.ACTIVE);
        assertThat(closed.validTill()).isEqualTo(FROM.plusDays(90));
        assertThat(closed.coversOn(FROM.plusDays(21))).isFalse();
    }
}
