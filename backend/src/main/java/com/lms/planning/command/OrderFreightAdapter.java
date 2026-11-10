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
package com.lms.planning.command;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import com.lms.order.api.OrderFreightProbe;
import com.lms.shared.tenant.TenantContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Tells the order module what has become of the freight cut from each line of
 * an order, so that a close can be refused or allowed on it.
 *
 * <p>One statement however many consignments: the order's consignments
 * ({@code consignment_order_idx}), their lines (the {@code consignment_line}
 * primary key) and, for those not yet moving, whether a load they are on has
 * left. A consignment counts as waiting while it is PLANNED, or ASSIGNED_TO_LOAD
 * on a load that is not dispatched -- a full truckload dispatched before dispatch
 * moved its consignments still reads ASSIGNED_TO_LOAD, and its load says it left.
 */
@Service
class OrderFreightAdapter implements OrderFreightProbe {

    private final JdbcClient jdbc;

    OrderFreightAdapter(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, LineFreight> freightByLine(UUID orderId) {
        Map<UUID, LineFreight> byLine = new LinkedHashMap<>();
        record Row(UUID lineId, LineFreight freight) {
        }
        jdbc.sql("""
                        SELECT cl.order_line_id,
                               count(*) FILTER (WHERE c.status NOT IN ('CANCELLED', 'SPLIT'))::int AS live,
                               count(*) FILTER (WHERE c.status = 'DELIVERED')::int AS delivered,
                               coalesce(sum(c.total_dead_weight_kg)
                                        FILTER (WHERE c.status NOT IN ('CANCELLED', 'SPLIT')), 0) AS live_kg,
                               coalesce(sum(c.total_dead_weight_kg)
                                        FILTER (WHERE c.status = 'DELIVERED'), 0) AS delivered_kg,
                               count(*) FILTER (WHERE c.status = 'PLANNED'
                                   OR (c.status = 'ASSIGNED_TO_LOAD' AND NOT EXISTS (
                                        SELECT 1 FROM load_consignment lc
                                          JOIN load_unit l ON l.id = lc.load_id
                                         WHERE lc.tenant_id = :tenantId AND lc.consignment_id = c.id
                                           AND l.status IN ('DISPATCHED', 'COMPLETED'))))::int AS waiting
                          FROM consignment c
                          JOIN consignment_line cl
                            ON cl.tenant_id = :tenantId AND cl.consignment_id = c.id
                         WHERE c.tenant_id = :tenantId AND c.order_id = :orderId
                         GROUP BY cl.order_line_id
                        """)
                .param("tenantId", TenantContext.requireTenantId())
                .param("orderId", orderId)
                .query((rs, n) -> new Row(rs.getObject("order_line_id", UUID.class),
                        new LineFreight(rs.getInt("live"), rs.getInt("delivered"), rs.getInt("waiting"),
                                rs.getBigDecimal("live_kg"), rs.getBigDecimal("delivered_kg"))))
                .list()
                .forEach(row -> byLine.put(row.lineId(), row.freight()));
        return byLine;
    }
}
