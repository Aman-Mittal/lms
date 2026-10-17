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
package com.lms.warehouse.query;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
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
 * Read side for warehousing.
 *
 * <p>Stock is read from the derived balances, never re-summed from the ledger
 * on a screen: the trigger that maintains them is what makes "what is in this
 * bin" a single index probe however long the history grows.
 */
@Service
@Transactional(readOnly = true)
@PreAuthorize("hasAuthority('WAREHOUSE_READ')")
public class WarehouseQueryService {

    private final JdbcClient jdbc;

    public WarehouseQueryService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // ------------------------------------------------------------ bins, SKUs

    /** A warehouse's bins by code, with how full each is. */
    public Slice<BinView> bins(UUID warehouseId, String cursor, Integer limit) {
        int pageSize = Slice.clampLimit(limit);
        List<BinView> rows = jdbc.sql("""
                        SELECT w.id, w.warehouse_id, w.code, w.capacity_units,
                               coalesce((SELECT sum(b.on_hand)
                                           FROM stock_balance b
                                          WHERE b.tenant_id = w.tenant_id
                                            AND b.location_type = 'BIN'
                                            AND b.location_id = w.id), 0)::int AS occupied_units
                          FROM warehouse_bin w
                         WHERE w.warehouse_id = :warehouseId
                           AND (CAST(:after AS text) IS NULL OR w.code > CAST(:after AS text))
                         ORDER BY w.code
                         LIMIT :limit
                        """)
                .param("warehouseId", warehouseId)
                .param("after", blankToNull(cursor))
                .param("limit", pageSize + 1)
                .query(BinView.class)
                .list();
        return Slice.of(rows, pageSize, BinView::code);
    }

    public Slice<SkuView> skus(String cursor, Integer limit) {
        int pageSize = Slice.clampLimit(limit);
        List<SkuView> rows = jdbc.sql("""
                        SELECT id, code, description, owner_partner_id, uom
                          FROM sku
                         WHERE (CAST(:after AS text) IS NULL OR code > CAST(:after AS text))
                         ORDER BY code
                         LIMIT :limit
                        """)
                .param("after", blankToNull(cursor))
                .param("limit", pageSize + 1)
                .query(SkuView.class)
                .list();
        return Slice.of(rows, pageSize, SkuView::code);
    }

    // ---------------------------------------------------------------- inbound

    public Optional<AsnView> asn(UUID id) {
        return jdbc.sql("""
                        SELECT a.id, a.asn_no, a.warehouse_id, a.supplier_partner_id, a.status,
                               a.cross_dock,
                               (SELECT h.id FROM handling_unit h
                                 WHERE h.tenant_id = a.tenant_id AND h.asn_id = a.id
                                 LIMIT 1) AS handling_unit_id,
                               a.received_by, a.received_at
                          FROM asn a
                         WHERE a.id = :id
                        """)
                .param("id", id)
                .query((rs, n) -> new AsnView(
                        rs.getObject("id", UUID.class),
                        rs.getString("asn_no"),
                        rs.getObject("warehouse_id", UUID.class),
                        rs.getObject("supplier_partner_id", UUID.class),
                        rs.getString("status"),
                        rs.getBoolean("cross_dock"),
                        rs.getObject("handling_unit_id", UUID.class),
                        rs.getString("received_by"),
                        instant(rs.getObject("received_at", OffsetDateTime.class)),
                        null))
                .optional()
                .map(header -> new AsnView(header.id(), header.asnNo(), header.warehouseId(),
                        header.supplierPartnerId(), header.status(), header.crossDock(),
                        header.handlingUnitId(), header.receivedBy(), header.receivedAt(),
                        asnLines(id)));
    }

