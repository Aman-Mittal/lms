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

import java.time.OffsetDateTime;
import java.util.UUID;

import com.lms.shared.audit.TransitionAudit;
import com.lms.shared.config.Json;
import com.lms.shared.error.BusinessRuleViolationException;
import com.lms.shared.error.ResourceNotFoundException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.modulith.events.IncompleteEventPublications;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * An operator's two answers to a parked event: try it again, or give it up.
 *
 * <p>Replaying puts the publication back into the live registry and resubmits
 * it to the same listener, which runs synchronously. It then either completed
 * -- the parked row is marked replayed -- or failed once more, in which case it
 * is taken out of the registry again and re-parked with the new failure counted.
 * The three steps are separate transactions because the listener must see the
 * publication committed and runs in its own.
 */
@Service
public class DeadLetterService {

    private static final int MAX_REASON = 500;

    private final JdbcClient jdbc;
    private final IncompleteEventPublications incomplete;
    private final TransitionAudit audit;
    private final PlatformTransactionManager transactionManager;

    public DeadLetterService(JdbcClient jdbc, IncompleteEventPublications incomplete, TransitionAudit audit,
                             PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.incomplete = incomplete;
        this.audit = audit;
        this.transactionManager = transactionManager;
    }

    public enum Outcome {
        /** The listener completed; the event is resolved. */
        REPLAYED,
        /** The listener failed again; the event is parked with one more attempt counted. */
        PARKED_AGAIN
    }

    public record Replay(Outcome outcome, int attempts) {
    }

    private record Parked(String listenerId, String eventType, String serializedEvent,
                          OffsetDateTime publicationDate, int attempts, OffsetDateTime firstFailedAt,
                          OffsetDateTime lastFailedAt, String lastError, String status) {
    }

    @PreAuthorize("hasAuthority('EVENT_DEAD_LETTER_MANAGE')")
    public Replay replay(UUID id) {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);

        tx.executeWithoutResult(status -> {
            Parked parked = parked(id);
            int inserted = jdbc.sql("""
                            INSERT INTO event_publication
                                (id, listener_id, event_type, serialized_event, publication_date,
                                 status, completion_attempts)
                            VALUES (:id, :listener, :type, :event, :published, 'PUBLISHED', 0)
                            ON CONFLICT (id) DO NOTHING
                            """)
                    .param("id", id)
                    .param("listener", parked.listenerId())
                    .param("type", parked.eventType())
                    .param("event", parked.serializedEvent())
                    .param("published", parked.publicationDate())
                    .update();
            if (inserted == 0) {
                throw new BusinessRuleViolationException("dead-letter-replay-in-progress",
                        "This event is already being replayed");
            }
            // Carry the count over, so a failure now is counted on top of the earlier ones.
            jdbc.sql("""
                            INSERT INTO event_failure
                                (publication_id, tenant_id, listener_id, event_type, failures,
                                 first_failed_at, last_failed_at, last_error)
                            SELECT id, tenant_id, listener_id, event_type, attempts,
                                   first_failed_at, last_failed_at, last_error
                              FROM event_dead_letter WHERE id = :id
                            ON CONFLICT (publication_id) DO UPDATE SET failures = EXCLUDED.failures
                            """)
                    .param("id", id).update();
        });

        incomplete.resubmitIncompletePublications(publication -> publication.getIdentifier().equals(id));

