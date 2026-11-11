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

import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.lms.approvals.api.ApprovalPolicy;
import com.lms.approvals.api.ApprovalPolicyResolver;
import com.lms.settings.api.SettingKey;
import com.lms.settings.api.Settings;
import com.lms.shared.audit.TransitionAudit;
import com.lms.shared.error.BusinessRuleViolationException;
import com.lms.shared.error.ResourceNotFoundException;
import com.lms.shared.tenant.TenantContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The organisation-wide policy of a subject type, through the endpoints that
 * predate profile settings ({@code /api/v1/approvals/policies}).
 *
 * <p>Reads the effective organisation default and writes or clears it as the
 * organisation default profile's {@code approvals.policy.*} value; a unit's,
 * customer's or combination's profile is managed through the settings API.
 * A change applies to requests raised afterwards; an open request keeps the
 * policy copied onto it.
 */
@Service
public class ApprovalPolicyService {

    static final String AUDIT_TYPE = "APPROVAL_POLICY";

    private final ApprovalPolicyResolver resolver;
    private final PolicySnapshot snapshots;
    private final Settings settings;
    private final JdbcClient jdbc;
    private final TransitionAudit audit;

    public ApprovalPolicyService(ApprovalPolicyResolver resolver, PolicySnapshot snapshots, Settings settings,
                                 JdbcClient jdbc, TransitionAudit audit) {
        this.resolver = resolver;
        this.snapshots = snapshots;
        this.settings = settings;
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

    /**
     * Sets the organisation default of one subject type, replacing what it
     * had: a property left null is the code default's. Profiles of a unit, a
     * customer or a combination are set through the settings API.
     */
    @Transactional
    @PreAuthorize("hasAuthority('APPROVAL_POLICY_MANAGE')")
    public ApprovalPolicy set(String subjectType, Change change) {
        ApprovalPolicy base = ApprovalPolicyDefaults.of(subjectType)
                .orElseThrow(() -> new ResourceNotFoundException("No approval subject type " + subjectType));
        if (base.mandatory() && Boolean.FALSE.equals(change.required())) {
            throw new BusinessRuleViolationException("approval-mandatory",
                    subjectType + " happens only through an approval; it cannot be switched off");
        }
        List<String> steps = steps(change.decidePermissions());
        if (change.dueMinutes() != null && (change.dueMinutes() <= 0 || change.dueMinutes() > 60 * 24 * 90)) {
            throw new BusinessRuleViolationException("approval-due-out-of-range",
                    "A due duration is 1 minute to 90 days");
        }
        ApprovalPolicy before = resolver.resolve(subjectType);
        ApprovalPolicy next = new ApprovalPolicy(subjectType,
                change.required() == null ? base.required() : change.required(), base.mandatory(),
                steps == null ? base.decidePermissions() : steps,
                change.ordered() == null ? base.ordered() : change.ordered(),
                change.allowSelfApproval() == null ? base.allowSelfApproval() : change.allowSelfApproval(),
                change.dueMinutes() == null ? base.due() : Duration.ofMinutes(change.dueMinutes()),
                change.onExpiry() == null ? base.onExpiry() : change.onExpiry());
        settings.setOrgDefaults(Map.<SettingKey<?>, Object>of(ApprovalSettingDefinitions.keyOf(subjectType),
                ApprovalPolicyValues.toValue(next)));
        ApprovalPolicy after = resolver.resolve(subjectType);
        audit.record("APPROVAL_POLICY_SET", AUDIT_TYPE, policyId(subjectType), snapshots.write(before),
                snapshots.write(after));
        return after;
    }

    /** Clears the organisation default: the code default applies again, unless the platform sets one. */
    @Transactional
    @PreAuthorize("hasAuthority('APPROVAL_POLICY_MANAGE')")
    public ApprovalPolicy reset(String subjectType) {
        if (ApprovalPolicyDefaults.of(subjectType).isEmpty()) {
            throw new ResourceNotFoundException("No approval subject type " + subjectType);
        }
        ApprovalPolicy before = resolver.resolve(subjectType);
        Map<SettingKey<?>, Object> clear = new HashMap<>();
        clear.put(ApprovalSettingDefinitions.keyOf(subjectType), null);
        settings.setOrgDefaults(clear);
        ApprovalPolicy after = resolver.resolve(subjectType);
        audit.record("APPROVAL_POLICY_RESET", AUDIT_TYPE, policyId(subjectType), snapshots.write(before),
                snapshots.write(after));
        return after;
    }

    /** Steps must be known permission codes, at most five, no blank. */
    private List<String> steps(List<String> permissions) {
        if (permissions == null) {
            return null;
        }
        if (permissions.isEmpty() || permissions.size() > ApprovalPolicyValues.MAX_STEPS) {
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
        return codes;
    }

    /** A stable audit resource id per tenant and subject type. */
    private static UUID policyId(String subjectType) {
        return UUID.nameUUIDFromBytes(("approval-policy:" + TenantContext.requireTenantId() + ":" + subjectType)
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}
