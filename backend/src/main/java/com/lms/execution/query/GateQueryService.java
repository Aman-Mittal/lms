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
package com.lms.execution.query;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.lms.shared.query.Search;
import com.lms.shared.query.Slice;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read side of the gate register and its passes (issue #223).
 *
 * <p>Both lists are newest first and page by keyset on the sort time and the
 * id, served by {@code gate_visit_register_idx} (and its open-only partial) and
 * {@code gate_pass_list_idx}. Reading needs either gate right.
 */
@Service
@Transactional(readOnly = true)
@PreAuthorize("hasAnyAuthority('GATE_REGISTER', 'GATE_PASS_MANAGE')")
public class GateQueryService {

    private static final String VISIT_SELECT = """
            SELECT g.id, g.org_unit_id, ou.name AS org_unit_name, g.terminal_id, tm.code AS terminal_code,
                   g.vehicle_id, g.registration_no, g.driver_id,
                   coalesce(d.full_name, g.driver_name) AS driver_name,
                   g.purpose, g.trip_id, tr.trip_no, g.gate_pass_id, gp.pass_no AS gate_pass_no, g.status,
                   g.entered_at, g.entry_seal_nos, g.entry_weight_ref, g.entry_remarks,
                   g.exited_at, g.exit_seal_nos, g.exit_weight_ref, g.exit_remarks,
                   floor(extract(epoch FROM (coalesce(g.exited_at, now()) - g.entered_at)) / 60)::bigint
                       AS dwell_minutes,
                   g.version
              FROM gate_visit g
              JOIN org_unit ou ON ou.id = g.org_unit_id
              LEFT JOIN terminal tm ON tm.id = g.terminal_id
              LEFT JOIN driver d ON d.id = g.driver_id
              LEFT JOIN trip tr ON tr.id = g.trip_id
              LEFT JOIN gate_pass gp ON gp.id = g.gate_pass_id
            """;

    private static final String PASS_SELECT = """
            SELECT p.id, p.pass_no, p.org_unit_id, ou.name AS org_unit_name, p.terminal_id, tm.code AS terminal_code,
                   p.vehicle_id, p.registration_no, p.trip_id, tr.trip_no, p.purpose, p.valid_from, p.valid_until,
                   p.status, (p.status = 'ISSUED' AND p.valid_until < now()) AS expired,
                   p.used_at, p.used_visit_id, p.cancelled_at, p.cancel_reason, p.version, p.created_at
              FROM gate_pass p
              JOIN org_unit ou ON ou.id = p.org_unit_id
              LEFT JOIN terminal tm ON tm.id = p.terminal_id
              LEFT JOIN trip tr ON tr.id = p.trip_id
            """;

    private static final RowMapper<GateVisitView> VISIT_ROWS = (rs, row) -> new GateVisitView(
            rs.getObject("id", UUID.class), rs.getObject("org_unit_id", UUID.class), rs.getString("org_unit_name"),
            rs.getObject("terminal_id", UUID.class), rs.getString("terminal_code"),
            rs.getObject("vehicle_id", UUID.class), rs.getString("registration_no"),
            rs.getObject("driver_id", UUID.class), rs.getString("driver_name"), rs.getString("purpose"),
            rs.getObject("trip_id", UUID.class), rs.getString("trip_no"),
            rs.getObject("gate_pass_id", UUID.class), rs.getString("gate_pass_no"), rs.getString("status"),
            instant(rs, "entered_at"), strings(rs, "entry_seal_nos"), rs.getString("entry_weight_ref"),
            rs.getString("entry_remarks"), instant(rs, "exited_at"), strings(rs, "exit_seal_nos"),
            rs.getString("exit_weight_ref"), rs.getString("exit_remarks"), rs.getLong("dwell_minutes"),
            (Long) rs.getObject("version"));

    private static final RowMapper<GatePassView> PASS_ROWS = (rs, row) -> new GatePassView(
            rs.getObject("id", UUID.class), rs.getString("pass_no"), rs.getObject("org_unit_id", UUID.class),
            rs.getString("org_unit_name"), rs.getObject("terminal_id", UUID.class), rs.getString("terminal_code"),
            rs.getObject("vehicle_id", UUID.class), rs.getString("registration_no"),
            rs.getObject("trip_id", UUID.class), rs.getString("trip_no"), rs.getString("purpose"),
            instant(rs, "valid_from"), instant(rs, "valid_until"), rs.getString("status"),
            rs.getBoolean("expired"), instant(rs, "used_at"), rs.getObject("used_visit_id", UUID.class),
            instant(rs, "cancelled_at"), rs.getString("cancel_reason"), (Long) rs.getObject("version"),
            instant(rs, "created_at"));

    private final JdbcClient jdbc;

    public GateQueryService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** The register, newest entry first; {@code q} matches the registration number. */
    public Slice<GateVisitView> visits(String status, UUID orgUnitId, UUID terminalId, String purpose,
                                       UUID tripId, String q, String cursor, Integer limit) {
        int pageSize = Slice.clampLimit(limit);
        Cursor after = Cursor.parse(cursor);
        List<GateVisitView> rows = jdbc.sql(VISIT_SELECT + """
                         WHERE (CAST(:status AS text) IS NULL OR g.status = CAST(:status AS text))
                           AND (CAST(:orgUnitId AS uuid) IS NULL OR g.org_unit_id = CAST(:orgUnitId AS uuid))
                           AND (CAST(:terminalId AS uuid) IS NULL OR g.terminal_id = CAST(:terminalId AS uuid))
                           AND (CAST(:purpose AS text) IS NULL OR g.purpose = CAST(:purpose AS text))
                           AND (CAST(:tripId AS uuid) IS NULL OR g.trip_id = CAST(:tripId AS uuid))
                           AND (CAST(:q AS text) IS NULL OR lower(g.registration_no) LIKE CAST(:q AS text))
                           AND (CAST(:atBefore AS timestamptz) IS NULL
                                OR (g.entered_at, g.id) < (CAST(:atBefore AS timestamptz), CAST(:idBefore AS uuid)))
                         ORDER BY g.entered_at DESC, g.id DESC
                         LIMIT :limit
                        """)
                .param("status", status)
                .param("orgUnitId", orgUnitId)
                .param("terminalId", terminalId)
                .param("purpose", purpose)
                .param("tripId", tripId)
                .param("q", Search.containsPattern(q == null ? null : q.replace(" ", "")))
                .param("atBefore", after == null ? null : java.sql.Timestamp.from(after.at()))
                .param("idBefore", after == null ? null : after.id())
                .param("limit", pageSize + 1)
                .query(VISIT_ROWS)
                .list();
        return Slice.of(rows, pageSize, row -> Cursor.of(row.enteredAt(), row.id()));
    }

    public Optional<GateVisitView> visit(UUID id) {
        return jdbc.sql(VISIT_SELECT + " WHERE g.id = :id").param("id", id).query(VISIT_ROWS).optional();
    }

    /** Who is inside now, across the tenant, longest inside first. Bounded: a yard holds a few hundred, not thousands. */
    public List<GateVisitView> openVisits(UUID orgUnitId, UUID terminalId) {
        return jdbc.sql(VISIT_SELECT + """
                         WHERE g.status = 'OPEN'
                           AND (CAST(:orgUnitId AS uuid) IS NULL OR g.org_unit_id = CAST(:orgUnitId AS uuid))
                           AND (CAST(:terminalId AS uuid) IS NULL OR g.terminal_id = CAST(:terminalId AS uuid))
                         ORDER BY g.entered_at, g.id
                         LIMIT 500
                        """)
                .param("orgUnitId", orgUnitId)
                .param("terminalId", terminalId)
                .query(VISIT_ROWS)
                .list();
    }

    public Slice<GatePassView> passes(String status, UUID orgUnitId, String q, String cursor, Integer limit) {
        int pageSize = Slice.clampLimit(limit);
        Cursor after = Cursor.parse(cursor);
        List<GatePassView> rows = jdbc.sql(PASS_SELECT + """
                         WHERE (CAST(:status AS text) IS NULL OR p.status = CAST(:status AS text))
                           AND (CAST(:orgUnitId AS uuid) IS NULL OR p.org_unit_id = CAST(:orgUnitId AS uuid))
                           AND (CAST(:q AS text) IS NULL OR lower(p.registration_no) LIKE CAST(:q AS text)
                                OR lower(p.pass_no) LIKE CAST(:q AS text))
                           AND (CAST(:atBefore AS timestamptz) IS NULL
                                OR (p.created_at, p.id) < (CAST(:atBefore AS timestamptz), CAST(:idBefore AS uuid)))
                         ORDER BY p.created_at DESC, p.id DESC
                         LIMIT :limit
                        """)
                .param("status", status)
                .param("orgUnitId", orgUnitId)
                .param("q", Search.containsPattern(q))
                .param("atBefore", after == null ? null : java.sql.Timestamp.from(after.at()))
                .param("idBefore", after == null ? null : after.id())
                .param("limit", pageSize + 1)
                .query(PASS_ROWS)
                .list();
        return Slice.of(rows, pageSize, row -> Cursor.of(row.createdAt(), row.id()));
    }

    public Optional<GatePassView> pass(UUID id) {
        return jdbc.sql(PASS_SELECT + " WHERE p.id = :id").param("id", id).query(PASS_ROWS).optional();
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        java.sql.Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private static List<String> strings(ResultSet rs, String column) throws SQLException {
        Array array = rs.getArray(column);
        return array == null ? List.of() : List.copyOf(Arrays.asList((String[]) array.getArray()));
    }

    /** A keyset position: the sort time (to the microsecond, as stored) and the id that breaks ties. */
    record Cursor(Instant at, UUID id) {

        static String of(Instant at, UUID id) {
            Instant micros = at.truncatedTo(ChronoUnit.MICROS);
            return micros.getEpochSecond() + "." + String.format("%06d", micros.getNano() / 1000) + "_" + id;
        }

        static Cursor parse(String cursor) {
            if (cursor == null || cursor.isBlank()) {
                return null;
            }
            try {
                String[] parts = cursor.split("_", 2);
                String[] seconds = parts[0].split("\\.", 2);
                Instant at = Instant.ofEpochSecond(Long.parseLong(seconds[0]), Long.parseLong(seconds[1]) * 1000);
                return new Cursor(at, UUID.fromString(parts[1]));
            } catch (RuntimeException e) {
                throw new IllegalArgumentException("The cursor is not one this list handed out", e);
            }
        }
    }
}