        Replay replay = tx.execute(status -> {
            OffsetDateTime completedAt = jdbc.sql(
                            "SELECT completion_date FROM event_publication WHERE id = :id")
                    .param("id", id).query(OffsetDateTime.class).optional().orElse(null);
            boolean stillThere = jdbc.sql("SELECT 1 FROM event_publication WHERE id = :id")
                    .param("id", id).query(Integer.class).optional().isPresent();
            if (completedAt != null || !stillThere) {
                jdbc.sql("""
                                UPDATE event_dead_letter
                                   SET status = 'REPLAYED', resolved_at = now(),
                                       version = COALESCE(version, 0) + 1
                                 WHERE id = :id
                                """)
                        .param("id", id).update();
                jdbc.sql("DELETE FROM event_failure WHERE publication_id = :id").param("id", id).update();
                int attempts = parked(id, false).attempts();
                audit.record("EVENT_REPLAYED", "EVENT_DEAD_LETTER", id, Json.object("status", "PARKED"),
                        Json.object("status", "REPLAYED"));
                return new Replay(Outcome.REPLAYED, attempts);
            }
            // The failure the listener just recorded, on top of the carried-over count.
            int recorded = jdbc.sql("SELECT failures FROM event_failure WHERE publication_id = :id")
                    .param("id", id).query(Integer.class).optional().orElse(0);
            jdbc.sql("""
                            UPDATE event_dead_letter d
                               SET attempts = GREATEST(d.attempts + 1, :recorded),
                                   last_failed_at = COALESCE((SELECT f.last_failed_at FROM event_failure f
                                                               WHERE f.publication_id = d.id), now()),
                                   last_error = COALESCE((SELECT f.last_error FROM event_failure f
                                                           WHERE f.publication_id = d.id), d.last_error),
                                   parked_at = now(),
                                   version = COALESCE(d.version, 0) + 1
                             WHERE d.id = :id
                            """)
                    .param("id", id).param("recorded", recorded).update();
            jdbc.sql("DELETE FROM event_failure WHERE publication_id = :id").param("id", id).update();
            jdbc.sql("DELETE FROM event_publication WHERE id = :id").param("id", id).update();
            int attempts = parked(id, false).attempts();
            audit.record("EVENT_REPARKED", "EVENT_DEAD_LETTER", id, null,
                    Json.object("status", "PARKED", "attempts", String.valueOf(attempts)));
            return new Replay(Outcome.PARKED_AGAIN, attempts);
        });
        return replay;
    }

    @Transactional
    @PreAuthorize("hasAuthority('EVENT_DEAD_LETTER_MANAGE')")
    public void discard(UUID id, String reason) {
        String why = reason == null ? "" : reason.strip();
        if (why.isEmpty() || why.length() > MAX_REASON) {
            throw new BusinessRuleViolationException("dead-letter-reason-required",
                    "Discarding needs a reason of 1 to " + MAX_REASON + " characters");
        }
        parked(id);
        jdbc.sql("""
                        UPDATE event_dead_letter
                           SET status = 'DISCARDED', resolved_at = now(), resolution_note = :reason,
                               version = COALESCE(version, 0) + 1
                         WHERE id = :id AND status = 'PARKED'
                        """)
                .param("id", id).param("reason", why).update();
        audit.record("EVENT_DISCARDED", "EVENT_DEAD_LETTER", id, Json.object("status", "PARKED"),
                Json.object("status", "DISCARDED", "reason", why));
    }

    /** The row, visible only to its own tenant, and still parked. */
    private Parked parked(UUID id) {
        Parked parked = parked(id, true);
        if (!"PARKED".equals(parked.status())) {
            throw new BusinessRuleViolationException("dead-letter-not-parked",
                    "This event is " + parked.status() + " and can no longer be replayed or discarded");
        }
        return parked;
    }

    private Parked parked(UUID id, boolean lock) {
        return jdbc.sql("""
                        SELECT listener_id, event_type, serialized_event, publication_date, attempts,
                               first_failed_at, last_failed_at, last_error, status
                          FROM event_dead_letter WHERE id = :id
                        """ + (lock ? " FOR UPDATE" : ""))
                .param("id", id)
                .query((rs, row) -> new Parked(rs.getString("listener_id"), rs.getString("event_type"),
                        rs.getString("serialized_event"), rs.getObject("publication_date", OffsetDateTime.class),
                        rs.getInt("attempts"), rs.getObject("first_failed_at", OffsetDateTime.class),
                        rs.getObject("last_failed_at", OffsetDateTime.class), rs.getString("last_error"),
                        rs.getString("status")))
                .optional()
                .orElseThrow(() -> new ResourceNotFoundException("Parked event", id));
    }
}
