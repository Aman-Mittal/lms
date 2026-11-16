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
package com.lms.statutory.command;

import java.time.Duration;
import java.time.Instant;

import com.lms.shared.error.BusinessRuleViolationException;
import com.lms.statutory.api.EwayBillAction;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EwayBillRulesTest {

    private static final Instant GENERATED = Instant.parse("2026-11-01T06:00:00Z");
    private static final Instant VALID_UNTIL = GENERATED.plus(Duration.ofDays(2));

    private static String codeOf(Runnable action) {
        return ((BusinessRuleViolationException) catchThrowable(action)).code();
    }

    private static Throwable catchThrowable(Runnable action) {
        try {
            action.run();
            return null;
        } catch (RuntimeException e) {
            return e;
        }
    }

    @Test
    @DisplayName("validity is the distance over the daily allowance, rounded up, and at least a day")
    void validityFollowsDistance() {
        assertThat(EwayBillRules.validityDays(1, 200)).isEqualTo(1);
        assertThat(EwayBillRules.validityDays(200, 200)).isEqualTo(1);
        assertThat(EwayBillRules.validityDays(201, 200)).isEqualTo(2);
        assertThat(EwayBillRules.validityDays(1_000, 200)).isEqualTo(5);
        assertThat(EwayBillRules.validUntil(GENERATED, 450, 200)).isEqualTo(GENERATED.plus(Duration.ofDays(3)));
        assertThatThrownBy(() -> EwayBillRules.validityDays(0, 200)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("a bill is usable while live and valid after the moment asked about")
    void usability() {
        assertThat(EwayBillRules.usableAt(EwayBillStatus.ACTIVE, VALID_UNTIL, GENERATED)).isTrue();
        assertThat(EwayBillRules.usableAt(EwayBillStatus.EXTENDED, VALID_UNTIL, GENERATED)).isTrue();
        assertThat(EwayBillRules.usableAt(EwayBillStatus.ACTIVE, VALID_UNTIL, VALID_UNTIL)).isFalse();
        assertThat(EwayBillRules.usableAt(EwayBillStatus.CANCELLED, VALID_UNTIL, GENERATED)).isFalse();
        assertThat(EwayBillRules.usableAt(EwayBillStatus.EXPIRED, VALID_UNTIL, GENERATED)).isFalse();
    }

    @Test
    @DisplayName("expiring soon means inside the threshold and not yet lapsed")
    void expiringSoon() {
        Instant now = VALID_UNTIL.minus(Duration.ofHours(7));
        assertThat(EwayBillRules.expiringSoon(VALID_UNTIL, now, 8)).isTrue();
        assertThat(EwayBillRules.expiringSoon(VALID_UNTIL, now, 7)).isTrue();
        assertThat(EwayBillRules.expiringSoon(VALID_UNTIL, now, 6)).isFalse();
        assertThat(EwayBillRules.expiringSoon(VALID_UNTIL, VALID_UNTIL, 8)).isFalse();
        assertThat(EwayBillRules.expiringSoon(VALID_UNTIL, VALID_UNTIL.plusSeconds(1), 8)).isFalse();
    }

    @Test
    @DisplayName("cancellation is allowed only inside the window from generation")
    void cancelWindow() {
        Instant insideWindow = GENERATED.plus(Duration.ofHours(24));
        EwayBillRules.requireAllowed(EwayBillAction.CANCEL, EwayBillStatus.ACTIVE, GENERATED, VALID_UNTIL, false,
                insideWindow, 24);
        Instant after = insideWindow.plusSeconds(1);
        assertThat(codeOf(() -> EwayBillRules.requireAllowed(EwayBillAction.CANCEL, EwayBillStatus.ACTIVE,
                GENERATED, VALID_UNTIL, false, after, 24))).isEqualTo("eway-cancel-window-passed");
        // The window is the unit's setting, not a constant.
        EwayBillRules.requireAllowed(EwayBillAction.CANCEL, EwayBillStatus.ACTIVE, GENERATED, VALID_UNTIL, false,
                after, 36);
    }

    @Test
    @DisplayName("only a live, unexpired bill can be changed, and GENERATE is not an action on a bill")
    void stateGuards() {
        assertThat(codeOf(() -> EwayBillRules.requireAllowed(EwayBillAction.EXTEND, EwayBillStatus.CANCELLED,
                GENERATED, VALID_UNTIL, true, GENERATED, 24))).isEqualTo("eway-bill-not-live");
        assertThat(codeOf(() -> EwayBillRules.requireAllowed(EwayBillAction.EXTEND, EwayBillStatus.EXPIRED,
                GENERATED, VALID_UNTIL, true, GENERATED, 24))).isEqualTo("eway-bill-not-live");
        assertThat(codeOf(() -> EwayBillRules.requireAllowed(EwayBillAction.EXTEND, EwayBillStatus.ACTIVE,
                GENERATED, VALID_UNTIL, true, VALID_UNTIL, 24))).isEqualTo("eway-bill-expired");
        assertThat(codeOf(() -> EwayBillRules.requireAllowed(EwayBillAction.GENERATE, EwayBillStatus.ACTIVE,
                GENERATED, VALID_UNTIL, true, GENERATED, 24))).isEqualTo("eway-action-not-applicable");
        assertThat(codeOf(() -> EwayBillRules.requireAllowed(EwayBillAction.FETCH, EwayBillStatus.ACTIVE,
                GENERATED, VALID_UNTIL, true, GENERATED, 24))).isEqualTo("eway-action-not-applicable");
    }

    @Test
    @DisplayName("changing the vehicle needs Part-B to name one first")
    void changeVehicleNeedsPartB() {
        assertThat(codeOf(() -> EwayBillRules.requireAllowed(EwayBillAction.CHANGE_VEHICLE, EwayBillStatus.ACTIVE,
                GENERATED, VALID_UNTIL, false, GENERATED, 24))).isEqualTo("eway-part-b-missing");
        EwayBillRules.requireAllowed(EwayBillAction.CHANGE_VEHICLE, EwayBillStatus.ACTIVE, GENERATED, VALID_UNTIL,
                true, GENERATED, 24);
        EwayBillRules.requireAllowed(EwayBillAction.PART_B, EwayBillStatus.EXTENDED, GENERATED, VALID_UNTIL, false,
                GENERATED, 24);
    }

    @Test
    @DisplayName("reasons, vehicles and credentials are validated with stable codes")
    void inputs() {
        assertThat(EwayBillRules.requireReason("  load shifted  ")).isEqualTo("load shifted");
        assertThat(codeOf(() -> EwayBillRules.requireReason(" "))).isEqualTo("eway-reason-required");
        assertThat(codeOf(() -> EwayBillRules.requireReason(null))).isEqualTo("eway-reason-required");
        assertThat(codeOf(() -> EwayBillRules.requireReason("x".repeat(501)))).isEqualTo("eway-reason-too-long");

        assertThat(EwayBillRules.requireVehicle("mh 12 ab 1234")).isEqualTo("MH12AB1234");
        assertThat(codeOf(() -> EwayBillRules.requireVehicle(""))).isEqualTo("eway-vehicle-required");
        assertThat(codeOf(() -> EwayBillRules.requireVehicle("a b!"))).isEqualTo("eway-vehicle-invalid");

        assertThat(EwayBillRules.requireReference(null)).isNull();
        assertThat(EwayBillRules.requireReference("secret-ref:eway/branch-1")).isEqualTo("secret-ref:eway/branch-1");
        assertThat(codeOf(() -> EwayBillRules.requireReference("plain-text-value")))
                .isEqualTo("eway-credential-not-a-reference");
    }

    @Test
    @DisplayName("an action name outside the list parses to nothing, in any case, and known ones parse")
    void actionNames() {
        assertThat(EwayBillAction.parse("extend")).contains(EwayBillAction.EXTEND);
        assertThat(EwayBillAction.parse(" Change_Vehicle ")).contains(EwayBillAction.CHANGE_VEHICLE);
        assertThat(EwayBillAction.parse("REVOKE")).isEmpty();
        assertThat(EwayBillAction.parse("")).isEmpty();
        assertThat(EwayBillAction.parse(null)).isEmpty();
    }
}
