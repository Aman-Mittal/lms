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
package com.lms.order.query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.lms.shared.query.Slice;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read side for demand.
 *
 * <p>The line aggregates -- count, planned count, total weight, hazmat flag --
 * are computed in a lateral subquery rather than by loading each order's lines.
 * One statement, one round trip, and the aggregation happens where the rows
 * already are instead of over a link to a 0.1-CPU instance.
 */
@Service
@Transactional(readOnly = true)
public class OrderQueryService {

    /**
     * Paginated by {@code order_no}, not by a timestamp.
     *
     * <p>Order numbers are unique per tenant and already carry a unique index,
     * so the cursor needs no tie-breaker column and the seek is an index range
     * scan on an index that exists. Ordering by creation time would read
     * better on screen but would need an index built solely to serve it.
     */
    private static final String SELECT = """
            SELECT o.id, o.order_no, o.status,
                   o.customer_partner_id, c.legal_name AS customer_name,
                   t.code AS origin_terminal_code,
                   o.requested_pickup_at, o.requested_delivery_at,
                   agg.line_count, agg.planned_line_count,
                   agg.total_dead_weight_kg, agg.contains_hazmat,
                   o.created_at
              FROM sales_order o
              JOIN business_partner c ON c.id = o.customer_partner_id
              LEFT JOIN terminal t ON t.id = o.origin_terminal_id
              LEFT JOIN LATERAL (
                    SELECT count(*)::int AS line_count,
                           count(*) FILTER (WHERE l.planned)::int AS planned_line_count,
                           coalesce(sum(l.dead_weight_kg), 0) AS total_dead_weight_kg,
                           bool_or(l.hazmat_un_code IS NOT NULL
                                   AND btrim(l.hazmat_un_code) <> '') AS contains_hazmat
                      FROM order_line l
                     WHERE l.order_id = o.id
                   ) agg ON true
            """;

    private final JdbcClient jdbc;

    public OrderQueryService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<OrderView> findById(UUID id) {
        return jdbc.sql(SELECT + " WHERE o.id = :id")
                .param("id", id)
                .query(OrderView.class)
                .optional();
    }

    /**
     * A page of orders, optionally narrowed to one status.
     *
     * @param status optional; null means every status
     * @param cursor the {@code order_no} of the last row of the previous page
     */
    public Slice<OrderView> list(String status, String cursor, Integer limit) {
        int pageSize = Slice.clampLimit(limit);

        // Both parameters are bound with an explicit cast. A bind used only
        // inside an IS NULL test gives the planner nothing to infer a type
        // from, and Postgres fails outright with "could not determine data type
        // of parameter" rather than guessing.
        List<OrderView> rows = jdbc.sql(SELECT + """
                         WHERE (CAST(:status AS text) IS NULL OR o.status = CAST(:status AS text))
                           AND (CAST(:cursor AS text) IS NULL OR o.order_no > CAST(:cursor AS text))
                         ORDER BY o.order_no
                         LIMIT :limit
                        """)
                .param("status", status)
                .param("cursor", cursor)
                // One more than asked for: that extra row is how "is there
                // another page" is answered without a second count query.
                .param("limit", pageSize + 1)
                .query(OrderView.class)
                .list();

        return Slice.of(rows, pageSize, OrderView::orderNo);
    }

    /** The lines of one order, in entry order. */
    public List<OrderLineView> findLines(UUID orderId) {
        return jdbc.sql("""
                        SELECT l.id, l.line_no, l.material_code, l.material_description,
                               l.material_class, l.hazmat_un_code,
                               l.quantity, l.uom, l.dead_weight_kg,
                               l.planned,
                               l.consignee_partner_id, cn.legal_name AS consignee_name,
                               l.destination_terminal_id, dt.code AS destination_terminal_code
                          FROM order_line l
                          JOIN business_partner cn ON cn.id = l.consignee_partner_id
                          JOIN terminal dt ON dt.id = l.destination_terminal_id
                         WHERE l.order_id = :orderId
                         ORDER BY l.line_no
                        """)
                .param("orderId", orderId)
                .query(OrderLineView.class)
                .list();
    }
}