    private List<AsnView.Line> asnLines(UUID asnId) {
        return jdbc.sql("""
                        SELECT l.line_no, l.sku_id, s.code AS sku_code, l.lot_no, l.expected_qty,
                               l.received_qty, l.damaged_qty,
                               CASE WHEN l.received_qty IS NULL THEN 0
                                    ELSE greatest(l.expected_qty - l.received_qty, 0)
                               END AS short_qty,
                               CASE WHEN l.received_qty IS NULL THEN 0
                                    ELSE greatest(l.received_qty - l.expected_qty, 0)
                               END AS excess_qty
                          FROM asn_line l
                          JOIN sku s ON s.id = l.sku_id
                         WHERE l.asn_id = :asnId
                         ORDER BY l.line_no
                        """)
                .param("asnId", asnId)
                .query(AsnView.Line.class)
                .list();
    }

    // --------------------------------------------------------------- outbound

    public Optional<OutboundOrderView> outboundOrder(UUID id) {
        return jdbc.sql("""
                        SELECT id, order_no, warehouse_id, ship_to, status, created_at
                          FROM outbound_order
                         WHERE id = :id
                        """)
                .param("id", id)
                .query((rs, n) -> new OutboundOrderView(
                        rs.getObject("id", UUID.class),
                        rs.getString("order_no"),
                        rs.getObject("warehouse_id", UUID.class),
                        rs.getString("ship_to"),
                        rs.getString("status"),
                        instant(rs.getObject("created_at", OffsetDateTime.class)),
                        null))
                .optional()
                .map(header -> new OutboundOrderView(header.id(), header.orderNo(),
                        header.warehouseId(), header.shipTo(), header.status(),
                        header.createdAt(), outboundLines(id)));
    }

    private List<OutboundOrderView.Line> outboundLines(UUID orderId) {
        return jdbc.sql("""
                        SELECT l.line_no, l.sku_id, s.code AS sku_code, l.ordered_qty,
                               l.picked_qty, l.ordered_qty - l.picked_qty AS short_qty
                          FROM outbound_line l
                          JOIN sku s ON s.id = l.sku_id
                         WHERE l.order_id = :orderId
                         ORDER BY l.line_no
                        """)
                .param("orderId", orderId)
                .query(OutboundOrderView.Line.class)
                .list();
    }

    public Optional<HandlingUnitView> handlingUnit(UUID id) {
        return jdbc.sql("""
                        SELECT id, hu_no, warehouse_id, source, outbound_order_id, asn_id, status,
                               dispatch_ref, dispatched_at
                          FROM handling_unit
                         WHERE id = :id
                        """)
                .param("id", id)
                .query((rs, n) -> new HandlingUnitView(
                        rs.getObject("id", UUID.class),
                        rs.getString("hu_no"),
                        rs.getObject("warehouse_id", UUID.class),
                        rs.getString("source"),
                        rs.getObject("outbound_order_id", UUID.class),
                        rs.getObject("asn_id", UUID.class),
                        rs.getString("status"),
                        rs.getString("dispatch_ref"),
                        instant(rs.getObject("dispatched_at", OffsetDateTime.class)),
                        null))
                .optional()
                .map(header -> new HandlingUnitView(header.id(), header.huNo(),
                        header.warehouseId(), header.source(), header.outboundOrderId(),
                        header.asnId(), header.status(), header.dispatchRef(),
                        header.dispatchedAt(), unitContents(id)));
    }

    private List<HandlingUnitView.Content> unitContents(UUID handlingUnitId) {
        return jdbc.sql("""
                        SELECT b.sku_id, s.code AS sku_code, b.lot_no, b.on_hand AS quantity
                          FROM stock_balance b
                          JOIN sku s ON s.id = b.sku_id
                         WHERE b.location_type = 'HU'
                           AND b.location_id = :id
                           AND b.on_hand > 0
                         ORDER BY s.code, b.lot_no
                        """)
                .param("id", handlingUnitId)
                .query(HandlingUnitView.Content.class)
                .list();
    }

    // ------------------------------------------------------------------ stock

