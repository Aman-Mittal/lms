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

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.lms.shared.audit.TransitionAudit;
import com.lms.shared.config.Json;
import com.lms.shared.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.modulith.events.IncompleteEventPublications;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * One pass over the incomplete publications: resubmit those whose backoff has
 * run out, park those that have used up their attempts.
 *
 * <p>Driven by {@link EventRetryScheduler} in a running application and called
 * directly by tests, with the time passed in so a test can step the clock.
 *
 * <p>The registry table has no tenant column and no row-level security, so it
 * is read as it is. Everything per tenant -- the failure record, the parked
 * row, the audit entry -- is read and written with that tenant bound, exactly
 * as a request would.
 */
@Component
public class EventRetrier {

    private static final Logger log = LoggerFactory.getLogger(EventRetrier.class);

    private final JdbcClient jdbc;
    private final EventPayloads payloads;
    private final IncompleteEventPublications incomplete;
    private final TransitionAudit audit;
    private final PlatformTransactionManager transactionManager;
    private final RetryPolicy policy;
    private final int batchSize;

    public EventRetrier(JdbcClient jdbc, EventPayloads payloads, IncompleteEventPublications incomplete,
                        TransitionAudit audit, PlatformTransactionManager transactionManager,
                        @Value("${lms.events.retry.max-attempts:5}") int maxAttempts,
                        @Value("${lms.events.retry.initial-backoff:PT1M}") Duration initialBackoff,
                        @Value("${lms.events.retry.max-backoff:PT1H}") Duration maxBackoff,
                        @Value("${lms.events.retry.grace-period:PT5M}") Duration gracePeriod,
                        @Value("${lms.events.retry.batch-size:100}") int batchSize) {
        this.jdbc = jdbc;
        this.payloads = payloads;
        this.incomplete = incomplete;
        this.audit = audit;
        this.transactionManager = transactionManager;
        this.policy = new RetryPolicy(maxAttempts, initialBackoff, maxBackoff, gracePeriod);
        this.batchSize = batchSize;
    }

    /** What one pass did. */
    public record Pass(int resubmitted, int parked) {
    }

    /** One incomplete publication, as the registry holds it. */
    record Pending(UUID id, String listenerId, String eventType, String serializedEvent,
                   Instant publicationDate, int completionAttempts) {
    }

    /** What has been recorded about its failures, if anything. */
    record Failure(int failures, Instant firstFailedAt, Instant lastFailedAt, String lastError) {
    }

    public Pass runOnce(Instant now) {
        List<Pending> pending = jdbc.sql("""
                        SELECT id, listener_id, event_type, serialized_event, publication_date,
                               COALESCE(completion_attempts, 0) AS completion_attempts
                          FROM event_publication
                         WHERE completion_date IS NULL
                         ORDER BY publication_date
                         LIMIT :batch
                        """)
                .param("batch", batchSize)
                .query((rs, row) -> new Pending(rs.getObject("id", UUID.class), rs.getString("listener_id"),
                        rs.getString("event_type"), rs.getString("serialized_event"),
                        rs.getObject("publication_date", OffsetDateTime.class).toInstant(),
                        rs.getInt("completion_attempts")))
                .list();

        // Due for resubmission, with the tenant each belongs to (null for none).
        Map<UUID, UUID> due = new HashMap<>();
        int parked = 0;
        for (Pending publication : pending) {
            try {
                Optional<UUID> tenantId = payloads.tenantOf(publication.serializedEvent());
                Optional<Failure> failure = tenantId.flatMap(tenant -> failureOf(tenant, publication.id()));
                // An event with no tenant has no failure record; the registry's own count stands in.
                int failures = failure.map(Failure::failures)
                        .orElse(tenantId.isPresent() ? 0 : publication.completionAttempts());
                Instant lastFailedAt = failure.map(Failure::lastFailedAt)
                        .orElse(failures > 0 ? publication.publicationDate() : null);
                switch (policy.decide(failures, publication.publicationDate(), lastFailedAt, now)) {
                    case RESUBMIT -> due.put(publication.id(), tenantId.orElse(null));
                    case PARK -> {
                        park(publication, tenantId, failure.orElse(null), failures);
                        parked++;
                    }
                    case WAIT -> {
                        // Inside its backoff or its grace period.
                    }
                }
            } catch (RuntimeException e) {
                log.error("Could not process incomplete publication {}", publication.id(), e);
            }
        }
        if (!due.isEmpty()) {
            incomplete.resubmitIncompletePublications(publication -> due.containsKey(publication.getIdentifier()));
            due.forEach(this::forgetIfCompleted);
        }
        if (!due.isEmpty() || parked > 0) {
            log.info("Event retry: resubmitted {}, parked {}", due.size(), parked);
        }
        return new Pass(due.size(), parked);
    }

