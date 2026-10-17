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
package com.lms.milkrun.query;

import java.time.LocalDate;
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
 * Read side for milk runs.
 *
 * <p>Routes page by code, which is unique among versions in force. Runs page
 * by day and then route code -- the order a planner reads a week in -- and
 * that pair is unique by construction, because it is the key that makes
 * generation idempotent. Neither cursor needs an id to break ties.
 */
@Service
@Transactional(readOnly = true)
@PreAuthorize("hasAuthority('MILKRUN_READ')")
public class MilkRunQueryService {

    private static final String ROUTE = """
            SELECT r.id, r.code, r.name, r.plant_terminal_id, t.code AS plant_code,
                   r.effective_from, r.effective_to, r.days_of_week, r.departure_time,
                   r.time_zone, r.run_rate, r.per_stop_fee, r.currency, r.created_at
              FROM milk_run_route r
              LEFT JOIN terminal t ON t.id = r.plant_terminal_id
            """;

    private static final String RUN = """
            SELECT r.id, r.route_id, r.route_code, r.run_date, r.planned_departure,
                   r.plant_terminal_id, r.status, r.trip_id, r.trip_no, r.started_at,
                   r.completed_at, r.completion_source, r.run_rate, r.per_stop_fee,
                   r.currency, r.stops_visited, r.stops_skipped, r.charge_amount,
                   (SELECT sum(s.picked_up_kg)
                      FROM circuit_run_stop s
                     WHERE s.tenant_id = r.tenant_id AND s.run_id = r.id) AS total_picked_up_kg
              FROM circuit_run r
            """;

    private final JdbcClient jdbc;

    public MilkRunQueryService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // ----------------------------------------------------------------- routes

    public Optional<MilkRunRouteView> findRoute(UUID id) {
        return jdbc.sql(ROUTE + " WHERE r.id = :id")
                .param("id", id)
                .query(MilkRunRouteView.class)
                .optional();
    }

    /** The versions in force, by code. Superseded versions stay reachable by id. */
    public Slice<MilkRunRouteView> listRoutes(String cursor, Integer limit) {
        int pageSize = Slice.clampLimit(limit);
        List<MilkRunRouteView> rows = jdbc.sql(ROUTE + """
                         WHERE r.effective_to IS NULL
                           AND (CAST(:after AS text) IS NULL OR r.code > CAST(:after AS text))
                         ORDER BY r.code
                         LIMIT :limit
                        """)
                .param("after", cursor == null || cursor.isBlank() ? null : cursor)
                .param("limit", pageSize + 1)
                .query(MilkRunRouteView.class)
                .list();
        return Slice.of(rows, pageSize, MilkRunRouteView::code);
    }

    public List<MilkRunRouteStopView> routeStops(UUID routeId) {
        return jdbc.sql("""
                        SELECT s.sequence, s.terminal_id, t.code AS terminal_code,
                               t.name AS terminal_name, s.window_open_min, s.window_close_min,
                               s.planned_dwell_min
                          FROM milk_run_route_stop s
                          LEFT JOIN terminal t ON t.id = s.terminal_id
                         WHERE s.route_id = :routeId
                         ORDER BY s.sequence
                        """)
                .param("routeId", routeId)
                .query(MilkRunRouteStopView.class)
                .list();
    }

    // ------------------------------------------------------------------- runs

    public Optional<CircuitRunView> findRun(UUID id) {
        return jdbc.sql(RUN + " WHERE r.id = :id")
                .param("id", id)
                .query(CircuitRunView.class)
                .optional();
    }

    /**
     * A page of circuits by day, then route.
     *
     * @param fromDate  optional; the first day to include
     * @param routeCode optional; one route's circuits only
     * @param status    optional; PLANNED, IN_PROGRESS or COMPLETED
     */
    public Slice<CircuitRunView> listRuns(LocalDate fromDate, String routeCode, String status,
                                          String cursor, Integer limit) {
        int pageSize = Slice.clampLimit(limit);
        RunCursor after = RunCursor.parse(cursor);

        List<CircuitRunView> rows = jdbc.sql(RUN + """
                         WHERE (CAST(:fromDate AS date) IS NULL OR r.run_date >= CAST(:fromDate AS date))
                           AND (CAST(:routeCode AS text) IS NULL
                                OR r.route_code = CAST(:routeCode AS text))
                           AND (CAST(:status AS text) IS NULL OR r.status = CAST(:status AS text))
                           AND (CAST(:afterDate AS date) IS NULL
                                OR (r.run_date, r.route_code) > (CAST(:afterDate AS date),
                                                                 CAST(:afterCode AS text)))
                         ORDER BY r.run_date, r.route_code
                         LIMIT :limit
                        """)
                .param("fromDate", fromDate)
                .param("routeCode", routeCode)
                .param("status", status)
                .param("afterDate", after == null ? null : after.runDate())
                .param("afterCode", after == null ? null : after.routeCode())
                .param("limit", pageSize + 1)
                .query(CircuitRunView.class)
                .list();

        return Slice.of(rows, pageSize,
                run -> new RunCursor(run.runDate(), run.routeCode()).encode());
    }

    public List<CircuitRunStopView> runStops(UUID runId) {
        return jdbc.sql("""
                        SELECT s.sequence, s.terminal_id, t.code AS terminal_code,
                               s.window_opens_at, s.window_closes_at, s.planned_dwell_min,
                               s.status, s.arrived_at, s.departed_at, s.arrival_source,
                               s.minutes_late, s.picked_up_kg, s.skip_reason
                          FROM circuit_run_stop s
                          LEFT JOIN terminal t ON t.id = s.terminal_id
                         WHERE s.run_id = :runId
                         ORDER BY s.sequence
                        """)
                .param("runId", runId)
                .query(CircuitRunStopView.class)
                .list();
    }

    /** Where the previous page of circuits stopped: a day and a route code. */
    record RunCursor(LocalDate runDate, String routeCode) {

        String encode() {
            return runDate + "_" + routeCode;
        }

        static RunCursor parse(String cursor) {
            if (cursor == null || cursor.isBlank()) {
                return null;
            }
            // The date comes first and never contains an underscore, so the
            // first one is the separator even when the route code has its own.
            int split = cursor.indexOf('_');
            try {
                return new RunCursor(LocalDate.parse(cursor.substring(0, split)),
                        cursor.substring(split + 1));
            } catch (DateTimeParseException | StringIndexOutOfBoundsException e) {
                throw new BusinessRuleViolationException("invalid-cursor",
                        "The cursor was not issued by this listing");
            }
        }
    }
}
