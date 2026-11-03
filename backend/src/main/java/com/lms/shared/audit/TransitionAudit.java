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

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import com.lms.shared.config.Json;
import com.lms.shared.observability.CorrelationIdFilter;
import com.lms.shared.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The audit record of a state transition, written in the transition's own
 * transaction.
 *
 * <p>{@link AuditTrail#record} runs in a transaction of its own
 * ({@code REQUIRES_NEW}), which is right for an entry that must survive the
 * failure of what it describes -- a refused login. It is wrong for a lifecycle
 * transition: a transition that rolls back would still leave a row saying it
 * happened, and every row costs a second connection while the first is held.
 * This writes through the caller's transaction instead, so the row commits
 * with the change or not at all, and it is {@code MANDATORY}: an audit call
 * outside a transaction is a defect, not a case to paper over.
 *
 * <p>Cost is the point of the batch form. {@link #recordAll} writes any number
 * of rows of one action in a single INSERT, so cancelling an order of four
 * hundred consignments audits them in one statement, not four hundred. Callers
 * must pass only what they already hold -- the audit adds one statement per
 * kind of transition and never a read.
 */
@Component
public class TransitionAudit {

    private static final Logger log = LoggerFactory.getLogger(TransitionAudit.class);

    private final JdbcClient jdbc;

    public TransitionAudit(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** One resource's snapshots either side of a transition. */
    public record Entry(UUID resourceId, Json before, Json after) {
    }

    /** Records a single transition; see {@link #recordAll}. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void record(String action, String resourceType, UUID resourceId, Json before,
                       Json after) {
        recordAll(action, resourceType, List.of(new Entry(resourceId, before, after)));
    }

    /**
     * Records the same transition for each of {@code entries}, in one
     * statement. Does nothing for an empty list, so a repeated delivery that
     * moved nothing audits nothing and costs nothing.
     *
     * @param before in each entry: the state before the change, or null for a creation
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordAll(String action, String resourceType, List<Entry> entries) {
        if (entries.isEmpty()) {
            return;
        }
        UUID tenantId = TenantContext.current().map(TenantContext.Scope::tenantId).orElse(null);
        if (tenantId == null) {
            // As AuditTrail: the table requires a tenant, and failing the real
            // work because the record could not be attributed would turn a
            // record-keeping gap into an outage.
            log.warn("Dropping audit entry '{}' with no tenant in scope", action);
            return;
        }

        // The rows travel as one JSON document and are unpacked by Postgres,
        // so the statement is the same text for one row or a thousand and
        // needs no array binding. before_state and after_state are already
        // JSON, so they are embedded as they are.
        StringBuilder rows = new StringBuilder("[");
        for (Entry entry : entries) {
            if (rows.length() > 1) {
                rows.append(',');
            }
            rows.append("{\"resource_id\":\"").append(entry.resourceId()).append("\",")
                    .append("\"before_state\":")
                    .append(entry.before() == null ? "null" : entry.before().value())
                    .append(",\"after_state\":")
                    .append(entry.after() == null ? "null" : entry.after().value())
                    .append('}');
        }
        rows.append(']');

        jdbc.sql("""
                        INSERT INTO audit_log
                            (id, tenant_id, actor_id, action, resource_type, resource_id,
                             correlation_id, before_state, after_state, occurred_at)
                        SELECT gen_random_uuid(), :tenantId, :actorId, :action, :resourceType,
                               r.resource_id, :correlationId, r.before_state, r.after_state,
                               :occurredAt
                          FROM jsonb_to_recordset(CAST(:rows AS jsonb))
                               AS r(resource_id uuid, before_state jsonb, after_state jsonb)
                        """)
                .param("tenantId", tenantId)
                .param("actorId", AuditTrail.currentActorId())
                .param("action", action)
                .param("resourceType", resourceType)
                .param("correlationId", CorrelationIdFilter.current())
                .param("occurredAt", Instant.now().atOffset(ZoneOffset.UTC))
                .param("rows", rows.toString())
                .update();
    }
}
