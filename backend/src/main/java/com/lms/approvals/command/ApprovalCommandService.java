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
package com.lms.approvals.command;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.lms.approvals.api.ApprovalOutcome;
import com.lms.approvals.api.ApprovalPolicy;
import com.lms.approvals.api.ApprovalPolicyResolver;
import com.lms.approvals.api.ApprovalRequests;
import com.lms.approvals.api.ApprovalSubject;
import com.lms.approvals.api.ApprovalTicket;
import com.lms.approvals.command.domain.ApprovalRequest;
import com.lms.approvals.command.domain.ApprovalStatus;
import com.lms.approvals.events.ApprovalDecided;
import com.lms.approvals.events.ApprovalRequested;
import com.lms.identity.access.AccessSubject;
import com.lms.shared.audit.TransitionAudit;
import com.lms.shared.config.Json;
import com.lms.shared.error.BusinessRuleViolationException;
import com.lms.shared.error.ResourceNotFoundException;
import com.lms.settings.api.SettingContext;
import com.lms.shared.tenant.TenantContext;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Raising and deciding approval requests.
 *
 * <p>Every decision is one transaction: the request's new state, the decision
 * trail's row, the audit entry and -- when the request closes approved -- the
 * owning module's change, made by its handler. A handler that refuses rolls
 * all of it back and the request stays open (see
 * {@link com.lms.approvals.api.ApprovalOutcomeHandler}).
 *
 * <p>Who may decide comes from the policy copied onto the request, not from a
 * {@code @PreAuthorize} expression: the permission differs per subject type
 * and per tenant. The decision point is still consulted ({@code @abac}) so a
 * tenant can confine deciding to an organisational subtree.
 */
@Service
public class ApprovalCommandService implements ApprovalRequests {

    static final String AUDIT_TYPE = "APPROVAL";

    private final ApprovalRequestRepository requests;
    private final ApprovalPolicyResolver policies;
    private final PolicySnapshot snapshots;
    private final ApprovalHandlers handlers;
    private final JdbcClient jdbc;
    private final TransitionAudit audit;
    private final ApplicationEventPublisher events;

    public ApprovalCommandService(ApprovalRequestRepository requests, ApprovalPolicyResolver policies,
                                  PolicySnapshot snapshots, ApprovalHandlers handlers, JdbcClient jdbc,
                                  TransitionAudit audit, ApplicationEventPublisher events) {
        this.requests = requests;
        this.policies = policies;
        this.snapshots = snapshots;
        this.handlers = handlers;
        this.jdbc = jdbc;
        this.audit = audit;
        this.events = events;
    }

    // ------------------------------------------------------------- raising