    private Optional<Failure> failureOf(UUID tenantId, UUID publicationId) {
        return TenantContext.callWith(tenantId, null, () -> new TransactionTemplate(transactionManager)
                .execute(status -> jdbc.sql("""
                                SELECT failures, first_failed_at, last_failed_at, last_error
                                  FROM event_failure WHERE publication_id = :id
                                """)
                        .param("id", publicationId)
                        .query((rs, row) -> new Failure(rs.getInt("failures"),
                                rs.getObject("first_failed_at", OffsetDateTime.class).toInstant(),
                                rs.getObject("last_failed_at", OffsetDateTime.class).toInstant(),
                                rs.getString("last_error")))
                        .optional()));
    }

    private void park(Pending publication, Optional<UUID> tenantId, Failure failure, int failures) {
        int attempts = Math.max(failures, 1);
        String lastError = failure == null ? "No failure was recorded for this publication" : failure.lastError();
        if (tenantId.isEmpty()) {
            new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                jdbc.sql("""
                                SELECT park_event_without_tenant(:id, :listener, :type, :event,
                                                                 :published, :attempts, :error)
                                """)
                        .param("id", publication.id())
                        .param("listener", publication.listenerId())
                        .param("type", publication.eventType())
                        .param("event", publication.serializedEvent())
                        .param("published", OffsetDateTime.ofInstant(publication.publicationDate(),
                                ZoneOffset.UTC))
                        .param("attempts", attempts)
                        .param("error", lastError)
                        .query(Object.class).optional();
                deletePublication(publication.id());
            });
            log.warn("Parked event publication {} ({} for {}): its event names no tenant, so no tenant "
                            + "can see it through the API; handle it in event_dead_letter directly",
                    publication.id(), publication.eventType(), publication.listenerId());
            return;
        }
        UUID tenant = tenantId.get();
        TenantContext.runWith(tenant, null, () -> new TransactionTemplate(transactionManager)
                .executeWithoutResult(status -> {
                    Instant now = Instant.now();
                    jdbc.sql("""
                                    INSERT INTO event_dead_letter
                                        (id, tenant_id, listener_id, event_type, serialized_event,
                                         publication_date, attempts, first_failed_at, last_failed_at,
                                         last_error, parked_at)
                                    VALUES (:id, :tenant, :listener, :type, :event, :published, :attempts,
                                            :first, :last, :error, now())
                                    ON CONFLICT (id) DO UPDATE
                                       SET status = 'PARKED', attempts = EXCLUDED.attempts,
                                           last_failed_at = EXCLUDED.last_failed_at,
                                           last_error = EXCLUDED.last_error, parked_at = now(),
                                           resolved_at = NULL, resolution_note = NULL
                                    """)
                            .param("id", publication.id())
                            .param("tenant", tenant)
                            .param("listener", publication.listenerId())
                            .param("type", publication.eventType())
                            .param("event", publication.serializedEvent())
                            .param("published", OffsetDateTime.ofInstant(publication.publicationDate(),
                                    ZoneOffset.UTC))
                            .param("attempts", attempts)
                            .param("first", OffsetDateTime.ofInstant(
                                    failure == null ? now : failure.firstFailedAt(), ZoneOffset.UTC))
                            .param("last", OffsetDateTime.ofInstant(
                                    failure == null ? now : failure.lastFailedAt(), ZoneOffset.UTC))
                            .param("error", lastError)
                            .update();
                    jdbc.sql("DELETE FROM event_failure WHERE publication_id = :id")
                            .param("id", publication.id()).update();
                    deletePublication(publication.id());
                    audit.record("EVENT_PARKED", "EVENT_DEAD_LETTER", publication.id(), null,
                            Json.object("eventType", publication.eventType(),
                                    "listenerId", publication.listenerId(),
                                    "attempts", String.valueOf(attempts)));
                }));
    }

    /** A retry that worked leaves nothing to pace: its failure record goes. */
    private void forgetIfCompleted(UUID publicationId, UUID tenantId) {
        if (tenantId == null) {
            return;
        }
        boolean open = jdbc.sql("SELECT 1 FROM event_publication WHERE id = :id AND completion_date IS NULL")
                .param("id", publicationId).query(Integer.class).optional().isPresent();
        if (open) {
            return;
        }
        TenantContext.runWith(tenantId, null, () -> new TransactionTemplate(transactionManager)
                .executeWithoutResult(status -> jdbc.sql("DELETE FROM event_failure WHERE publication_id = :id")
                        .param("id", publicationId).update()));
    }

    private void deletePublication(UUID id) {
        jdbc.sql("DELETE FROM event_publication WHERE id = :id").param("id", id).update();
    }
}
