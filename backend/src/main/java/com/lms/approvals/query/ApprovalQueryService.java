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
package com.lms.approvals.query;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.lms.approvals.command.PolicySnapshot;
import com.lms.identity.access.AccessSubject;
import com.lms.shared.config.Json;
import com.lms.shared.error.BusinessRuleViolationException;
import com.lms.shared.query.Slice;
import com.lms.shared.tenant.TenantContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The approvals inbox and one request with its trail.
 *
 * <p>Newest first, keyset-paged on {@code (requested_at, id)}. The statement
 * is assembled from the filters actually given, so each shape is served by
 * the index made for it (V53): by status, by requester, by subject, or the
 * tenant's whole list. "Mine to decide" is open requests whose pending
 * permissions overlap the caller's, which the caller did not raise (unless the
 * policy lets them) and has not already approved.
 */
@Service
public class ApprovalQueryService {

    /** Which requests the inbox shows. */
    public enum Scope {
        ALL, MINE_TO_DECIDE, REQUESTED_BY_ME
    }

    private static final String COLUMNS = """
            SELECT r.id, r.subject_type, r.subject_id, r.action, r.summary, r.payload, r.status, r.org_unit_id,
                   r.requested_by, r.requested_at, r.due_at, r.pending_permissions, r.approvals_given,
                   jsonb_array_length(r.policy -> 'decidePermissions') AS approvals_required,
                   r.decided_by, r.decided_at, r.reason, r.version,
                   (r.status = 'REQUESTED'
                    AND r.pending_permissions && CAST(:perms AS text[])
                    AND (COALESCE((r.policy ->> 'allowSelfApproval')::boolean, false)
                         OR r.requested_by IS DISTINCT FROM CAST(:me AS uuid))
                    AND NOT EXISTS (SELECT 1 FROM approval_decision d
                                     WHERE d.tenant_id = r.tenant_id AND d.request_id = r.id
                                       AND d.decision = 'APPROVED'
                                       AND d.decided_by = CAST(:me AS uuid))) AS mine
              FROM approval_request r
            """;

    private final JdbcClient jdbc;
    private final PolicySnapshot snapshots;

    public ApprovalQueryService(JdbcClient jdbc, PolicySnapshot snapshots) {
        this.jdbc = jdbc;
        this.snapshots = snapshots;
    }

    @Transactional(readOnly = true)
    @PreAuthorize("hasAuthority('APPROVAL_READ')")
    public Slice<ApprovalView> list(String status, String subjectType, UUID subjectId, Scope scope,
                                    String cursor, Integer limit) {
        int pageSize = Slice.clampLimit(limit);
        Scope in = scope == null ? Scope.ALL : scope;
        Cursor after = Cursor.parse(cursor);
        UUID me = actorId();
        if (in == Scope.REQUESTED_BY_ME && me == null) {
            return Slice.empty();
        }

        StringBuilder sql = new StringBuilder(COLUMNS).append(" WHERE r.tenant_id = :tenantId");
        String effectiveStatus = in == Scope.MINE_TO_DECIDE ? "REQUESTED" : status;
        if (in == Scope.MINE_TO_DECIDE && status != null && !"REQUESTED".equals(status)) {
            return Slice.empty();
        }
        if (effectiveStatus != null) {
            sql.append(" AND r.status = :status");
        }
        if (in == Scope.REQUESTED_BY_ME) {
            sql.append(" AND r.requested_by = CAST(:me AS uuid)");
        }
        if (subjectType != null) {
            sql.append(" AND r.subject_type = :subjectType");
        }
        if (subjectId != null) {
            sql.append(" AND r.subject_id = :subjectId");
        }
        if (after != null) {
            sql.append(" AND (r.requested_at, r.id) < (:cursorAt, :cursorId)");
        }
        String body = sql + " ORDER BY r.requested_at DESC, r.id DESC LIMIT :limit";
        if (in == Scope.MINE_TO_DECIDE) {
            // The mine predicate is computed per row, so it filters the
            // tenant's open requests -- served by the inbox index on status --
            // before the page is cut; open requests are few by nature.
            body = "SELECT * FROM (" + sql + ") page WHERE page.mine"
                    + " ORDER BY page.requested_at DESC, page.id DESC LIMIT :limit";
        }

        JdbcClient.StatementSpec statement = jdbc.sql(body)
                .param("tenantId", TenantContext.requireTenantId())
                .param("perms", AccessSubject.current().permissions().toArray(String[]::new))
                .param("me", me)
                .param("limit", pageSize + 1);
        if (effectiveStatus != null) {
            statement = statement.param("status", effectiveStatus);
        }
        if (subjectType != null) {
            statement = statement.param("subjectType", subjectType);
        }
        if (subjectId != null) {
            statement = statement.param("subjectId", subjectId);
        }
        if (after != null) {
            statement = statement.param("cursorAt", after.at()).param("cursorId", after.id());
        }
        List<ApprovalView> rows = statement.query((rs, row) -> view(rs, me)).list();
        return Slice.of(rows, pageSize, v -> new Cursor(v.requestedAt().atOffset(ZoneOffset.UTC), v.id()).encode());
    }

