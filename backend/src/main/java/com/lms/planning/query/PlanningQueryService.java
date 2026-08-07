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
package com.lms.planning.query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.lms.shared.query.Slice;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read side for planning.
 *
 * <p>Utilisation is computed in SQL rather than in Java so that it can be
 * sorted and filtered on without reading every row -- "show me every load under
 * 60% utilised" is the question a planner actually asks, and answering it in
 * the application would mean fetching everything first.
 */
@Service
@Transactional(readOnly = true)
public class PlanningQueryService {

    private static final String LOAD_SELECT = """
            SELECT l.id, l.load_no, l.status,
                   t.code AS origin_terminal_code,
                   l.vehicle_type, v.registration_no AS vehicle_registration_no,
                   l.capacity_weight_kg, l.capacity_volume_m3,
                   l.planned_weight_kg, l.planned_volume_m3,
                   round(l.planned_weight_kg * 100 / l.capacity_weight_kg, 2) AS weight_utilisation_pct,
                   round(l.planned_volume_m3 * 100 / l.capacity_volume_m3, 2) AS volume_utilisation_pct,
                   coalesce(lc.consignment_count, 0) AS consignment_count,
                   l.requires_hazmat, l.created_at
              FROM load_unit l
              JOIN terminal t ON t.id = l.origin_terminal_id
              LEFT JOIN vehicle v ON v.id = l.vehicle_id
              LEFT JOIN LATERAL (
                    SELECT count(*)::int AS consignment_count
                      FROM load_consignment lcx
                     WHERE lcx.load_id = l.id
                   ) lc ON true
            """;

    private static final String CONSIGNMENT_SELECT = """
            SELECT c.id, c.tracking_ref, c.status,
                   c.order_id, o.order_no,
                   p.legal_name AS consignee_name,
                   t.code AS destination_terminal_code,
                   c.total_dead_weight_kg, c.total_volume_m3, c.chargeable_weight_kg,
                   c.contains_hazmat, c.material_classes,
                   l.load_no, c.created_at
              FROM consignment c
              JOIN sales_order o ON o.id = c.order_id
              JOIN business_partner p ON p.id = c.consignee_partner_id
              JOIN terminal t ON t.id = c.destination_terminal_id
              LEFT JOIN load_consignment lc ON lc.consignment_id = c.id
              LEFT JOIN load_unit l ON l.id = lc.load_id
            """;

    private final JdbcClient jdbc;

    public PlanningQueryService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<LoadView> findLoad(UUID id) {
        return jdbc.sql(LOAD_SELECT + " WHERE l.id = :id")
                .param("id", id)
                .query(LoadView.class)
                .optional();
    }

    /**
     * A page of loads, keyed on {@code load_no}.
     *
     * <p>Load numbers are unique per tenant and already carry a unique index,
     * so the cursor needs no tie-breaker and the seek is a range scan on an
     * index that exists rather than one added to serve the screen.
     */
    public Slice<LoadView> listLoads(String status, String cursor, Integer limit) {
        int pageSize = Slice.clampLimit(limit);

        List<LoadView> rows = jdbc.sql(LOAD_SELECT + """
                         WHERE (CAST(:status AS text) IS NULL OR l.status = CAST(:status AS text))
                           AND (CAST(:cursor AS text) IS NULL OR l.load_no > CAST(:cursor AS text))
                         ORDER BY l.load_no
                         LIMIT :limit
                        """)
                .param("status", status)
                .param("cursor", cursor)
                .param("limit", pageSize + 1)
                .query(LoadView.class)
                .list();

        return Slice.of(rows, pageSize, LoadView::loadNo);
    }

    public Optional<ConsignmentView> findConsignment(UUID id) {
        return jdbc.sql(CONSIGNMENT_SELECT + " WHERE c.id = :id")
                .param("id", id)
                .query(ConsignmentView.class)
                .optional();
    }

    /** Look-up by the reference a customer quotes down the phone. */
    public Optional<ConsignmentView> findByTrackingRef(String trackingRef) {
        return jdbc.sql(CONSIGNMENT_SELECT + " WHERE c.tracking_ref = :ref")
                .param("ref", trackingRef)
                .query(ConsignmentView.class)
                .optional();
    }

    public List<ConsignmentView> findConsignmentsOnLoad(UUID loadId) {
        return jdbc.sql(CONSIGNMENT_SELECT + " WHERE lc.load_id = :loadId ORDER BY lc.drop_sequence")
                .param("loadId", loadId)
                .query(ConsignmentView.class)
                .list();
    }

    /** Consignments not yet on a load: the planner's work queue. */
    public Slice<ConsignmentView> listUnassigned(String cursor, Integer limit) {
        int pageSize = Slice.clampLimit(limit);

        List<ConsignmentView> rows = jdbc.sql(CONSIGNMENT_SELECT + """
                         WHERE c.status = 'PLANNED'
                           AND lc.consignment_id IS NULL
                           AND (CAST(:cursor AS text) IS NULL
                                OR c.tracking_ref > CAST(:cursor AS text))
                         ORDER BY c.tracking_ref
                         LIMIT :limit
                        """)
                .param("cursor", cursor)
                .param("limit", pageSize + 1)
                .query(ConsignmentView.class)
                .list();

        return Slice.of(rows, pageSize, ConsignmentView::trackingRef);
    }
}