    /**
     * What a warehouse holds, optionally narrowed to one SKU or one bin.
     * Empty balances -- stock that has moved on -- are left out.
     *
     * <p>Keyset on the balance id: arbitrary but unique and stable, which is
     * all a cursor needs.
     */
    public Slice<StockBalanceView> stock(UUID warehouseId, UUID skuId, UUID binId, String cursor,
                                         Integer limit) {
        int pageSize = Slice.clampLimit(limit);
        UUID after = parseUuidCursor(cursor);
        List<StockBalanceView> rows = jdbc.sql("""
                        SELECT b.id, b.warehouse_id, b.location_type, b.location_id,
                               w.code AS location_code, b.sku_id, s.code AS sku_code, b.lot_no,
                               b.on_hand
                          FROM stock_balance b
                          JOIN sku s ON s.id = b.sku_id
                          LEFT JOIN warehouse_bin w
                                 ON b.location_type = 'BIN' AND w.id = b.location_id
                         WHERE b.warehouse_id = :warehouseId
                           AND b.on_hand > 0
                           AND (CAST(:skuId AS uuid) IS NULL OR b.sku_id = CAST(:skuId AS uuid))
                           AND (CAST(:binId AS uuid) IS NULL
                                OR (b.location_type = 'BIN' AND b.location_id = CAST(:binId AS uuid)))
                           AND (CAST(:after AS uuid) IS NULL OR b.id > CAST(:after AS uuid))
                         ORDER BY b.id
                         LIMIT :limit
                        """)
                .param("warehouseId", warehouseId)
                .param("skuId", skuId)
                .param("binId", binId)
                .param("after", after)
                .param("limit", pageSize + 1)
                .query(StockBalanceView.class)
                .list();
        return Slice.of(rows, pageSize, view -> view.id().toString());
    }

    /** A SKU's movements, newest first: the ledger behind its balances. */
    public Slice<StockMovementView> movements(UUID skuId, String cursor, Integer limit) {
        int pageSize = Slice.clampLimit(limit);
        MovementCursor after = MovementCursor.parse(cursor);
        List<StockMovementView> rows = jdbc.sql("""
                        SELECT id, warehouse_id, movement_type, location_type, location_id,
                               sku_id, lot_no, quantity, reference_id, reason, actor, occurred_at
                          FROM stock_movement
                         WHERE sku_id = :skuId
                           AND (CAST(:afterAt AS timestamptz) IS NULL
                                OR (occurred_at, id) < (CAST(:afterAt AS timestamptz),
                                                        CAST(:afterId AS uuid)))
                         ORDER BY occurred_at DESC, id DESC
                         LIMIT :limit
                        """)
                .param("skuId", skuId)
                .param("afterAt", after == null ? null : after.occurredAt())
                .param("afterId", after == null ? null : after.id())
                .param("limit", pageSize + 1)
                .query(StockMovementView.class)
                .list();
        return Slice.of(rows, pageSize, view -> new MovementCursor(
                view.occurredAt().atOffset(ZoneOffset.UTC), view.id()).encode());
    }

    // ---------------------------------------------------------------- helpers

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static Instant instant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }

    private static UUID parseUuidCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(cursor);
        } catch (IllegalArgumentException e) {
            throw new BusinessRuleViolationException("invalid-cursor",
                    "The cursor was not issued by this listing");
        }
    }

    record MovementCursor(OffsetDateTime occurredAt, UUID id) {

        String encode() {
            return occurredAt.toInstant() + "_" + id;
        }

        static MovementCursor parse(String cursor) {
            if (cursor == null || cursor.isBlank()) {
                return null;
            }
            int split = cursor.lastIndexOf('_');
            try {
                return new MovementCursor(
                        Instant.parse(cursor.substring(0, split)).atOffset(ZoneOffset.UTC),
                        UUID.fromString(cursor.substring(split + 1)));
            } catch (DateTimeParseException | IllegalArgumentException
                     | StringIndexOutOfBoundsException e) {
                throw new BusinessRuleViolationException("invalid-cursor",
                        "The cursor was not issued by this listing");
            }
        }
    }
}
