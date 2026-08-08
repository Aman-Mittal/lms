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

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.lms.shared.query.Slice;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Read side for trip execution. */
@Service
@Transactional(readOnly = true)
public class TripQueryService {

    private static final String SELECT = """
            SELECT t.id, t.trip_no, t.status,
                   t.load_id, l.load_no,
                   vp.legal_name AS vendor_name,
                   v.registration_no AS vehicle_registration_no,
                   d.full_name AS driver_name,
                   ot.code AS origin_terminal_code,
                   dt.code AS destination_terminal_code,
                   t.planned_start_at, t.gate_in_at, t.dispatched_at,
                   t.arrived_at, t.completed_at,
                   t.tare_weight_kg, t.gross_weight_kg, t.payload_weight_kg,
                   l.planned_weight_kg,
                   -- Computed here rather than in Java so that a control screen
                   -- can sort and filter on dwell without reading every row.
                   CASE WHEN t.gate_in_at IS NOT NULL AND t.dispatched_at IS NOT NULL
                        THEN floor(extract(epoch FROM (t.dispatched_at - t.gate_in_at)) / 60)::bigint
                   END AS origin_dwell_minutes,
                   coalesce(docs.document_count, 0) AS document_count,
                   t.created_at
              FROM trip t
              JOIN load_unit l ON l.id = t.load_id
              JOIN terminal ot ON ot.id = t.origin_terminal_id
              LEFT JOIN terminal dt ON dt.id = t.destination_terminal_id
              LEFT JOIN business_partner vp ON vp.id = t.vendor_partner_id
              LEFT JOIN vehicle v ON v.id = t.vehicle_id
              LEFT JOIN driver d ON d.id = t.driver_id
              LEFT JOIN LATERAL (
                    SELECT count(*)::int AS document_count
                      FROM trip_document td
                     WHERE td.trip_id = t.id
                   ) docs ON true
            """;

    private final JdbcClient jdbc;

    public TripQueryService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<TripView> findById(UUID id) {
        return jdbc.sql(SELECT + " WHERE t.id = :id")
                .param("id", id)
                .query(TripView.class)
                .optional();
    }

    public Optional<TripView> findByTripNo(String tripNo) {
        return jdbc.sql(SELECT + " WHERE t.trip_no = :tripNo")
                .param("tripNo", tripNo)
                .query(TripView.class)
                .optional();
    }

    /** A page of trips, keyed on the trip number, which is unique per tenant. */
    public Slice<TripView> list(String status, String cursor, Integer limit) {
        int pageSize = Slice.clampLimit(limit);

        List<TripView> rows = jdbc.sql(SELECT + """
                         WHERE (CAST(:status AS text) IS NULL OR t.status = CAST(:status AS text))
                           AND (CAST(:cursor AS text) IS NULL OR t.trip_no > CAST(:cursor AS text))
                         ORDER BY t.trip_no
                         LIMIT :limit
                        """)
                .param("status", status)
                .param("cursor", cursor)
                .param("limit", pageSize + 1)
                .query(TripView.class)
                .list();

        return Slice.of(rows, pageSize, TripView::tripNo);
    }

    /** The gate log for one trip: the evidence in a detention dispute. */
    public List<GateEventView> findGateLog(UUID tripId) {
        return jdbc.sql("""
                        SELECT g.id, g.event_type, tm.code AS terminal_code,
                               g.occurred_at, g.remarks
                          FROM gate_event g
                          JOIN terminal tm ON tm.id = g.terminal_id
                         WHERE g.trip_id = :tripId
                         ORDER BY g.occurred_at
                        """)
                .param("tripId", tripId)
                .query(GateEventView.class)
                .list();
    }
}
