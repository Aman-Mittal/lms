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

import java.time.ZoneOffset;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;

import com.lms.approvals.api.ApprovalPolicy;
import com.lms.approvals.api.ApprovalPolicyResolver;
import com.lms.shared.audit.TransitionAudit;
import com.lms.shared.error.BusinessRuleViolationException;
import com.lms.shared.error.ResourceNotFoundException;
import com.lms.shared.tenant.TenantContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A tenant's own values for a subject type's policy.
 *
 * <p>A stand-in for profile settings: the values land in
 * {@code approval_policy_override}, which {@link DefaultApprovalPolicyResolver}
 * lays over the code default. A change applies to requests raised afterwards;
 * an open request keeps the policy copied onto it.
 */
@Service
public class ApprovalPolicyService {

    static final String AUDIT_TYPE = "APPROVAL_POLICY";

    private final ApprovalPolicyResolver resolver;
    private final PolicySnapshot snapshots;
    private final JdbcClient jdbc;
    private final TransitionAudit audit;

    public ApprovalPolicyService(ApprovalPolicyResolver resolver, PolicySnapshot snapshots, JdbcClient jdbc,
                                 TransitionAudit audit) {
        this.resolver = resolver;
        this.snapshots = snapshots;
        this.jdbc = jdbc;
        this.audit = audit;
    }

    /**
     * The tenant's values. Each is optional; null keeps the default.
     *
     * @param dueMinutes how long a request may stay open
     * @param onExpiry   EXPIRE or AUTO_APPROVE
     */
    public record Change(Boolean required, List<String> decidePermissions, Boolean ordered,
                         Boolean allowSelfApproval, Integer dueMinutes, ApprovalPolicy.Expiry onExpiry) {
    }

    @Transactional(readOnly = true)
    @PreAuthorize("hasAuthority('APPROVAL_READ')")
    public List<ApprovalPolicy> effective() {
        return ApprovalPolicyDefaults.subjectTypes().stream().map(resolver::resolve).toList();
    }

    /** Sets the tenant's values for one subject type, replacing any it had. */
    @Transactional
    @PreAuthorize("hasAuthority('APPROVAL_POLICY_MANAGE')")
    public ApprovalPolicy set(String subjectType, Change change) {
        ApprovalPolicy base = ApprovalPolicyDefaults.of(subjectType)
                .orElseThrow(() -> new ResourceNotFoundException("No approval subject type " + subjectType));
        if (base.mandatory() && Boolean.FALSE.equals(change.required())) {
            throw new BusinessRuleViolationException("approval-mandatory",
                    subjectType + " happens only through an approval; it cannot be switched off");
        }
        String[] steps = steps(change.decidePermissions());
        if (change.dueMinutes() != null && (change.dueMinutes() <= 0 || change.dueMinutes() > 60 * 24 * 90)) {
            throw new BusinessRuleViolationException("approval-due-out-of-range",
                    "A due duration is 1 minute to 90 days");
        }
        ApprovalPolicy before = resolver.resolve(subjectType);
        jdbc.sql("""
                        INSERT INTO approval_policy_override (tenant_id, subject_type, required, decide_permissions,
                                                              ordered, allow_self_approval, due_minutes, on_expiry,
                                                              updated_at, updated_by)
                        VALUES (:tenantId, :subjectType, :required, :steps, :ordered, :self, :due, :expiry,
                                :at, :by)
                        ON CONFLICT (tenant_id, subject_type) DO UPDATE
                           SET required = EXCLUDED.required, decide_permissions = EXCLUDED.decide_permissions,
                               ordered = EXCLUDED.ordered, allow_self_approval = EXCLUDED.allow_self_approval,
                               due_minutes = EXCLUDED.due_minutes, on_expiry = EXCLUDED.on_expiry,
                               updated_at = EXCLUDED.updated_at, updated_by = EXCLUDED.updated_by
                        """)
                .param("tenantId", TenantContext.requireTenantId())
                .param("subjectType", subjectType)
                .param("required", change.required())
                .param("steps", steps)
                .param("ordered", change.ordered())
                .param("self", change.allowSelfApproval())
                .param("due", change.dueMinutes())
                .param("expiry", change.onExpiry() == null ? null : change.onExpiry().name())
                .param("at", Instant.now().atOffset(ZoneOffset.UTC))
                .param("by", ApprovalCommandService.actorId())
                .update();
        ApprovalPolicy after = resolver.resolve(subjectType);
        audit.record("APPROVAL_POLICY_SET", AUDIT_TYPE, policyId(subjectType), snapshots.write(before),
                snapshots.write(after));
        return after;
    }

    /** Drops the tenant's values: the code default applies again. */
    @Transactional
    @PreAuthorize("hasAuthority('APPROVAL_POLICY_MANAGE')")
    public ApprovalPolicy reset(String subjectType) {
        if (ApprovalPolicyDefaults.of(subjectType).isEmpty()) {
            throw new ResourceNotFoundException("No approval subject type " + subjectType);
        }
        ApprovalPolicy before = resolver.resolve(subjectType);
        jdbc.sql("DELETE FROM approval_policy_override WHERE tenant_id = :tenantId AND subject_type = :subjectType")
                .param("tenantId", TenantContext.requireTenantId())
                .param("subjectType", subjectType)
                .update();
        ApprovalPolicy after = resolver.resolve(subjectType);
        audit.record("APPROVAL_POLICY_RESET", AUDIT_TYPE, policyId(subjectType), snapshots.write(before),
                snapshots.write(after));
        return after;
    }

    /** Steps must be known permission codes, at most five, no blank. */
    private String[] steps(List<String> permissions) {
        if (permissions == null) {
            return null;
        }
        if (permissions.isEmpty() || permissions.size() > 5) {
            throw new BusinessRuleViolationException("approval-steps-invalid",
                    "A policy has one to five approval steps");
        }
        List<String> codes = permissions.stream().map(p -> p == null ? "" : p.strip()).toList();
        List<String> known = jdbc.sql("SELECT code FROM permission WHERE code IN (:codes)")
                .param("codes", new LinkedHashSet<>(codes))
                .query(String.class)
                .list();
        for (String code : codes) {
            if (!known.contains(code)) {
                throw new BusinessRuleViolationException("approval-steps-invalid",
                        "Each approval step names a permission; '" + code + "' is not one");
            }
        }
        return codes.toArray(String[]::new);
    }

    /** A stable audit resource id per tenant and subject type. */
    private static UUID policyId(String subjectType) {
        return UUID.nameUUIDFromBytes(("approval-policy:" + TenantContext.requireTenantId() + ":" + subjectType)
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}
