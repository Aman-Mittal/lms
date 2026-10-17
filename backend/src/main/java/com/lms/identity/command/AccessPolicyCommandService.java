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
package com.lms.identity.command;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

import com.lms.identity.abac.Condition;
import com.lms.identity.access.SubjectAttributes;
import com.lms.identity.command.domain.AccessPolicy;
import com.lms.shared.audit.AuditTrail;
import com.lms.shared.config.Json;
import com.lms.shared.error.BusinessRuleViolationException;
import com.lms.shared.error.ResourceNotFoundException;
import com.lms.shared.tenant.TenantContext;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Administers access policies and the user attributes they read.
 *
 * <p>One permission, {@code ACCESS_POLICY_MANAGE}, for both. Whoever can set a
 * user's approval limit already decides what that user may approve, so
 * guarding the policy and the attribute separately would protect nothing.
 *
 * <p>Every change is audited with what it replaced. A policy is a security
 * control: "who loosened this, and when" has to have an answer.
 */
@Service
public class AccessPolicyCommandService {

    private static final Pattern SAFE_REGION = Pattern.compile("^[A-Z0-9_-]{1,32}$");

    private final AccessPolicyRepository policies;
    private final JdbcClient jdbc;
    private final JsonMapper json;
    private final AuditTrail audit;

    public AccessPolicyCommandService(AccessPolicyRepository policies, JdbcClient jdbc,
                                      JsonMapper json, AuditTrail audit) {
        this.policies = policies;
        this.jdbc = jdbc;
        this.json = json;
        this.audit = audit;
    }

    @Transactional
    @PreAuthorize("hasAuthority('ACCESS_POLICY_MANAGE')")
    public UUID create(String code, String action, AccessPolicy.Effect effect, String description,
                       Map<String, Object> condition, boolean enabled) {
        UUID tenantId = TenantContext.requireTenantId();
        Json validated = validated(condition);
        if (!policies.isPermissionCode(action)) {
            throw new IllegalArgumentException("'" + action + "' is not a permission code; "
                    + "a policy on it would never be consulted");
        }
        policies.findByCode(tenantId, code).ifPresent(existing -> {
            throw new BusinessRuleViolationException("access-policy-code-taken",
                    "An access policy with code " + code + " already exists");
        });

        AccessPolicy created = policies.save(AccessPolicy.create(UUID.randomUUID(), tenantId,
                code, action, effect, description, validated, enabled));
        audit.record("ACCESS_POLICY_CREATED", "ACCESS_POLICY", created.id(), null,
                snapshot(created));
        return created.id();
    }

    @Transactional
    @PreAuthorize("hasAuthority('ACCESS_POLICY_MANAGE')")
    public void revise(UUID policyId, AccessPolicy.Effect effect, String description,
                       Map<String, Object> condition, boolean enabled) {
        AccessPolicy existing = require(policyId);
        AccessPolicy revised = policies.save(
                existing.revise(effect, description, validated(condition), enabled));
        audit.record("ACCESS_POLICY_REVISED", "ACCESS_POLICY", revised.id(),
                snapshot(existing), snapshot(revised));
    }

    @Transactional
    @PreAuthorize("hasAuthority('ACCESS_POLICY_MANAGE')")
    public void delete(UUID policyId) {
        AccessPolicy existing = require(policyId);
        policies.delete(existing);
        audit.record("ACCESS_POLICY_DELETED", "ACCESS_POLICY", existing.id(),
                snapshot(existing), null);
    }

    /**
     * Sets the attributes a user's next token will carry.
     *
     * <p>Takes effect at the user's next sign-in or refresh, not on the token
     * they hold now. That is the price of deciding from the token rather than
     * reading the user on every request, and it is bounded by the access-token
     * lifetime.
     */
    @Transactional
    @PreAuthorize("hasAuthority('ACCESS_POLICY_MANAGE')")
    public void setUserAttributes(UUID userId, SubjectAttributes attributes) {
        if (attributes.region() != null && !SAFE_REGION.matcher(attributes.region()).matches()) {
            throw new IllegalArgumentException(
                    "Region must be 1-32 upper-case letters, digits, '-' or '_': " + attributes.region());
        }
        if (attributes.freightModes() != null) {
            for (String mode : attributes.freightModes()) {
                if (!SubjectAttributes.FREIGHT_MODES.contains(mode)) {
                    throw new IllegalArgumentException("Unknown freight mode '" + mode
                            + "'; expected " + SubjectAttributes.FREIGHT_MODES.stream().sorted().toList());
                }
            }
        }
        if (attributes.approvalLimit() != null
                && attributes.approvalLimit().compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("An approval limit cannot be negative");
        }

        int updated;
        try {
            updated = jdbc.sql("""
                            UPDATE app_user
                               SET region = :region,
                                   freight_modes = CAST(:modes AS text[]),
                                   partner_id = :partnerId,
                                   approval_limit = :approvalLimit,
                                   updated_at = now()
                             WHERE id = :id
                            """)
                    .param("region", attributes.region())
                    .param("modes", attributes.freightModes() == null ? null
                            : "{" + String.join(",", attributes.freightModes()) + "}")
                    .param("partnerId", attributes.partnerId())
                    .param("approvalLimit", attributes.approvalLimit())
                    .param("id", userId)
                    .update();
        } catch (DataIntegrityViolationException e) {
            // The partner reference is the only constraint the checks above do
            // not already cover.
            throw new IllegalArgumentException("No partner " + attributes.partnerId()
                    + " exists in this tenant");
        }
        if (updated == 0) {
            throw new ResourceNotFoundException("AppUser", userId);
        }

        audit.record("USER_ACCESS_ATTRIBUTES_SET", "APP_USER", userId, null,
                Json.object("region", attributes.region(),
                        "freightModes", attributes.freightModes() == null ? null
                                : String.join(",", attributes.freightModes()),
                        "partnerId", attributes.partnerId() == null ? null
                                : attributes.partnerId().toString(),
                        "approvalLimit", attributes.approvalLimit() == null ? null
                                : attributes.approvalLimit().toPlainString()));
    }

    // ------------------------------------------------------------- internals

    private Json validated(Map<String, Object> condition) {
        // Parsed here, at write time, so a typo is a 400 to the administrator
        // rather than a policy that quietly never matches.
        Condition.parse(condition);
        return new Json(json.writeValueAsString(condition));
    }

    private AccessPolicy require(UUID policyId) {
        return policies.findById(policyId)
                .orElseThrow(() -> new ResourceNotFoundException("AccessPolicy", policyId));
    }

    private static Json snapshot(AccessPolicy policy) {
        return Json.object("code", policy.code(), "action", policy.action(),
                "effect", policy.effect().name(), "enabled", String.valueOf(policy.enabled()),
                "condition", policy.condition().value());
    }
}