    @Transactional(readOnly = true)
    @PreAuthorize("hasAuthority('APPROVAL_READ')")
    public Optional<ApprovalDetailView> find(UUID id) {
        UUID me = actorId();
        record Row(ApprovalView view, String policy) {
        }
        Optional<Row> found = jdbc.sql(COLUMNS.replace("FROM approval_request r",
                        ", r.policy AS policy_snapshot FROM approval_request r")
                        + " WHERE r.tenant_id = :tenantId AND r.id = :id")
                .param("tenantId", TenantContext.requireTenantId())
                .param("perms", AccessSubject.current().permissions().toArray(String[]::new))
                .param("me", me)
                .param("id", id)
                .query((rs, row) -> new Row(view(rs, me), rs.getString("policy_snapshot")))
                .optional();
        return found.map(r -> new ApprovalDetailView(r.view(),
                ApprovalPolicyView.of(snapshots.read(new Json(r.policy()))), trail(id)));
    }

    private List<ApprovalDecisionView> trail(UUID requestId) {
        return jdbc.sql("""
                        SELECT id, step, decision, permission, decided_by, decided_at, note
                          FROM approval_decision
                         WHERE tenant_id = :tenantId AND request_id = :id
                         ORDER BY decided_at, id
                        """)
                .param("tenantId", TenantContext.requireTenantId())
                .param("id", requestId)
                .query((rs, row) -> new ApprovalDecisionView(rs.getObject("id", UUID.class),
                        (Integer) rs.getObject("step"), rs.getString("decision"), rs.getString("permission"),
                        rs.getObject("decided_by", UUID.class), instant(rs, "decided_at"), rs.getString("note")))
                .list();
    }

    private ApprovalView view(ResultSet rs, UUID me) throws SQLException {
        UUID requestedBy = rs.getObject("requested_by", UUID.class);
        return new ApprovalView(rs.getObject("id", UUID.class), rs.getString("subject_type"),
                rs.getObject("subject_id", UUID.class), rs.getString("action"), rs.getString("summary"),
                snapshots.payload(Json.of(rs.getString("payload"))), rs.getString("status"),
                rs.getObject("org_unit_id", UUID.class), requestedBy, instant(rs, "requested_at"),
                instant(rs, "due_at"), strings(rs.getArray("pending_permissions")), rs.getInt("approvals_given"),
                rs.getInt("approvals_required"), rs.getObject("decided_by", UUID.class),
                instant(rs, "decided_at"), rs.getString("reason"), rs.getBoolean("mine"),
                me != null && me.equals(requestedBy), rs.getLong("version"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime at = rs.getObject(column, OffsetDateTime.class);
        return at == null ? null : at.toInstant();
    }

    private static List<String> strings(Array array) throws SQLException {
        return array == null ? List.of() : Arrays.asList((String[]) array.getArray());
    }

    private static UUID actorId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null) {
            return null;
        }
        try {
            return UUID.fromString(authentication.getName());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** {@code <requestedAt ISO>_<id>}, as the audit history's cursor. */
    record Cursor(OffsetDateTime at, UUID id) {

        String encode() {
            return at.toInstant() + "_" + id;
        }

        static Cursor parse(String cursor) {
            if (cursor == null || cursor.isBlank()) {
                return null;
            }
            int split = cursor.lastIndexOf('_');
            try {
                return new Cursor(Instant.parse(cursor.substring(0, split)).atOffset(ZoneOffset.UTC),
                        UUID.fromString(cursor.substring(split + 1)));
            } catch (DateTimeParseException | IllegalArgumentException | StringIndexOutOfBoundsException e) {
                throw new BusinessRuleViolationException("invalid-cursor", "The cursor was not issued by this listing");
            }
        }
    }
}
