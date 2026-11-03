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
package com.lms.events.query;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.lms.shared.error.BusinessRuleViolationException;
import com.lms.shared.query.Slice;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The read side of the parked list, through row-level security like any other read. */
@Service
public class DeadLetterQueryService {

    private static final Set<String> STATUSES = Set.of("PARKED", "REPLAYED", "DISCARDED");
    private static final String COLUMNS = """
            SELECT id, seq, event_type, listener_id, status, attempts, first_failed_at, last_failed_at,
                   last_error, parked_at, publication_date, resolved_at, resolution_note, serialized_event
              FROM event_dead_letter
            """;
    private static final RowMapper<DeadLetterView> VIEW = (rs, row) -> new DeadLetterView(
            rs.getObject("id", UUID.class), rs.getLong("seq"), rs.getString("event_type"),
            rs.getString("listener_id"), rs.getString("status"), rs.getInt("attempts"),
            instant(rs.getObject("first_failed_at", OffsetDateTime.class)),
            instant(rs.getObject("last_failed_at", OffsetDateTime.class)), rs.getString("last_error"),
            instant(rs.getObject("parked_at", OffsetDateTime.class)),
            instant(rs.getObject("publication_date", OffsetDateTime.class)),
            instant(rs.getObject("resolved_at", OffsetDateTime.class)), rs.getString("resolution_note"));

    private final JdbcClient jdbc;

    public DeadLetterQueryService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * @param status PARKED unless given
     * @param cursor the {@code parkedNo} the previous page ended on
     */
    @Transactional(readOnly = true)
    @PreAuthorize("hasAuthority('EVENT_DEAD_LETTER_READ')")
    public Slice<DeadLetterView> list(String status, String eventType, String listenerId, String cursor,
                                      Integer limit) {
        String wanted = status == null || status.isBlank() ? "PARKED" : status.strip().toUpperCase();
        if (!STATUSES.contains(wanted)) {
            throw new BusinessRuleViolationException("dead-letter-status-invalid",
                    "The status is one of " + STATUSES);
        }
        Long after = null;
        if (cursor != null && !cursor.isBlank()) {
            try {
                after = Long.valueOf(cursor);
            } catch (NumberFormatException e) {
                throw new BusinessRuleViolationException("cursor-invalid", "The cursor is not one this list issued");
            }
        }
        int pageSize = Slice.clampLimit(limit);
        var rows = jdbc.sql(COLUMNS + """
                         WHERE status = :status
                           AND (CAST(:eventType AS text) IS NULL OR event_type = CAST(:eventType AS text))
                           AND (CAST(:listenerId AS text) IS NULL OR listener_id = CAST(:listenerId AS text))
                           AND (CAST(:after AS bigint) IS NULL OR seq > CAST(:after AS bigint))
                         ORDER BY seq
                         LIMIT :limit
                        """)
                .param("status", wanted)
                .param("eventType", blankToNull(eventType))
                .param("listenerId", blankToNull(listenerId))
                .param("after", after)
                .param("limit", pageSize + 1)
                .query(VIEW).list();
        return Slice.of(rows, pageSize, view -> String.valueOf(view.parkedNo()));
    }

    @Transactional(readOnly = true)
    @PreAuthorize("hasAuthority('EVENT_DEAD_LETTER_READ')")
    public Optional<DeadLetterDetail> find(UUID id) {
        return jdbc.sql(COLUMNS + " WHERE id = :id").param("id", id)
                .query((rs, row) -> new DeadLetterDetail(VIEW.mapRow(rs, row), rs.getString("serialized_event")))
                .optional();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private static Instant instant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }
}
