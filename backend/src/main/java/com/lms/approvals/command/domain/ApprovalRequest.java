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
package com.lms.approvals.command.domain;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.lms.approvals.api.ApprovalPolicy;
import com.lms.shared.config.Json;
import com.lms.shared.error.BusinessRuleViolationException;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.relational.core.mapping.Table;

/**
 * One request for approval, and the state machine that decides it.
 *
 * <p>The policy is the one copied onto the request when it was raised, never
 * the one in force now: a tenant that changes its policy does not change what
 * an open request needs. Permissions are checked by the caller, because they
 * belong to whoever is calling; this decides what the request's own state
 * allows.
 *
 * @param pendingPermissions what an approval may still be given under; see V53
 * @param approvalsGiven     approval steps filled so far
 */
@Table("approval_request")
public record ApprovalRequest(
        @Id UUID id,
        UUID tenantId,
        UUID orgUnitId,
        String subjectType,
        UUID subjectId,
        String action,
        Json payload,
        String summary,
        ApprovalStatus status,
        UUID requestedBy,
        Instant requestedAt,
        Instant dueAt,
        Json policy,
        String[] pendingPermissions,
        int approvalsGiven,
        UUID decidedBy,
        Instant decidedAt,
        String reason,
        @Version Long version,
        Instant createdAt) {

    public static final int MAX_TEXT = 500;

    /** Codes a caller can branch on. */
    public static final String NOT_OPEN = "approval-not-open";
    public static final String SELF_DECISION = "approval-self-decision";
    public static final String ALREADY_GIVEN = "approval-already-given";
    public static final String NOT_REQUESTER = "approval-not-requester";
    public static final String REASON_REQUIRED = "approval-reason-required";

    public static ApprovalRequest raise(UUID tenantId, UUID orgUnitId, String subjectType, UUID subjectId,
                                        String action, Json payload, String summary, UUID requestedBy,
                                        ApprovalPolicy policy, Json policySnapshot, Instant now) {
        String line = summary == null ? "" : summary.strip();
        if (line.isEmpty() || line.length() > MAX_TEXT) {
            throw new IllegalArgumentException("An approval summary is 1 to " + MAX_TEXT + " characters");
        }
        Instant at = now.truncatedTo(ChronoUnit.MICROS);
        return new ApprovalRequest(UUID.randomUUID(), tenantId, orgUnitId, subjectType, subjectId, action,
                payload, line, ApprovalStatus.REQUESTED, requestedBy, at,
                policy.due() == null ? null : at.plus(policy.due()), policySnapshot,
                pending(policy, Set.of(), 0), 0, null, null, null, null, at);
    }

    /** One approval step being given. */
    public record Step(ApprovalRequest after, int step, String permission, boolean closes) {
    }

    /**
     * Gives one approval step.
     *
     * @param approverPermissions what the approver holds
     * @param filledSteps         the steps already given, 1-based
     * @param priorApprovers      who gave them
     * @return empty when the approver holds no permission an open step needs:
     *         the caller answers that as a refusal of access, not of state
     */
    public Optional<Step> approve(UUID approver, Set<String> approverPermissions, ApprovalPolicy policy,
                                  Set<Integer> filledSteps, Set<UUID> priorApprovers, Instant now) {
        requireOpen();
        requireNotSelf(approver, policy);
        if (approver != null && priorApprovers.contains(approver)) {
            throw new BusinessRuleViolationException(ALREADY_GIVEN,
                    "You have already approved this request; another approver must give the next step");
        }
        Integer step = nextStep(approverPermissions, policy, filledSteps);
        if (step == null) {
            return Optional.empty();
        }
        Set<Integer> filled = new LinkedHashSet<>(filledSteps);
        filled.add(step);
        int given = approvalsGiven + 1;
        boolean closes = given >= policy.approvers();
        Instant at = now.truncatedTo(ChronoUnit.MICROS);
        ApprovalRequest after = closes
                ? closed(ApprovalStatus.APPROVED, approver, at, reason, given)
                : new ApprovalRequest(id, tenantId, orgUnitId, subjectType, subjectId, action, payload, summary,
                        status, requestedBy, requestedAt, dueAt, this.policy, pending(policy, filled, given),
                        given, decidedBy, decidedAt, reason, version, createdAt);
        return Optional.of(new Step(after, step, policy.decidePermissions().get(step - 1), closes));
    }

    /**
     * Rejects the request. Anyone who could give any of its steps may.
     *
     * @return empty when the rejecter holds no step's permission
     */
    public Optional<ApprovalRequest> reject(UUID rejecter, Set<String> rejecterPermissions, ApprovalPolicy policy,
                                            String why, Instant now) {
        requireOpen();
        String text = requireReason(why);
        requireNotSelf(rejecter, policy);
        if (policy.decidePermissions().stream().noneMatch(rejecterPermissions::contains)) {
            return Optional.empty();
        }
        return Optional.of(closed(ApprovalStatus.REJECTED, rejecter, now.truncatedTo(ChronoUnit.MICROS),
                text, approvalsGiven));
    }

    /** The requester withdraws the request. Nobody else may. */
    public ApprovalRequest cancel(UUID caller, String why, Instant now) {
        requireOpen();
        if (requestedBy == null || !requestedBy.equals(caller)) {
            throw new BusinessRuleViolationException(NOT_REQUESTER,
                    "Only the person who asked for this approval can cancel the request");
        }
        String note = optionalText(why);
        return closed(ApprovalStatus.CANCELLED, caller, now.truncatedTo(ChronoUnit.MICROS), note, approvalsGiven);
    }

    /** Whether the request is open and past due at {@code now}. */
    public boolean lapsed(Instant now) {
        return status.open() && dueAt != null && !dueAt.isAfter(now);
    }

    /** Closes a lapsed request without approval. */
    public ApprovalRequest expire(String note, Instant now) {
        requireLapsed(now);
        return closed(ApprovalStatus.EXPIRED, null, now.truncatedTo(ChronoUnit.MICROS), note, approvalsGiven);
    }

    /** Approves a lapsed request on its policy's behalf; no person decided it. */
    public ApprovalRequest autoApprove(ApprovalPolicy policy, Instant now) {
        requireLapsed(now);
        return closed(ApprovalStatus.APPROVED, null, now.truncatedTo(ChronoUnit.MICROS),
                "Approved by policy at expiry", policy.approvers());
    }

    private void requireLapsed(Instant now) {
        requireOpen();
        if (!lapsed(now)) {
            throw new BusinessRuleViolationException("approval-not-due", "This request is not yet due");
        }
    }

    private void requireOpen() {
        if (!status.open()) {
            throw new BusinessRuleViolationException(NOT_OPEN,
                    "This request is already " + status + " and can no longer be decided");
        }
    }

    private void requireNotSelf(UUID caller, ApprovalPolicy policy) {
        if (!policy.allowSelfApproval() && caller != null && caller.equals(requestedBy)) {
            throw new BusinessRuleViolationException(SELF_DECISION,
                    "You asked for this approval, so somebody else must decide it");
        }
    }

    private static String requireReason(String why) {
        String text = optionalText(why);
        if (text == null) {
            throw new BusinessRuleViolationException(REASON_REQUIRED, "A rejection must say why");
        }
        return text;
    }

    private static String optionalText(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String text = raw.strip();
        if (text.length() > MAX_TEXT) {
            throw new BusinessRuleViolationException("approval-text-too-long",
                    "A reason or note is at most " + MAX_TEXT + " characters");
        }
        return text;
    }

    /** The step this approver fills next, 1-based; null when they hold none that is open. */
    static Integer nextStep(Set<String> held, ApprovalPolicy policy, Set<Integer> filled) {
        List<String> steps = policy.decidePermissions();
        if (policy.ordered()) {
            for (int i = 1; i <= steps.size(); i++) {
                if (!filled.contains(i)) {
                    return held.contains(steps.get(i - 1)) ? i : null;
                }
            }
            return null;
        }
        for (int i = 1; i <= steps.size(); i++) {
            if (!filled.contains(i) && held.contains(steps.get(i - 1))) {
                return i;
            }
        }
        return null;
    }

    static String[] pending(ApprovalPolicy policy, Set<Integer> filled, int given) {
        List<String> steps = policy.decidePermissions();
        Set<String> open = new LinkedHashSet<>();
        for (int i = 1; i <= steps.size() && given < steps.size(); i++) {
            if (!filled.contains(i)) {
                open.add(steps.get(i - 1));
                if (policy.ordered()) {
                    break;
                }
            }
        }
        return new ArrayList<>(open).toArray(String[]::new);
    }

    private ApprovalRequest closed(ApprovalStatus to, UUID by, Instant at, String why, int given) {
        return new ApprovalRequest(id, tenantId, orgUnitId, subjectType, subjectId, action, payload, summary, to,
                requestedBy, requestedAt, dueAt, policy, new String[0], given, by, at, why, version, createdAt);
    }
}
