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
import java.util.Map;
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

    /**
     * Puts several consignments on a load in one statement, at consecutive
     * drop positions from {@code firstDropSequence} in the order given.
     *
     * <p>The same unique constraint as {@link #linkToLoad} guards it, and a
     * violation by any one of them aborts the whole insert: the batch boards
     * together or not at all.
     */
    public void linkAllToLoad(UUID tenantId, UUID loadId, List<UUID> consignmentIds,
                              int firstDropSequence, LoadUnit.LtlLeg leg) {
        if (consignmentIds.isEmpty()) {
            return;
        }
        jdbc.sql("""
                        INSERT INTO load_consignment
                               (tenant_id, load_id, consignment_id, drop_sequence, leg)
                        SELECT :tenantId, :loadId, boarding.id,
                               CAST(:first + boarding.position - 1 AS integer), :leg
                          FROM unnest(CAST(ARRAY[:ids] AS uuid[]))
                               WITH ORDINALITY AS boarding (id, position)
                        """)
                .param("tenantId", tenantId)
                .param("loadId", loadId)
                .param("ids", consignmentIds)
                .param("first", firstDropSequence)
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

    /**
     * Numbers what is left on a load 1, 2, 3... in the order it was in, so a
     * consignment taken off leaves no gap in the drops. One statement, served
     * by {@code load_consignment_load_idx}.
     */
    public void closeUpDropSequence(UUID tenantId, UUID loadId) {
        jdbc.sql("""
                        UPDATE load_consignment lc
                           SET drop_sequence = renumbered.position
                          FROM (SELECT consignment_id,
                                       CAST(row_number() OVER (ORDER BY drop_sequence) AS integer)
                                           AS position
                                  FROM load_consignment
                                 WHERE tenant_id = :tenantId AND load_id = :loadId) renumbered
                         WHERE lc.tenant_id = :tenantId AND lc.load_id = :loadId
                           AND lc.consignment_id = renumbered.consignment_id
                           AND lc.drop_sequence <> renumbered.position
                        """)
                .param("tenantId", tenantId)
                .param("loadId", loadId)
                .update();
    }

    /** Takes everything off one load, in one statement. */
    public void unlinkLoad(UUID tenantId, UUID loadId) {
        jdbc.sql("""
                        DELETE FROM load_consignment
                         WHERE tenant_id = :tenantId AND load_id = :loadId
                        """)
                .param("tenantId", tenantId)
                .param("loadId", loadId)
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

    /**
     * Every load placement of one consignment: none, or one per leg it has
     * been put on. Served by the unique {@code (consignment_id, leg)} index on
     * {@code load_consignment}.
     */
    public List<Placement> placementsOf(UUID tenantId, UUID consignmentId) {
        return jdbc.sql("""
                        SELECT lc.consignment_id, lc.load_id, l.status
                          FROM load_consignment lc
                          JOIN load_unit l ON l.id = lc.load_id
                         WHERE lc.tenant_id = :tenantId AND lc.consignment_id = :consignmentId
                        """)
                .param("tenantId", tenantId)
                .param("consignmentId", consignmentId)
                .query((rs, n) -> new Placement(rs.getObject("consignment_id", UUID.class),
                        rs.getObject("load_id", UUID.class), rs.getString("status")))
                .list();
    }

    /** The order lines a consignment was cut from. Served by the primary key. */
    public List<UUID> orderLinesOf(UUID tenantId, UUID consignmentId) {
        return jdbc.sql("""
                        SELECT order_line_id FROM consignment_line
                         WHERE tenant_id = :tenantId AND consignment_id = :consignmentId
                        """)
                .param("tenantId", tenantId)
                .param("consignmentId", consignmentId)
                .query(UUID.class)
                .list();
    }

    /** The consignments an order line is on, in any state. Served by {@code consignment_line_order_line_idx}. */
    public List<UUID> consignmentsOfLine(UUID tenantId, UUID orderLineId) {
        return jdbc.sql("""
                        SELECT consignment_id FROM consignment_line
                         WHERE tenant_id = :tenantId AND order_line_id = :orderLineId
                        """)
                .param("tenantId", tenantId)
                .param("orderLineId", orderLineId)
                .query(UUID.class)
                .list();
    }

    /**
     * Frees the order lines a cancelled consignment was cut from.
     *
     * <p>A cancelled consignment no longer holds demand. Its lines go back to
     * the order to be planned again, and the link would otherwise keep the
     * order from being amended -- an amendment replaces the lines, and the
     * link's foreign key refuses to let a line it names go.
     */
    public void unlinkLines(UUID tenantId, UUID consignmentId) {
        jdbc.sql("""
                        DELETE FROM consignment_line
                         WHERE tenant_id = :tenantId AND consignment_id = :consignmentId
                        """)
                .param("tenantId", tenantId)
                .param("consignmentId", consignmentId)
                .update();
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
     * Links every part of a split consignment to the order lines the whole was
     * cut from, in one statement: a part may hold some of any of them.
     *
     * <p>Served by the primary key on {@code consignment_line} and
     * {@code consignment_parent_idx}.
     */
    public void linkPartsToLinesOf(UUID tenantId, UUID parentId) {
        jdbc.sql("""
                        INSERT INTO consignment_line (tenant_id, consignment_id, order_line_id)
                        SELECT :tenantId, part.id, line.order_line_id
                          FROM consignment part
                          JOIN consignment_line line
                            ON line.tenant_id = :tenantId AND line.consignment_id = :parentId
                         WHERE part.tenant_id = :tenantId AND part.parent_consignment_id = :parentId
                        ON CONFLICT DO NOTHING
                        """)
                .param("tenantId", tenantId)
                .param("parentId", parentId)
                .update();
    }

    /**
     * Of these orders, the ones whose consignments have all been delivered or
     * cancelled and at least one delivered: nothing is left to carry.
     *
     * <p>A split consignment is neither: it carries nothing itself, and is
     * left out, so its order closes when its parts have been delivered.
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
                        HAVING count(*) FILTER (WHERE status NOT IN ('DELIVERED', 'CANCELLED', 'SPLIT')) = 0
                           AND count(*) FILTER (WHERE status = 'DELIVERED') > 0
                        """)
                .param("tenantId", tenantId)
                .param("orderIds", orderIds)
                .query(UUID.class)
                .list();
    }

    /** Whether a completed trip carries the consignment, which delivers it whatever its proof says. */
    public boolean carriedByCompletedTrip(UUID tenantId, UUID consignmentId) {
        return Boolean.TRUE.equals(jdbc.sql("""
                        SELECT EXISTS (SELECT 1
                                         FROM load_consignment lc
                                         JOIN trip t ON t.load_id = lc.load_id
                                        WHERE lc.tenant_id = :tenantId AND lc.consignment_id = :consignmentId
                                          AND t.status = 'COMPLETED')
                        """)
                .param("tenantId", tenantId)
                .param("consignmentId", consignmentId)
                .query(Boolean.class)
                .single());
    }

    /**
     * Per customer aboard, the contract family its orders were raised under,
     * for the customers whose orders aboard all name the same one; a customer
     * with an order under no contract, or under two, is absent.
     */
    public Map<UUID, UUID> contractFamiliesOnLoad(UUID tenantId, UUID loadId) {
        Map<UUID, UUID> families = new java.util.LinkedHashMap<>();
        jdbc.sql("""
                        SELECT o.customer_partner_id AS customer, min(o.contract_family_id::text)::uuid AS family
                          FROM load_consignment lc
                          JOIN consignment c ON c.id = lc.consignment_id
                          JOIN sales_order o ON o.id = c.order_id
                         WHERE lc.tenant_id = :tenantId
                           AND lc.load_id = :loadId
                         GROUP BY o.customer_partner_id
                        HAVING count(*) = count(o.contract_family_id)
                           AND count(DISTINCT o.contract_family_id) = 1
                        """)
                .param("tenantId", tenantId)
                .param("loadId", loadId)
                .query((rs, row) -> {
                    families.put(rs.getObject("customer", UUID.class), rs.getObject("family", UUID.class));
                    return null;
                })
                .list();
        return families;
    }

    /** The ordering customers of the consignments on a load, each once, by id. */
    public List<UUID> customersOnLoad(UUID tenantId, UUID loadId) {
        return jdbc.sql("""
                        SELECT DISTINCT o.customer_partner_id
                          FROM load_consignment lc
                          JOIN consignment c ON c.id = lc.consignment_id
                          JOIN sales_order o ON o.id = c.order_id
                         WHERE lc.tenant_id = :tenantId
                           AND lc.load_id = :loadId
                         ORDER BY o.customer_partner_id
                        """)
                .param("tenantId", tenantId)
                .param("loadId", loadId)
                .query(UUID.class)
                .list();
    }

    /** A load's freight mode by name; null when the tenant has no such load. Served by the primary key. */
    public String freightModeOf(UUID tenantId, UUID loadId) {
        return jdbc.sql("SELECT coalesce(freight_mode, 'FTL') FROM load_unit WHERE tenant_id = :tenantId AND id = :loadId")
                .param("tenantId", tenantId)
                .param("loadId", loadId)
                .query(String.class)
                .optional()
                .orElse(null);
    }
}
