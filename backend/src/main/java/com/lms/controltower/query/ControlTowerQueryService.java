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
package com.lms.controltower.query;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.lms.shared.error.BusinessRuleViolationException;
import com.lms.shared.query.Slice;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read side for the control tower.
 *
 * <p>The board is newest first, because the question a control tower desk
 * asks is "what just happened". That makes the sort key a timestamp, which is
 * not unique, so the cursor carries the ticket id as a tie-breaker: two
 * tickets raised in the same instant by the same detector must neither repeat
 * across pages nor fall between them.
 */
@Service
@Transactional(readOnly = true)
@PreAuthorize("hasAuthority('EXCEPTION_READ')")
public class ControlTowerQueryService {

    private static final String SELECT = """
            SELECT x.id, x.ticket_no, x.trip_id, x.trip_no, x.vehicle_id,
                   v.registration_no,
                   x.subject_type, x.subject_id, x.subject_label,
                   x.category, x.severity, x.status, x.description, x.source,
                   x.lat, x.lon, x.position_source, x.position_at, x.suspends_settlement,
                   x.raised_by, x.raised_at,
                   x.assignee_user_id, u.full_name AS assignee_name, x.assigned_at,
                   x.resolution, x.resolved_by, x.resolved_at, x.closed_by, x.closed_at
              FROM exception_ticket x
              LEFT JOIN vehicle v ON v.id = x.vehicle_id
              LEFT JOIN app_user u ON u.id = x.assignee_user_id
            """;

    private final JdbcClient jdbc;

    public ControlTowerQueryService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<ExceptionView> findById(UUID id) {
        return jdbc.sql(SELECT + " WHERE x.id = :id")
                .param("id", id)
                .query(ExceptionView.class)
                .optional();
    }

    /**
     * A page of the board, newest first.
     *
     * @param status   optional; one lifecycle state
     * @param severity optional; P1, P2 or P3
     * @param cursor   the {@code nextCursor} of the previous page
     */
    public Slice<ExceptionView> list(String status, String severity, String cursor,
                                     Integer limit) {
        int pageSize = Slice.clampLimit(limit);
        Cursor after = Cursor.parse(cursor);

        List<ExceptionView> rows = jdbc.sql(SELECT + """
                         WHERE (CAST(:status AS text) IS NULL OR x.status = CAST(:status AS text))
                           AND (CAST(:severity AS text) IS NULL
                                OR x.severity = CAST(:severity AS text))
                           AND (CAST(:afterAt AS timestamptz) IS NULL
                                OR (x.raised_at, x.id) < (CAST(:afterAt AS timestamptz),
                                                          CAST(:afterId AS uuid)))
                         ORDER BY x.raised_at DESC, x.id DESC
                         LIMIT :limit
                        """)
                .param("status", status)
                .param("severity", severity)
                .param("afterAt", after == null ? null : after.raisedAt())
                .param("afterId", after == null ? null : after.id())
                .param("limit", pageSize + 1)
                .query(ExceptionView.class)
                .list();

        return Slice.of(rows, pageSize, view -> new Cursor(
                view.raisedAt().atOffset(ZoneOffset.UTC), view.id()).encode());
    }

    /** Every step the ticket has been through, oldest first. */
    public List<ExceptionActivityView> historyOf(UUID exceptionId) {
        return jdbc.sql("""
                        SELECT action, from_status, to_status, note, actor, occurred_at
                          FROM exception_activity
                         WHERE exception_id = :exceptionId
                         ORDER BY occurred_at, id
                        """)
                .param("exceptionId", exceptionId)
                .query(ExceptionActivityView.class)
                .list();
    }

    /**
     * Open tickets by severity, plus the two numbers that need chasing.
     *
     * <p>One pass over the status index, counting only what is not closed --
     * closed tickets are history and the board's headline is about now.
     */
    public ExceptionSummaryView summary() {
        return jdbc.sql("""
                        SELECT count(*) FILTER (WHERE severity = 'P1')::int AS open_p1,
                               count(*) FILTER (WHERE severity = 'P2')::int AS open_p2,
                               count(*) FILTER (WHERE severity = 'P3')::int AS open_p3,
                               count(*) FILTER (WHERE status = 'RAISED')::int AS unassigned,
                               count(*) FILTER (WHERE suspends_settlement)::int AS settlement_holds,
                               count(*) FILTER (WHERE trip_id IS NULL)::int AS open_without_trip
                          FROM exception_ticket
                         WHERE status IN ('RAISED', 'ASSIGNED', 'UNDER_INVESTIGATION', 'RESOLVED')
                        """)
                .query(ExceptionSummaryView.class)
                .single();
    }

    /**
     * Where the previous page stopped.
     *
     * <p>Opaque to clients by convention only -- it is readable, which helps
     * when debugging a page that looks wrong, but nothing should construct
     * one.
     */
    record Cursor(OffsetDateTime raisedAt, UUID id) {

        String encode() {
            return raisedAt.toInstant() + "_" + id;
        }

        static Cursor parse(String cursor) {
            if (cursor == null || cursor.isBlank()) {
                return null;
            }
            int split = cursor.lastIndexOf('_');
            try {
                return new Cursor(Instant.parse(cursor.substring(0, split)).atOffset(ZoneOffset.UTC),
                        UUID.fromString(cursor.substring(split + 1)));
            } catch (DateTimeParseException | IllegalArgumentException
                     | StringIndexOutOfBoundsException e) {
                throw new BusinessRuleViolationException("invalid-cursor",
                        "The cursor was not issued by this listing");
            }
        }
    }
}
