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
package com.lms.clientbilling.query;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Facts about clients and movements that other modules own, read with SQL.
 *
 * <p>Reads across aggregates belong on the query side, and these are exactly
 * that: whether a partner is a customer (master data), and whose freight a
 * load carried (orders and planning). Reading them here keeps the module's
 * only code dependency the {@code TripCompleted} event.
 */
@Component
public class ClientDirectory {

    private final JdbcClient jdbc;

    public ClientDirectory(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** One client's freight on a load. */
    public record ClientShare(UUID clientPartnerId, BigDecimal chargeableWeightKg,
                              int consignmentCount, String orderNos) {
    }

    /** Whether the partner exists in this tenant and is a customer. */
    public boolean isCustomer(UUID partnerId) {
        return jdbc.sql("""
                        SELECT count(*) FROM business_partner
                         WHERE id = :id AND partner_type = 'CUSTOMER'
                        """)
                .param("id", partnerId)
                .query(Long.class)
                .single() > 0;
    }

    /**
     * Each customer's chargeable weight on a load, through the consignments
     * aboard and the orders they were generated from.
     *
     * <p>A load can carry several clients' freight -- that is the point of
     * consolidating it -- so this returns one row per client, and each is
     * billed for its own share.
     */
    public List<ClientShare> sharesOf(UUID loadId) {
        return jdbc.sql("""
                        SELECT so.customer_partner_id AS client_partner_id,
                               sum(c.chargeable_weight_kg) AS chargeable_weight_kg,
                               count(*)::int AS consignment_count,
                               string_agg(DISTINCT so.order_no, ', ') AS order_nos
                          FROM load_consignment lc
                          JOIN consignment c ON c.id = lc.consignment_id
                          JOIN sales_order so ON so.id = c.order_id
                         WHERE lc.load_id = :loadId
                         GROUP BY so.customer_partner_id
                         ORDER BY so.customer_partner_id
                        """)
                .param("loadId", loadId)
                .query(ClientShare.class)
                .list();
    }
}
