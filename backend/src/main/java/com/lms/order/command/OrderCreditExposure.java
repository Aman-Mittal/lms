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
package com.lms.order.command;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.lms.masterdata.api.OpenOrderDemandSource;
import com.lms.order.command.domain.OrderLine;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Serves {@link OpenOrderDemandSource}: a customer's open orders by
 * chargeable weight, for its credit exposure.
 *
 * <p>Weight comes from {@link OrderLine#chargeableWeightKg}, the figure the
 * order announces at validation, so the exposure and the order agree on what
 * was ordered. Open means accepted and not yet fulfilled or cancelled; the
 * partial index {@code sales_order_customer_open_idx} states exactly those
 * statuses.
 */
@Component
class OrderCreditExposure implements OpenOrderDemandSource {

    private final JdbcClient jdbc;

    OrderCreditExposure(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<OpenOrderDemand> openDemandOf(UUID customerPartnerId, UUID alsoCount) {
        List<OrderLine> lines = jdbc.sql("""
                        SELECT l.* FROM order_line l
                         WHERE l.order_id IN (
                               SELECT o.id FROM sales_order o
                                WHERE o.customer_partner_id = :customer
                                  AND o.status IN ('VALIDATED', 'PARTIALLY_PLANNED', 'FULLY_PLANNED')
                               UNION
                               SELECT o.id FROM sales_order o
                                WHERE o.id = CAST(:alsoCount AS uuid)
                                  AND o.customer_partner_id = :customer
                                  AND o.status NOT IN ('CANCELLED', 'FULFILLED'))
                         ORDER BY l.order_id, l.line_no
                        """)
                .param("customer", customerPartnerId)
                .param("alsoCount", alsoCount)
                .query(OrderLine.class)
                .list();
        Map<UUID, BigDecimal> byOrder = new LinkedHashMap<>();
        for (OrderLine line : lines) {
            byOrder.merge(line.orderId(), line.chargeableWeightKg(), BigDecimal::add);
        }
        return byOrder.entrySet().stream()
                .map(entry -> new OpenOrderDemand(entry.getKey(), entry.getValue()))
                .toList();
    }
}
