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
package com.lms.identity.abac;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;

import com.lms.identity.access.AccessSubject;
import com.lms.identity.access.ResourceAttributeProvider;
import com.lms.shared.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aot.hint.annotation.Reflective;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The policy decision point, reachable from SpEL as {@code @abac}.
 *
 * <p>Layered on RBAC, never instead of it. A guarded service method states
 * both, permission first:
 *
 * <pre>
 * &#64;PreAuthorize("hasAuthority('INVOICE_APPROVE')
 *               and &#64;abac.permits('INVOICE_APPROVE', 'FREIGHT_BILL', #billId)")
 * </pre>
 *
 * <p>SpEL's {@code and} short-circuits, so a caller without the permission is
 * refused without a policy ever being loaded, and a policy can only narrow
 * what the permission already allowed.
 *
 * <p><b>Combining.</b> Deny overrides. Any enabled DENY policy whose condition
 * holds refuses. If the action has PERMIT policies, at least one of them must
 * hold as well; if it has none, nothing further is required. An action with
 * no policies at all is decided by RBAC alone -- and costs one indexed lookup,
 * with no resource read, because the resource is only fetched when there is a
 * policy to show it to.
 *
 * <p><b>Fail closed.</b> With no tenant bound there are no policies to read,
 * and "no policies" would otherwise mean "permit"; so it refuses instead.
 */
@Component("abac")
public class PolicyDecisionPoint {

    private static final Logger log = LoggerFactory.getLogger(PolicyDecisionPoint.class);

    private final PolicyStore policies;
    private final Map<String, ResourceAttributeProvider> providers = new HashMap<>();
    private final JdbcClient jdbc;
    private final TransactionTemplate readOnly;

    PolicyDecisionPoint(PolicyStore policies, List<ResourceAttributeProvider> providers,
                        JdbcClient jdbc, PlatformTransactionManager transactionManager) {
        this.policies = policies;
        for (ResourceAttributeProvider provider : providers) {
            ResourceAttributeProvider clash = this.providers.put(provider.resourceType(), provider);
            if (clash != null) {
                throw new IllegalStateException("Two attribute providers claim resource type "
                        + provider.resourceType());
            }
        }
        this.jdbc = jdbc;
        // Joins the guarded method's transaction when there is one; opens a
        // read-only one when, as usual, the check runs before it starts. Either
        // way the tenant is on the connection before anything is read.
        this.readOnly = new TransactionTemplate(transactionManager);
        this.readOnly.setReadOnly(true);
        this.readOnly.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
    }

    /** The outcome, and which policy decided it when one did. */
    public record Decision(boolean permitted, String policy, String reason) {

        static Decision permit() {
            return new Decision(true, null, null);
        }

        static Decision deny(String policy, String reason) {
            return new Decision(false, policy, reason);
        }
    }

    /** Whether the caller may perform {@code action} on one existing record. */
    @Reflective
    public boolean permits(String action, String resourceType, Object resourceId) {
        return permits(action, resourceType, resourceId, Map.of());
    }

    /**
     * As {@link #permits(String, String, Object)}, with attributes the record
     * does not have yet -- the amount a vendor is claiming, say, which is what
     * is being approved before it is stored.
     */
    @Reflective
    public boolean permits(String action, String resourceType, Object resourceId,
                           Map<String, ?> overrides) {
        return decide(action, () -> {
            Map<String, Object> resource = new LinkedHashMap<>(lookup(resourceType, resourceId));
            if (overrides != null) {
                resource.putAll(overrides);
            }
            return resource;
        }).permitted();
    }

    /**
     * Whether the caller may perform {@code action} on something that will
     * belong to {@code orgUnitId} -- a record being created, which has no
     * attributes yet but where it will live.
     */
    @Reflective
    public boolean permitsIn(String action, Object orgUnitId) {
        return decide(action, () -> {
            Map<String, Object> resource = new LinkedHashMap<>();
            resource.put("orgUnitId", orgUnitId);
            return resource;
        }).permitted();
    }

    /** The full decision, for callers that want to say why. */
    public Decision decide(String action, Supplier<Map<String, Object>> resource) {
        Optional<TenantContext.Scope> scope = TenantContext.current();
        if (scope.isEmpty()) {
            log.warn("Refusing {}: no tenant is bound, so no policy can be read", action);
            return Decision.deny(null, "no tenant scope");
        }
        AccessSubject subject = AccessSubject.current();
        UUID tenantId = scope.get().tenantId();

        Decision decision = readOnly.execute(status -> {
            List<PolicyStore.Rule> rules = policies.forAction(tenantId, action);
            if (rules.isEmpty()) {
                return Decision.permit();
            }
            Map<String, Object> resourceAttributes = withOrgPath(resource.get());
            Function<String, Object> attributes = attributesFor(subject, resourceAttributes);
            return combine(rules, attributes);
        });

        if (!decision.permitted()) {
            // INFO, not WARN: a limit doing its job is not a fault. The policy
            // code is what an administrator needs to answer "why was I refused".
            log.info("Access policy refused {} for user {}: {} ({})", action, subject.userId(),
                    decision.policy(), decision.reason());
        }
        return decision;
    }

    static Decision combine(List<PolicyStore.Rule> rules, Function<String, Object> attributes) {
        boolean anyPermitRule = false;
        boolean permitMatched = false;
        for (PolicyStore.Rule rule : rules) {
            boolean holds = rule.condition().holds(attributes);
            if (rule.deny()) {
                if (holds) {
                    return Decision.deny(rule.code(), "denied by policy");
                }
            } else {
                anyPermitRule = true;
                permitMatched |= holds;
            }
        }
        if (anyPermitRule && !permitMatched) {
            return Decision.deny(null, "no permitting policy matched");
        }
        return Decision.permit();
    }

    static Function<String, Object> attributesFor(AccessSubject subject,
                                                  Map<String, Object> resource) {
        Map<String, Object> subjectAttributes = subject.asAttributes();
        ZonedDateTime now = ZonedDateTime.now(ZoneOffset.UTC);
        Map<String, Object> env = Map.of(
                "hour", now.getHour(),
                "dayOfWeek", now.getDayOfWeek().name());
        return name -> {
            int dot = name.indexOf('.');
            String key = name.substring(dot + 1);
            return switch (name.substring(0, dot)) {
                case "subject" -> subjectAttributes.get(key);
                case "resource" -> resource.get(key);
                case "env" -> env.get(key);
                default -> null;
            };
        };
    }

    private Map<String, Object> lookup(String resourceType, Object resourceId) {
        ResourceAttributeProvider provider = providers.get(resourceType);
        if (provider == null) {
            throw new IllegalStateException("No attribute provider for resource type " + resourceType);
        }
        if (resourceId == null) {
            return Map.of();
        }
        return provider.attributesOf(UUID.fromString(resourceId.toString()));
    }

    /**
     * Adds {@code orgPath} for a resource that names its organisational unit.
     *
     * <p>Units are identity's, so this is the one place that turns an id into
     * a path -- and the subtree rule is then a prefix comparison, the same
     * one row visibility already uses.
     */
    private Map<String, Object> withOrgPath(Map<String, Object> resource) {
        Object orgUnitId = resource.get("orgUnitId");
        if (orgUnitId == null || resource.get("orgPath") != null) {
            return resource;
        }
        Map<String, Object> enriched = new LinkedHashMap<>(resource);
        jdbc.sql("SELECT path FROM org_unit WHERE id = :id")
                .param("id", UUID.fromString(orgUnitId.toString()))
                .query(String.class)
                .optional()
                .ifPresent(path -> enriched.put("orgPath", path));
        return enriched;
    }
}
