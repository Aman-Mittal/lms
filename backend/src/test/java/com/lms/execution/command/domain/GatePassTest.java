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
package com.lms.execution.command.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.lms.shared.error.BusinessRuleViolationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The gate pass rules and the register's visit (issue #223), with no database. */
class GatePassTest {

    private static final Instant FROM = Instant.parse("2026-11-04T06:00:00Z");
    private static final Instant UNTIL = Instant.parse("2026-11-04T18:00:00Z");

    private static GatePass pass() {
        return GatePass.issue(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), null, "GP-1", null,
                "mh 12 ab 1234", null, GateVisit.Purpose.VISITOR, FROM, UNTIL);
    }

    @Test
    @DisplayName("registration numbers are compared upper case with no blanks")
    void registrations() {
        assertThat(Registrations.normalise(" mh 12\tab 1234 ")).isEqualTo("MH12AB1234");
        assertThat(Registrations.normalise("   ")).isNull();
        assertThat(Registrations.normalise(null)).isNull();
        assertThat(pass().registrationNo()).isEqualTo("MH12AB1234");
        assertThat(Registrations.seals(java.util.Arrays.asList(" S-1 ", "", null, "S-2"))).containsExactly("S-1", "S-2");
    }

    @Test
    @DisplayName("a pass admits inside its window, inclusive, and is expired outside it")
    void window() {
        GatePass pass = pass();

        assertThat(pass.admitsAt(FROM)).isTrue();
        assertThat(pass.admitsAt(UNTIL)).isTrue();
        assertThat(pass.refusalAt(FROM.minusSeconds(1))).isEqualTo("gate-pass-expired");
        assertThat(pass.refusalAt(UNTIL.plusSeconds(1))).isEqualTo("gate-pass-expired");
    }

    @Test
    @DisplayName("a window that does not end after it starts cannot be issued")
    void windowOrdered() {
        assertThatThrownBy(() -> GatePass.issue(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), null,
                "GP-2", null, "MH12", null, GateVisit.Purpose.TRIP, UNTIL, FROM))
                .isInstanceOf(BusinessRuleViolationException.class).extracting("code")
                .isEqualTo("gate-pass-window-invalid");
    }

    @Test
    @DisplayName("a used pass is refused as used even after its window, and cannot be used or cancelled again")
    void used() {
        GatePass used = pass().use(UUID.randomUUID(), FROM.plusSeconds(60));

        assertThat(used.status()).isEqualTo(GatePass.Status.USED);
        assertThat(used.refusalAt(FROM.plusSeconds(120))).isEqualTo("gate-pass-used");
        assertThat(used.refusalAt(UNTIL.plusSeconds(1))).isEqualTo("gate-pass-used");
        assertThatThrownBy(() -> used.use(UUID.randomUUID(), FROM.plusSeconds(120)))
                .isInstanceOf(BusinessRuleViolationException.class).extracting("code").isEqualTo("gate-pass-used");
        assertThatThrownBy(() -> used.cancel("changed plan", UNTIL)).isInstanceOf(BusinessRuleViolationException.class)
                .extracting("code").isEqualTo("gate-pass-used");
    }

    @Test
    @DisplayName("cancelling keeps the reason, admits nothing, and cannot be repeated or done without a reason")
    void cancelled() {
        GatePass cancelled = pass().cancel("  driver changed ", FROM);

        assertThat(cancelled.status()).isEqualTo(GatePass.Status.CANCELLED);
        assertThat(cancelled.cancelReason()).isEqualTo("driver changed");
        assertThat(cancelled.refusalAt(FROM.plusSeconds(60))).isEqualTo("gate-pass-cancelled");
        assertThatThrownBy(() -> cancelled.cancel("again", UNTIL)).isInstanceOf(BusinessRuleViolationException.class);
        assertThatThrownBy(() -> pass().cancel(" ", UNTIL)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("a visit is closed once by an exit no earlier than its entry")
    void visit() {
        GateVisit visit = GateVisit.enter(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), null, null,
                "mh 12 ab 1234", null, "Ravi", GateVisit.Purpose.VISITOR, null, null, FROM, new String[] {"S-1"},
                null, null);
        assertThat(visit.isOpen()).isTrue();
        assertThat(visit.registrationNo()).isEqualTo("MH12AB1234");

        assertThatThrownBy(() -> visit.exit(FROM.minusSeconds(1), null, null, null))
                .isInstanceOf(BusinessRuleViolationException.class).extracting("code")
                .isEqualTo("gate-exit-before-entry");
        GateVisit closed = visit.exit(FROM.plusSeconds(1800), new String[] {"S-1", "S-2"}, "WB-9", null);
        assertThat(closed.isOpen()).isFalse();
        assertThat(closed.exitSealNos()).containsExactly("S-1", "S-2");
        assertThatThrownBy(() -> closed.exit(UNTIL, null, null, null)).isInstanceOf(BusinessRuleViolationException.class)
                .extracting("code").isEqualTo("gate-visit-closed");
        assertThat(List.of(closed.entrySealNos())).containsExactly("S-1");
    }
}
