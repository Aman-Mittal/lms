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
package com.lms.approvals;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.lms.approvals.api.ApprovalPolicy;
import com.lms.approvals.api.ApprovalPolicy.Expiry;
import com.lms.approvals.command.ApprovalPolicyDefaults;
import com.lms.approvals.command.PolicySnapshot;
import com.lms.approvals.command.domain.ApprovalRequest;
import com.lms.approvals.command.domain.ApprovalStatus;
import com.lms.shared.config.Json;
import com.lms.shared.error.BusinessRuleViolationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The request's state machine and the policy rules, with no database. */
class ApprovalStateMachineTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID ASKER = UUID.randomUUID();
    private static final UUID ALICE = UUID.randomUUID();
    private static final UUID BOB = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-11-10T10:00:00Z");
    private static final PolicySnapshot SNAPSHOTS = new PolicySnapshot(JsonMapper.builder().build());

    private static ApprovalPolicy policy(List<String> steps, boolean ordered, boolean self, Expiry expiry) {
        return new ApprovalPolicy("ORDER_CANCELLATION", true, false, steps, ordered, self,
                Duration.ofHours(1), expiry);
    }

    private static ApprovalRequest raise(ApprovalPolicy policy) {
        return ApprovalRequest.raise(TENANT, null, policy.subjectType(), UUID.randomUUID(), "CANCEL",
                new Json("{}"), "Cancel order ORD-1", ASKER, policy, SNAPSHOTS.write(policy), NOW);
    }

    private static String code(Runnable call) {
        try {
            call.run();
        } catch (BusinessRuleViolationException e) {
            return e.code();
        }
        throw new AssertionError("expected a refusal");
    }

    @Test
    @DisplayName("one approver closes a one-step request; the requester may not, nor anyone without the right")
    void singleStep() {
        ApprovalPolicy policy = policy(List.of("ORDER_APPROVE"), true, false, Expiry.EXPIRE);
        ApprovalRequest request = raise(policy);
        assertThat(request.status()).isEqualTo(ApprovalStatus.REQUESTED);
        assertThat(request.pendingPermissions()).containsExactly("ORDER_APPROVE");
        assertThat(request.dueAt()).isEqualTo(NOW.plus(Duration.ofHours(1)));

        assertThat(code(() -> request.approve(ASKER, Set.of("ORDER_APPROVE"), policy, Set.of(), Set.of(), NOW)))
                .isEqualTo(ApprovalRequest.SELF_DECISION);
        assertThat(request.approve(ALICE, Set.of("ORDER_READ"), policy, Set.of(), Set.of(), NOW)).isEmpty();

        ApprovalRequest.Step step = request.approve(ALICE, Set.of("ORDER_APPROVE"), policy, Set.of(), Set.of(), NOW)
                .orElseThrow();
        assertThat(step.closes()).isTrue();
        assertThat(step.after().status()).isEqualTo(ApprovalStatus.APPROVED);
        assertThat(step.after().decidedBy()).isEqualTo(ALICE);
        assertThat(step.after().pendingPermissions()).isEmpty();
        assertThat(code(() -> step.after().approve(BOB, Set.of("ORDER_APPROVE"), policy, Set.of(1), Set.of(ALICE),
                NOW))).isEqualTo(ApprovalRequest.NOT_OPEN);
    }

    @Test
    @DisplayName("a policy may let the requester decide their own request")
    void selfApprovalAllowed() {
        ApprovalPolicy policy = policy(List.of("ORDER_APPROVE"), true, true, Expiry.EXPIRE);
        assertThat(raise(policy).approve(ASKER, Set.of("ORDER_APPROVE"), policy, Set.of(), Set.of(), NOW))
                .isPresent();
    }

    @Test
    @DisplayName("ordered steps are filled in turn by distinct approvers holding each step's right")
    void orderedSteps() {
        ApprovalPolicy policy = policy(List.of("ORDER_APPROVE", "INVOICE_APPROVE"), true, false, Expiry.EXPIRE);
        ApprovalRequest request = raise(policy);
        assertThat(request.pendingPermissions()).containsExactly("ORDER_APPROVE");
        assertThat(request.approve(ALICE, Set.of("INVOICE_APPROVE"), policy, Set.of(), Set.of(), NOW))
                .as("step two cannot be given first").isEmpty();

        ApprovalRequest.Step one = request.approve(ALICE, Set.of("ORDER_APPROVE", "INVOICE_APPROVE"), policy,
                Set.of(), Set.of(), NOW).orElseThrow();
        assertThat(one.step()).isEqualTo(1);
        assertThat(one.closes()).isFalse();
        assertThat(one.after().status()).isEqualTo(ApprovalStatus.REQUESTED);
        assertThat(one.after().pendingPermissions()).containsExactly("INVOICE_APPROVE");

        assertThat(code(() -> one.after().approve(ALICE, Set.of("INVOICE_APPROVE"), policy, Set.of(1),
                Set.of(ALICE), NOW))).isEqualTo(ApprovalRequest.ALREADY_GIVEN);
        ApprovalRequest.Step two = one.after().approve(BOB, Set.of("INVOICE_APPROVE"), policy, Set.of(1),
                Set.of(ALICE), NOW).orElseThrow();
        assertThat(two.step()).isEqualTo(2);
        assertThat(two.closes()).isTrue();
        assertThat(two.after().approvalsGiven()).isEqualTo(2);
    }

    @Test
    @DisplayName("unordered steps are filled by whichever right an approver holds")
    void unorderedSteps() {
        ApprovalPolicy policy = policy(List.of("ORDER_APPROVE", "INVOICE_APPROVE"), false, false, Expiry.EXPIRE);
        ApprovalRequest request = raise(policy);
        assertThat(request.pendingPermissions()).containsExactly("ORDER_APPROVE", "INVOICE_APPROVE");
        ApprovalRequest.Step first = request.approve(BOB, Set.of("INVOICE_APPROVE"), policy, Set.of(), Set.of(), NOW)
                .orElseThrow();
        assertThat(first.step()).isEqualTo(2);
        assertThat(first.after().pendingPermissions()).containsExactly("ORDER_APPROVE");
    }

    @Test
    @DisplayName("a rejection needs a reason and a deciding right; only the requester cancels")
    void rejectAndCancel() {
        ApprovalPolicy policy = policy(List.of("ORDER_APPROVE"), true, false, Expiry.EXPIRE);
        ApprovalRequest request = raise(policy);
        assertThat(code(() -> request.reject(ALICE, Set.of("ORDER_APPROVE"), policy, " ", NOW)))
                .isEqualTo(ApprovalRequest.REASON_REQUIRED);
        assertThat(request.reject(ALICE, Set.of(), policy, "No", NOW)).isEmpty();
        assertThat(code(() -> request.reject(ASKER, Set.of("ORDER_APPROVE"), policy, "No", NOW)))
                .isEqualTo(ApprovalRequest.SELF_DECISION);
        ApprovalRequest rejected = request.reject(ALICE, Set.of("ORDER_APPROVE"), policy, " Not now ", NOW)
                .orElseThrow();
        assertThat(rejected.status()).isEqualTo(ApprovalStatus.REJECTED);
        assertThat(rejected.reason()).isEqualTo("Not now");

        assertThat(code(() -> request.cancel(ALICE, null, NOW))).isEqualTo(ApprovalRequest.NOT_REQUESTER);
        assertThat(request.cancel(ASKER, null, NOW).status()).isEqualTo(ApprovalStatus.CANCELLED);
    }

    @Test
    @DisplayName("only a lapsed request expires, and auto-approval fills every step")
    void expiry() {
        ApprovalPolicy policy = policy(List.of("ORDER_APPROVE", "ORDER_APPROVE"), true, false, Expiry.AUTO_APPROVE);
        ApprovalRequest request = raise(policy);
        assertThat(request.lapsed(NOW)).isFalse();
        assertThat(code(() -> request.expire("x", NOW))).isEqualTo("approval-not-due");
        Instant later = NOW.plus(Duration.ofHours(2));
        assertThat(request.lapsed(later)).isTrue();
        assertThat(request.expire("late", later).status()).isEqualTo(ApprovalStatus.EXPIRED);
        ApprovalRequest auto = request.autoApprove(policy, later);
        assertThat(auto.status()).isEqualTo(ApprovalStatus.APPROVED);
        assertThat(auto.approvalsGiven()).isEqualTo(2);
        assertThat(auto.decidedBy()).isNull();
    }

    @Test
    @DisplayName("a policy survives its snapshot, and the defaults register every flow")
    void policies() {
        ApprovalPolicy policy = policy(List.of("ORDER_APPROVE", "INVOICE_APPROVE"), false, true, Expiry.AUTO_APPROVE);
        assertThat(SNAPSHOTS.read(SNAPSHOTS.write(policy))).isEqualTo(policy);

        ApprovalPolicy reversal = ApprovalPolicyDefaults.of("POD_REVERSAL").orElseThrow();
        assertThat(reversal.mandatory()).isTrue();
        assertThat(reversal.required()).isTrue();
        ApprovalPolicy cancellation = ApprovalPolicyDefaults.of("ORDER_CANCELLATION").orElseThrow();
        assertThat(cancellation.required()).as("off by default: existing behaviour unchanged").isFalse();
        assertThat(new ApprovalPolicy("X", false, true, List.of("P"), true, false, null, null).required())
                .as("mandatory implies required").isTrue();
        assertThatThrownBy(() -> new ApprovalPolicy("X", true, false, List.of(), true, false, null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
