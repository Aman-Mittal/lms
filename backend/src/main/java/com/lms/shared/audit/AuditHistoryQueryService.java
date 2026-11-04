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

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.lms.shared.error.BusinessRuleViolationException;
import com.lms.shared.query.Slice;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads one record's audit trail back, newest first, a page at a time.
 *
 * <p>The one read behind every {@code GET /<resource>/{id}/history}. It does
 * not decide who may see the record: each controller first reads the record
 * itself through its own module's query, under that module's rules, and only
 * asks for the history of a record it could read. Row-level security confines
 * the rows to the tenant in scope regardless.
 *
 * <p>Keyset on {@code (occurred_at, id)}: rows of one transaction share a
 * timestamp, so the id breaks ties and no row is skipped or repeated across a
 * page boundary. Served by {@code audit_log_resource_history_idx} (V45).
 */
@Service
@Transactional(readOnly = true)
public class AuditHistoryQueryService {

    private static final TypeReference<Map<String, Object>> OBJECT = new TypeReference<>() {
    };

    private final JdbcClient jdbc;
    private final JsonMapper json;

    public AuditHistoryQueryService(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /** As {@link #historyOf(String, UUID, String, Integer, HistoryViewer)}, read as staff. */
    public Slice<HistoryEntryView> historyOf(String resourceType, UUID resourceId, String cursor,
                                             Integer limit) {
        return historyOf(resourceType, resourceId, cursor, limit, HistoryViewer.staff());
    }

    /**
     * A page of the record's history, as {@code viewer} may see it.
     *
     * @param resourceType the audit resource type the record's module writes ({@code SalesOrder}, ...)
     * @param cursor       the {@code nextCursor} of the previous page, or null for the newest
     */
    public Slice<HistoryEntryView> historyOf(String resourceType, UUID resourceId, String cursor,
                                             Integer limit, HistoryViewer viewer) {
        int pageSize = Slice.clampLimit(limit);
        Cursor after = Cursor.parse(cursor);
        List<HistoryEntryView> rows = jdbc.sql("""
                        SELECT a.id, a.action, a.actor_id, u.full_name AS actor_name,
                               a.correlation_id, a.occurred_at,
                               a.before_state::text AS before_state,
                               a.after_state::text AS after_state
                          FROM audit_log a
                          LEFT JOIN app_user u ON u.id = a.actor_id
                         WHERE a.tenant_id = app_current_tenant()
                           AND a.resource_type = :resourceType
                           AND a.resource_id = :resourceId
                           AND (CAST(:afterAt AS timestamptz) IS NULL
                                OR (a.occurred_at, a.id) < (CAST(:afterAt AS timestamptz),
                                                            CAST(:afterId AS uuid)))
                         ORDER BY a.occurred_at DESC, a.id DESC
                         LIMIT :limit
                        """)
                .param("resourceType", resourceType)
                .param("resourceId", resourceId)
                .param("afterAt", after == null ? null : after.occurredAt())
                .param("afterId", after == null ? null : after.id())
                .param("limit", pageSize + 1)
                .query((rs, row) -> entry(rs, viewer))
                .list();
        return Slice.of(rows, pageSize, view -> new Cursor(
                view.occurredAt().atOffset(ZoneOffset.UTC), view.id()).encode());
    }

    /**
     * One row as the viewer may see it. A portal reader learns whether a step
     * was theirs, but never the name or id of the staff member who took it.
     */
    private HistoryEntryView entry(ResultSet rs, HistoryViewer viewer) throws SQLException {
        UUID actorId = rs.getObject("actor_id", UUID.class);
        boolean mine = actorId != null && actorId.equals(viewer.userId());
        String kind = actorId == null ? "PLATFORM" : mine ? "YOU" : "USER";
        return new HistoryEntryView(rs.getObject("id", UUID.class), rs.getString("action"), kind,
                viewer.seesNames() || mine ? actorId : null,
                viewer.seesNames() ? rs.getString("actor_name") : null,
                rs.getString("correlation_id"),
                rs.getObject("occurred_at", OffsetDateTime.class).toInstant(),
                parse(rs.getString("before_state")), parse(rs.getString("after_state")));
    }

    private Map<String, Object> parse(String text) {
        return text == null ? null : json.readValue(text, OBJECT);
    }

    /**
     * Where the previous page stopped: the last row's time and id. Readable,
     * which helps when a page looks wrong, but nothing should construct one.
     */
    record Cursor(OffsetDateTime occurredAt, UUID id) {

        String encode() {
            return occurredAt.toInstant() + "_" + id;
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