    @Override
    @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
    public boolean isRequired(String subjectType, SettingContext context) {
        return policies.resolve(subjectType, context).required();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public ApprovalTicket request(ApprovalSubject subject, SettingContext context, String action,
                                  Map<String, ?> payload, String summary) {
        UUID tenantId = TenantContext.requireTenantId();
        if (requests.findOpen(tenantId, subject.type(), subject.id(), action).isPresent()) {
            throw new BusinessRuleViolationException("approval-already-open",
                    "An approval request for this is already open; decide or cancel it first");
        }
        ApprovalPolicy policy = policies.resolve(subject.type(), context);
        UUID requester = actorId();
        ApprovalRequest saved = requests.save(ApprovalRequest.raise(tenantId, subject.orgUnitId(), subject.type(),
                subject.id(), action, snapshots.payload(payload), summary, requester, policy,
                snapshots.write(policy), Instant.now()));
        audit.record("APPROVAL_REQUESTED", AUDIT_TYPE, saved.id(), null, snapshot(saved));
        events.publishEvent(new ApprovalRequested(tenantId, saved.id(), saved.subjectType(), saved.subjectId(),
                saved.action(), saved.summary(), requester, saved.dueAt(), saved.requestedAt()));
        return ticket(saved);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
    public Optional<ApprovalTicket> openRequest(String subjectType, UUID subjectId, String action) {
        return requests.findOpen(TenantContext.requireTenantId(), subjectType, subjectId, action)
                .map(ApprovalCommandService::ticket);
    }

    // ------------------------------------------------------------ deciding

    /**
     * Gives one approval step; the last one closes the request APPROVED and
     * applies it through the owning module's handler.
     *
     * @return the request's status afterwards
     */
    @Transactional
    @PreAuthorize("isAuthenticated() and @abac.permits('APPROVAL_DECIDE', 'APPROVAL', #id)")
    public ApprovalStatus approve(UUID id, long expectedVersion, String note) {
        ApprovalRequest request = require(id, expectedVersion);
        ApprovalPolicy policy = snapshots.read(request.policy());
        UUID approver = actorId();
        Trail trail = trailOf(request);
        ApprovalRequest.Step step = request.approve(approver, permissions(), policy, trail.filledSteps(),
                        trail.approvers(), Instant.now())
                .orElseThrow(() -> new AccessDeniedException("No open step of this request is the caller's"));
        String text = note == null || note.isBlank() ? null : note.strip();
        if (text != null && text.length() > ApprovalRequest.MAX_TEXT) {
            throw new BusinessRuleViolationException("approval-text-too-long",
                    "A note is at most " + ApprovalRequest.MAX_TEXT + " characters");
        }

        ApprovalRequest saved = requests.save(step.closes() && text != null
                ? withReason(step.after(), text) : step.after());
        decide(saved, step.step(), "APPROVED", step.permission(), approver, text);
        audit.record(step.closes() ? "APPROVAL_APPROVED" : "APPROVAL_STEP_GIVEN", AUDIT_TYPE, id,
                snapshot(request), snapshot(saved));
        if (step.closes()) {
            handlers.of(saved.subjectType()).onApproved(outcome(saved, approver, text));
            announce(saved);
        }
        return saved.status();
    }

    /** Rejects the request, with a reason. Nothing is applied. */
    @Transactional
    @PreAuthorize("isAuthenticated() and @abac.permits('APPROVAL_DECIDE', 'APPROVAL', #id)")
    public void reject(UUID id, long expectedVersion, String reason) {
        ApprovalRequest request = require(id, expectedVersion);
        ApprovalPolicy policy = snapshots.read(request.policy());
        UUID rejecter = actorId();
        ApprovalRequest rejected = request.reject(rejecter, permissions(), policy, reason, Instant.now())
                .orElseThrow(() -> new AccessDeniedException("The caller may decide no step of this request"));
        ApprovalRequest saved = requests.save(rejected);
        decide(saved, null, "REJECTED", null, rejecter, saved.reason());
        audit.record("APPROVAL_REJECTED", AUDIT_TYPE, id, snapshot(request), snapshot(saved));
        handlers.of(saved.subjectType()).onClosedWithoutApproval(outcome(saved, rejecter, saved.reason()),
                saved.status().name());
        announce(saved);
    }

    /** The requester withdraws the request. */
    @Transactional
    @PreAuthorize("isAuthenticated()")
    public void cancel(UUID id, long expectedVersion, String reason) {
        ApprovalRequest request = require(id, expectedVersion);
        UUID caller = actorId();
        ApprovalRequest saved = requests.save(request.cancel(caller, reason, Instant.now()));
        decide(saved, null, "CANCELLED", null, caller, saved.reason());
        audit.record("APPROVAL_CANCELLED", AUDIT_TYPE, id, snapshot(request), snapshot(saved));
        handlers.of(saved.subjectType()).onClosedWithoutApproval(outcome(saved, caller, saved.reason()),
                saved.status().name());
        announce(saved);
    }

    /**
     * Settles one lapsed request as its policy says: expired, or approved by
     * the platform. Unguarded: the caller is the expiry sweep, in a tenant
     * scope it bound. A request that is no longer open or not yet due is left
     * alone, so a second sweep over the same request changes nothing.
     *
     * @return whether this call closed the request
     */
    @Transactional
    public boolean settleLapsed(UUID id, Instant now) {
        ApprovalRequest request = requests.findById(id).orElse(null);
        if (request == null || !request.lapsed(now)) {
            return false;
        }
        ApprovalPolicy policy = snapshots.read(request.policy());
        if (policy.onExpiry() == ApprovalPolicy.Expiry.AUTO_APPROVE) {
            ApprovalRequest saved = requests.save(request.autoApprove(policy, now));
            decide(saved, null, "AUTO_APPROVED", null, null, saved.reason());
            audit.record("APPROVAL_AUTO_APPROVED", AUDIT_TYPE, id, snapshot(request), snapshot(saved));
            handlers.of(saved.subjectType()).onApproved(outcome(saved, null, saved.reason()));
            announce(saved);
        } else {
            expire(request, "Expired: not decided by " + request.dueAt(), now);
        }
        return true;
    }

    /**
     * Expires a lapsed request whose automatic approval its handler refused,
     * saying why. Called by the sweep after that refusal rolled back.
     */
    @Transactional
    public boolean expireRefused(UUID id, String code, Instant now) {
        ApprovalRequest request = requests.findById(id).orElse(null);
        if (request == null || !request.lapsed(now)) {
            return false;
        }
        expire(request, "Expired: approval at expiry was refused (" + code + ")", now);
        return true;
    }

    private void expire(ApprovalRequest request, String note, Instant now) {
        ApprovalRequest saved = requests.save(request.expire(note, now));
        decide(saved, null, "EXPIRED", null, null, note);
        audit.record("APPROVAL_EXPIRED", AUDIT_TYPE, saved.id(), snapshot(request), snapshot(saved));
        handlers.of(saved.subjectType()).onClosedWithoutApproval(outcome(saved, null, note), saved.status().name());
        announce(saved);
    }

    // --------------------------------------------------------------- helpers

    private ApprovalRequest require(UUID id, long expectedVersion) {
        ApprovalRequest request = requests.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Approval request", id));
        if (request.version() == null || request.version() != expectedVersion) {
            throw new OptimisticLockingFailureException(
                    "Approval request " + id + " has changed since version " + expectedVersion);
        }
        return request;
    }

    private record Trail(Set<Integer> filledSteps, Set<UUID> approvers) {
    }

    private Trail trailOf(ApprovalRequest request) {
        Set<Integer> steps = new HashSet<>();
        Set<UUID> approvers = new HashSet<>();
        jdbc.sql("""
                        SELECT step, decided_by FROM approval_decision
                         WHERE tenant_id = :tenantId AND request_id = :id AND decision = 'APPROVED'
                        """)
                .param("tenantId", request.tenantId())
                .param("id", request.id())
                .query((rs, row) -> {
                    steps.add(rs.getInt("step"));
                    UUID by = rs.getObject("decided_by", UUID.class);
                    if (by != null) {
                        approvers.add(by);
                    }
                    return null;
                })
                .list();
        return new Trail(steps, approvers);
    }

    private void decide(ApprovalRequest request, Integer step, String decision, String permission, UUID by,
                        String note) {
        jdbc.sql("""
                        INSERT INTO approval_decision (id, tenant_id, request_id, step, decision, permission,
                                                       decided_by, decided_at, note)
                        VALUES (:id, :tenantId, :requestId, :step, :decision, :permission, :by, :at, :note)
                        """)
                .param("id", UUID.randomUUID())
                .param("tenantId", request.tenantId())
                .param("requestId", request.id())
                .param("step", step)
                .param("decision", decision)
                .param("permission", permission)
                .param("by", by)
                .param("at", Instant.now().truncatedTo(ChronoUnit.MICROS).atOffset(ZoneOffset.UTC))
                .param("note", note)
                .update();
    }

    private void announce(ApprovalRequest closed) {
        events.publishEvent(new ApprovalDecided(closed.tenantId(), closed.id(), closed.subjectType(),
                closed.subjectId(), closed.action(), closed.status().name(), closed.decidedBy(), closed.reason(),
                closed.decidedAt()));
    }

    private ApprovalOutcome outcome(ApprovalRequest request, UUID decidedBy, String reason) {
        return new ApprovalOutcome(request.id(), request.subjectType(), request.subjectId(), request.action(),
                snapshots.payload(request.payload()), request.summary(), request.requestedBy(), decidedBy, reason);
    }

    private static ApprovalRequest withReason(ApprovalRequest r, String reason) {
        return new ApprovalRequest(r.id(), r.tenantId(), r.orgUnitId(), r.subjectType(), r.subjectId(), r.action(),
                r.payload(), r.summary(), r.status(), r.requestedBy(), r.requestedAt(), r.dueAt(), r.policy(),
                r.pendingPermissions(), r.approvalsGiven(), r.decidedBy(), r.decidedAt(), reason, r.version(),
                r.createdAt());
    }

    static ApprovalTicket ticket(ApprovalRequest r) {
        return new ApprovalTicket(r.id(), r.subjectType(), r.subjectId(), r.action(), r.status().name(),
                r.requestedAt(), r.dueAt(), r.version() == null ? 0 : r.version());
    }

    private static Json snapshot(ApprovalRequest r) {
        return Json.object("subjectType", r.subjectType(), "subjectId", r.subjectId().toString(),
                "action", r.action(), "status", r.status().name(),
                "approvalsGiven", String.valueOf(r.approvalsGiven()),
                "reason", r.reason());
    }

    private static Set<String> permissions() {
        return AccessSubject.current().permissions();
    }

    /** The calling user, or null for a caller that is not one (a sweep, a test principal). */
    static UUID actorId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return null;
        }
        try {
            return UUID.fromString(authentication.getName());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
