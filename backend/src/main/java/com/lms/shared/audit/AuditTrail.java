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
package com.lms.shared.audit;

import java.time.ZoneOffset;
import java.util.UUID;

import com.lms.shared.config.Json;
import com.lms.shared.observability.CorrelationIdFilter;
import com.lms.shared.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes the append-only audit log of vision document 3.1.
 *
 * <p>V2 created the table, gave it rules that refuse UPDATE and DELETE, and
 * nothing ever wrote to it. An empty audit table is worse than none at all: it
 * answers "was there anything suspicious" with silence, and silence reads as
 * no.
 *
 * <p>Every entry is written in its own transaction. An audit record that rolls
 * back with the thing it was recording is an audit record that only exists for
 * actions that succeeded, which is precisely backwards -- the refused dispatch
 * and the rejected login are the entries an investigation wants.
 */
@Component
public class AuditTrail {

    private static final Logger log = LoggerFactory.getLogger(AuditTrail.class);

    private final JdbcClient jdbc;

    public AuditTrail(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Records a mutation with snapshots either side of it.
     *
     * @param before state before the change, or null for a creation
     * @param after  state after the change, or null for a deletion
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(String action, String resourceType, UUID resourceId,
                       Json before, Json after) {
        recordFor(TenantContext.current().map(TenantContext.Scope::tenantId).orElse(null),
                currentActorId(), action, resourceType, resourceId, before, after);
    }

    /**
     * Records against an explicit tenant and actor.
     *
     * <p>Needed by authentication, which happens before a scope exists and, on
     * failure, before anybody has proved who they are.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordFor(UUID tenantId, UUID actorId, String action, String resourceType,
                          UUID resourceId, Json before, Json after) {
        if (tenantId == null) {
            // Nothing to attribute it to, and the table requires a tenant. Log
            // rather than throw: failing the caller's real work because the
            // audit could not be attributed would turn a record-keeping gap
            // into an outage.
            log.warn("Dropping audit entry '{}' with no tenant in scope", action);
            return;
        }

        jdbc.sql("""
                        INSERT INTO audit_log
                            (id, tenant_id, actor_id, action, resource_type, resource_id,
                             correlation_id, before_state, after_state, occurred_at)
                        VALUES (:id, :tenantId, :actorId, :action, :resourceType, :resourceId,
                                :correlationId, :before, :after, :occurredAt)
                        """)
                .param("id", UUID.randomUUID())
                .param("tenantId", tenantId)
                .param("actorId", actorId)
                .param("action", action)
                .param("resourceType", resourceType)
                .param("resourceId", resourceId)
                // Ties an audit entry to the request that produced it and to
                // every log line that request emitted, which is what turns a
                // row into an investigation.
                .param("correlationId", CorrelationIdFilter.current())
                .param("before", before)
                .param("after", after)
                .param("occurredAt", java.time.Instant.now().atOffset(ZoneOffset.UTC))
                .update();
    }

    /**
     * The signed-in user's identifier, or null when there is not one.
     *
     * <p>Null is the honest answer during authentication itself: at that point
     * the claim of identity is exactly what is in question.
     */
    private static UUID currentActorId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return null;
        }
        try {
            return UUID.fromString(authentication.getName());
        } catch (IllegalArgumentException e) {
            // A principal that is not a user identifier -- a service account, or
            // a test. Not an error, and not something to attribute.
            return null;
        }
    }
}
