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

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import com.lms.planning.command.domain.LoadUnit;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Writes to the two join tables that carry no state of their own.
 *
 * <p>{@code consignment_line} and {@code load_consignment} are pure
 * many-to-many links with composite keys and no surrogate id. Spring Data JDBC
 * wants an {@code @Id} per aggregate, so modelling these as repositories would
 * mean inventing an identifier that nothing uses. Plain SQL through
 * {@link JdbcClient} is the honest representation.
 *
 * <p>Row-level security still applies: every statement here runs inside the
 * caller's transaction, so the tenant scope is already on the connection and
 * the {@code WITH CHECK} clause rejects a row written under the wrong scope.
 */
@Component
public class PlanningLinks {

    private final JdbcClient jdbc;

    public PlanningLinks(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void linkOrderLine(UUID tenantId, UUID consignmentId, UUID orderLineId) {
        jdbc.sql("""
                        INSERT INTO consignment_line (tenant_id, consignment_id, order_line_id)
                        VALUES (:tenantId, :consignmentId, :orderLineId)
                        ON CONFLICT DO NOTHING
                        """)
                .param("tenantId", tenantId)
                .param("consignmentId", consignmentId)
                .param("orderLineId", orderLineId)
                .update();
    }

    /**
     * Puts a consignment on a load at the next drop position.
     *
     * <p>No {@code ON CONFLICT DO NOTHING} here, deliberately. The unique
     * constraint on {@code (consignment_id, leg)} is what stops a consignment
     * being booked onto two loads for the same leg, and swallowing that
     * violation would turn double-booked freight into a silent no-op that the
     * operator reads as success.
     *
     * @param leg the load's leg, copied onto the link so that a docket may be
     *            on one line haul and one delivery load, never two of either
     */
    public void linkToLoad(UUID tenantId, UUID loadId, UUID consignmentId, int dropSequence,
                           LoadUnit.LtlLeg leg) {
        jdbc.sql("""
                        INSERT INTO load_consignment
                               (tenant_id, load_id, consignment_id, drop_sequence, leg)
                        VALUES (:tenantId, :loadId, :consignmentId, :dropSequence, :leg)
                        """)
                .param("tenantId", tenantId)
                .param("loadId", loadId)
                .param("consignmentId", consignmentId)
                .param("dropSequence", dropSequence)
                .param("leg", leg.name())
                .update();
    }

    /** Served by {@code load_consignment_load_idx}. */
    public int nextDropSequence(UUID tenantId, UUID loadId) {
        Integer highest = jdbc.sql("""
                        SELECT max(drop_sequence) FROM load_consignment
                         WHERE tenant_id = :tenantId AND load_id = :loadId
                        """)
                .param("tenantId", tenantId)
                .param("loadId", loadId)
                .query(Integer.class)
                .optional()
                .orElse(null);
        return highest == null ? 1 : highest + 1;
    }

    /** Removes a consignment from a load, so it can be planned onto another. */
    public void unlinkFromLoad(UUID tenantId, UUID loadId, UUID consignmentId) {
        jdbc.sql("""
                        DELETE FROM load_consignment
                         WHERE tenant_id = :tenantId AND load_id = :loadId
                           AND consignment_id = :consignmentId
                        """)
                .param("tenantId", tenantId)
                .param("loadId", loadId)
                .param("consignmentId", consignmentId)
                .update();
    }

    /** Where a consignment sits: the load, and that load's status. */
    public record Placement(UUID consignmentId, UUID loadId, String loadStatus) {
    }

    /**
     * Every load placement of an order's consignments, in one statement.
     *
     * <p>Served by {@code consignment_order_idx} and the unique
     * {@code (consignment_id, leg)} index on {@code load_consignment}.
     */
    public List<Placement> placementsOfOrder(UUID tenantId, UUID orderId) {
        return jdbc.sql("""
                        SELECT lc.consignment_id, lc.load_id, l.status
                          FROM load_consignment lc
                          JOIN load_unit l ON l.id = lc.load_id
                         WHERE lc.tenant_id = :tenantId
                           AND lc.consignment_id IN (SELECT c.id FROM consignment c
                                                      WHERE c.tenant_id = :tenantId
                                                        AND c.order_id = :orderId)
                        """)
                .param("tenantId", tenantId)
                .param("orderId", orderId)
                .query((rs, n) -> new Placement(rs.getObject("consignment_id", UUID.class),
                        rs.getObject("load_id", UUID.class), rs.getString("status")))
                .list();
    }

    /** Takes consignments off whatever loads hold them, in one statement. */
    public void unlinkAll(UUID tenantId, Collection<UUID> consignmentIds) {
        if (consignmentIds.isEmpty()) {
            return;
        }
        jdbc.sql("""
                        DELETE FROM load_consignment
                         WHERE tenant_id = :tenantId AND consignment_id IN (:ids)
                        """)
                .param("tenantId", tenantId)
                .param("ids", consignmentIds)
                .update();
    }

    /**
     * Of these orders, the ones whose consignments have all been delivered or
     * cancelled and at least one delivered: nothing is left to carry.
     *
     * <p>Served by {@code consignment_order_idx}.
     */
    public List<UUID> orderIdsWithNothingLeftToCarry(UUID tenantId, Collection<UUID> orderIds) {
        if (orderIds.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("""
                        SELECT order_id FROM consignment
                         WHERE tenant_id = :tenantId AND order_id IN (:orderIds)
                         GROUP BY order_id
                        HAVING count(*) FILTER (WHERE status NOT IN ('DELIVERED', 'CANCELLED')) = 0
                           AND count(*) FILTER (WHERE status = 'DELIVERED') > 0
                        """)
                .param("tenantId", tenantId)
                .param("orderIds", orderIds)
                .query(UUID.class)
                .list();
    }
}
