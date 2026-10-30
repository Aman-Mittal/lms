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
package com.lms.events.command;

import java.lang.reflect.Method;
import java.util.Objects;
import java.util.UUID;

import com.lms.shared.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.event.TransactionalApplicationListenerMethodAdapter;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.ConcurrentLruCache;
import tools.jackson.databind.JsonNode;

/**
 * Writes down why a listener failed, against the publication it failed on.
 *
 * <p>The registry knows that a publication is incomplete, not why, and not how
 * many times it has been tried. This keeps that per publication in
 * {@code event_failure}, so the retrier can pace its backoff and a parked event
 * can say what went wrong.
 *
 * <p>The publication is the incomplete one for this listener and event type
 * whose stored payload equals this event, compared as JSON trees with the
 * mapper the registry serializes with. The listener identifier is computed
 * exactly as Spring Modulith computes it, so the two always agree.
 *
 * <p>Recording must never hide the failure it records, so anything that goes
 * wrong here is logged and swallowed.
 */
@Component
public class ListenerFailureRecorder {

    private static final Logger log = LoggerFactory.getLogger(ListenerFailureRecorder.class);
    private static final int MAX_ERROR_LENGTH = 1000;
    private static final ConcurrentLruCache<Method, String> LISTENER_IDS =
            new ConcurrentLruCache<>(512, ListenerFailureRecorder::listenerIdOf);

    private final JdbcClient jdbc;
    private final EventPayloads payloads;
    private final PlatformTransactionManager transactionManager;

    public ListenerFailureRecorder(JdbcClient jdbc, EventPayloads payloads,
                                   PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.payloads = payloads;
        this.transactionManager = transactionManager;
    }

    void record(Method listener, Object event, Throwable failure) {
        try {
            String listenerId = LISTENER_IDS.get(listener);
            JsonNode tree = payloads.treeOf(event);
            UUID publicationId = jdbc.sql("""
                            SELECT id, serialized_event FROM event_publication
                             WHERE listener_id = :listener AND event_type = :type
                               AND completion_date IS NULL
                             ORDER BY publication_date DESC
                             LIMIT 50
                            """)
                    .param("listener", listenerId)
                    .param("type", event.getClass().getName())
                    .query((rs, row) -> tree.equals(payloads.parse(rs.getString("serialized_event")))
                            ? rs.getObject("id", UUID.class) : null)
                    .list().stream().filter(Objects::nonNull).findFirst().orElse(null);
            if (publicationId == null) {
                // Not tracked by the registry (a plain listener call): nothing to pace.
                return;
            }
            UUID tenantId = payloads.tenantOf(tree).orElse(null);
            if (tenantId == null) {
                log.warn("Listener {} failed on publication {} whose event names no tenant; "
                        + "the failure is not recorded", listenerId, publicationId);
                return;
            }
            String error = describe(failure);
            TenantContext.runWith(tenantId, null, () -> requiresNew().executeWithoutResult(status ->
                    jdbc.sql("""
                                    INSERT INTO event_failure
                                        (publication_id, tenant_id, listener_id, event_type, failures,
                                         first_failed_at, last_failed_at, last_error)
                                    VALUES (:id, :tenant, :listener, :type, 1, now(), now(), :error)
                                    ON CONFLICT (publication_id) DO UPDATE
                                       SET failures = event_failure.failures + 1,
                                           last_failed_at = now(),
                                           last_error = EXCLUDED.last_error
                                    """)
                            .param("id", publicationId)
                            .param("tenant", tenantId)
                            .param("listener", listenerId)
                            .param("type", event.getClass().getName())
                            .param("error", error)
                            .update()));
        } catch (RuntimeException e) {
            log.warn("Could not record the failure of listener {}", listener, e);
        }
    }

    private TransactionTemplate requiresNew() {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return template;
    }

    /** The exception's type and message, cut to a length a list can show. */
    static String describe(Throwable failure) {
        String message = failure.getClass().getSimpleName()
                + (failure.getMessage() == null ? "" : ": " + failure.getMessage());
        return message.length() <= MAX_ERROR_LENGTH ? message : message.substring(0, MAX_ERROR_LENGTH);
    }

    /** As Spring Modulith's CompletionRegisteringAdvisor derives it, so both name a listener alike. */
    static String listenerIdOf(Method method) {
        return new TransactionalApplicationListenerMethodAdapter("listener", method.getDeclaringClass(), method)
                .getListenerId();
    }
}
