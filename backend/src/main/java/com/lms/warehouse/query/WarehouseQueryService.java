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

import com.lms.identity.access.AccessSubject;
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

    /**
     * The portal-user predicate on goods, by who owns them. Null for the
     * tenant's own staff; a partner id confines stock, SKUs and their ledger
     * to that customer's own goods. Unowned SKUs -- the tenant's own stock --
     * are never theirs.
     */
    private static final String OWNER_SCOPE = """
               AND (CAST(:partner AS uuid) IS NULL OR s.owner_partner_id = CAST(:partner AS uuid))
            """;

    /**
     * The same rule for documents rather than goods: a portal user sees an
     * ASN or an outbound order only when one of its lines is a SKU their
     * partner owns, and a handling unit only when the order or ASN that
     * filled it is one they see.
     */
    private static String ownedLine(String lineTable, String joinsDocument) {
        return """
                EXISTS (SELECT 1 FROM %s l
                          JOIN sku s ON s.id = l.sku_id
                         WHERE %s
                           AND s.owner_partner_id = CAST(:partner AS uuid))
                """.formatted(lineTable, joinsDocument);
    }

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
                        SELECT s.id, s.code, s.description, s.owner_partner_id, s.uom
                          FROM sku s
                         WHERE (CAST(:after AS text) IS NULL OR s.code > CAST(:after AS text))
                        """ + OWNER_SCOPE + """
                         ORDER BY s.code
                         LIMIT :limit
                        """)
                .param("after", blankToNull(cursor))
                .param("partner", AccessSubject.current().partnerScope())
                .param("limit", pageSize + 1)
                .query(SkuView.class)
                .list();
        return Slice.of(rows, pageSize, SkuView::code);
    }

    // ---------------------------------------------------------------- inbound

    /**
     * A warehouse's ASNs, newest first, optionally narrowed to one status.
     *
     * @param status optional; null means every status
     * @param cursor the {@code nextCursor} of the previous page
     */
    public Slice<AsnSummaryView> asns(UUID warehouseId, String status, String cursor,
                                      Integer limit) {
        int pageSize = Slice.clampLimit(limit);
        Keyset after = Keyset.parse(cursor);
        List<AsnSummaryView> rows = jdbc.sql("""
                        SELECT a.id, a.asn_no, a.warehouse_id, a.supplier_partner_id, a.status,
                               a.cross_dock, a.received_at, a.created_at
                          FROM asn a
                         WHERE a.warehouse_id = :warehouseId
                           AND (CAST(:status AS text) IS NULL OR a.status = CAST(:status AS text))
                           AND (CAST(:afterAt AS timestamptz) IS NULL
                                OR (a.created_at, a.id) < (CAST(:afterAt AS timestamptz),
                                                           CAST(:afterId AS uuid)))
                           AND (CAST(:partner AS uuid) IS NULL OR
                        """ + ownedLine("asn_line",
                        "l.tenant_id = a.tenant_id AND l.asn_id = a.id") + """
                               )
                         ORDER BY a.created_at DESC, a.id DESC
                         LIMIT :limit
                        """)
                .param("warehouseId", warehouseId)
                .param("partner", AccessSubject.current().partnerScope())
                .param("status", blankToNull(status))
                .param("afterAt", after == null ? null : after.at())
                .param("afterId", after == null ? null : after.id())
                .param("limit", pageSize + 1)
                .query(AsnSummaryView.class)
                .list();
        return Slice.of(rows, pageSize, view -> Keyset.of(view.createdAt(), view.id()).encode());
    }

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

    /**
     * A warehouse's outbound orders, newest first, optionally narrowed to one
     * status -- {@code BACK_ORDERED} is the list of orders waiting for stock.
     */
    public Slice<OutboundOrderSummaryView> outboundOrders(UUID warehouseId, String status,
                                                          String cursor, Integer limit) {
        int pageSize = Slice.clampLimit(limit);
        Keyset after = Keyset.parse(cursor);
        List<OutboundOrderSummaryView> rows = jdbc.sql("""
                        SELECT o.id, o.order_no, o.warehouse_id, o.ship_to, o.status,
                               t.ordered_units, t.picked_units,
                               t.ordered_units - t.picked_units AS back_ordered_units,
                               o.created_at
                          FROM outbound_order o
                         CROSS JOIN LATERAL (
                               SELECT coalesce(sum(l.ordered_qty), 0)::int AS ordered_units,
                                      coalesce(sum(l.picked_qty), 0)::int AS picked_units
                                 FROM outbound_line l
                                WHERE l.tenant_id = o.tenant_id
                                  AND l.order_id = o.id) t
                         WHERE o.warehouse_id = :warehouseId
                           AND (CAST(:status AS text) IS NULL OR o.status = CAST(:status AS text))
                           AND (CAST(:afterAt AS timestamptz) IS NULL
                                OR (o.created_at, o.id) < (CAST(:afterAt AS timestamptz),
                                                           CAST(:afterId AS uuid)))
                           AND (CAST(:partner AS uuid) IS NULL OR
                        """ + ownedLine("outbound_line",
                        "l.tenant_id = o.tenant_id AND l.order_id = o.id") + """
                               )
                         ORDER BY o.created_at DESC, o.id DESC
                         LIMIT :limit
                        """)
                .param("warehouseId", warehouseId)
                .param("partner", AccessSubject.current().partnerScope())
                .param("status", blankToNull(status))
                .param("afterAt", after == null ? null : after.at())
                .param("afterId", after == null ? null : after.id())
                .param("limit", pageSize + 1)
                .query(OutboundOrderSummaryView.class)
                .list();
        return Slice.of(rows, pageSize, view -> Keyset.of(view.createdAt(), view.id()).encode());
    }

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

    /** A warehouse's handling units, newest first, optionally narrowed to one status. */
    public Slice<HandlingUnitSummaryView> handlingUnits(UUID warehouseId, String status,
                                                        String cursor, Integer limit) {
        int pageSize = Slice.clampLimit(limit);
        Keyset after = Keyset.parse(cursor);
        List<HandlingUnitSummaryView> rows = jdbc.sql("""
                        SELECT h.id, h.hu_no, h.warehouse_id, h.source, h.outbound_order_id,
                               h.asn_id, h.status, h.dispatch_ref, h.dispatched_at, h.created_at
                          FROM handling_unit h
                         WHERE h.warehouse_id = :warehouseId
                           AND (CAST(:status AS text) IS NULL OR h.status = CAST(:status AS text))
                           AND (CAST(:afterAt AS timestamptz) IS NULL
                                OR (h.created_at, h.id) < (CAST(:afterAt AS timestamptz),
                                                           CAST(:afterId AS uuid)))
                           AND (CAST(:partner AS uuid) IS NULL OR
                        """ + ownedLine("outbound_line",
                        "l.tenant_id = h.tenant_id AND l.order_id = h.outbound_order_id")
                        + " OR " + ownedLine("asn_line",
                        "l.tenant_id = h.tenant_id AND l.asn_id = h.asn_id") + """
                               )
                         ORDER BY h.created_at DESC, h.id DESC
                         LIMIT :limit
                        """)
                .param("warehouseId", warehouseId)
                .param("partner", AccessSubject.current().partnerScope())
                .param("status", blankToNull(status))
                .param("afterAt", after == null ? null : after.at())
                .param("afterId", after == null ? null : after.id())
                .param("limit", pageSize + 1)
                .query(HandlingUnitSummaryView.class)
                .list();
        return Slice.of(rows, pageSize, view -> Keyset.of(view.createdAt(), view.id()).encode());
    }

    // ------------------------------------------------------------ pick waves

    public Optional<PickWaveView> pickWave(UUID id) {
        return jdbc.sql("""
                        SELECT id, wave_no, warehouse_id, status, picked_by, picked_at, created_at
                          FROM pick_wave
                         WHERE id = :id
                        """)
                .param("id", id)
                .query((rs, n) -> new PickWaveView(
                        rs.getObject("id", UUID.class),
                        rs.getString("wave_no"),
                        rs.getObject("warehouse_id", UUID.class),
                        rs.getString("status"),
                        rs.getString("picked_by"),
                        instant(rs.getObject("picked_at", OffsetDateTime.class)),
                        instant(rs.getObject("created_at", OffsetDateTime.class)),
                        null))
                .optional()
                .map(header -> new PickWaveView(header.id(), header.waveNo(),
                        header.warehouseId(), header.status(), header.pickedBy(),
                        header.pickedAt(), header.createdAt(), waveOrders(id)));
    }

    private List<PickWaveView.Order> waveOrders(UUID waveId) {
        return jdbc.sql("""
                        SELECT m.seq, o.id AS order_id, o.order_no, o.status,
                               t.ordered_units, t.picked_units,
                               t.ordered_units - t.picked_units AS short_units
                          FROM pick_wave_order m
                          JOIN outbound_order o ON o.id = m.order_id
                         CROSS JOIN LATERAL (
                               SELECT coalesce(sum(l.ordered_qty), 0)::int AS ordered_units,
                                      coalesce(sum(l.picked_qty), 0)::int AS picked_units
                                 FROM outbound_line l
                                WHERE l.tenant_id = o.tenant_id
                                  AND l.order_id = o.id) t
                         WHERE m.wave_id = :waveId
                         ORDER BY m.seq
                        """)
                .param("waveId", waveId)
                .query(PickWaveView.Order.class)
                .list();
    }

    public Optional<HandlingUnitView> handlingUnit(UUID id) {
        return jdbc.sql("""
                        SELECT id, hu_no, warehouse_id, source, outbound_order_id, asn_id, status,
                               dispatch_ref, dispatched_at, destination_terminal_id,
                               consignee_partner_id, gross_weight_kg, volume_m3
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
                        rs.getObject("destination_terminal_id", UUID.class),
                        rs.getObject("consignee_partner_id", UUID.class),
                        rs.getBigDecimal("gross_weight_kg"),
                        rs.getBigDecimal("volume_m3"),
                        null))
                .optional()
                .map(header -> new HandlingUnitView(header.id(), header.huNo(),
                        header.warehouseId(), header.source(), header.outboundOrderId(),
                        header.asnId(), header.status(), header.dispatchRef(),
                        header.dispatchedAt(), header.destinationTerminalId(),
                        header.consigneePartnerId(), header.grossWeightKg(), header.volumeM3(),
                        unitContents(id)));
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
                        """ + OWNER_SCOPE + """
                         ORDER BY b.id
                         LIMIT :limit
                        """)
                .param("warehouseId", warehouseId)
                .param("partner", AccessSubject.current().partnerScope())
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
        Keyset after = Keyset.parse(cursor);
        List<StockMovementView> rows = jdbc.sql("""
                        SELECT id, warehouse_id, movement_type, location_type, location_id,
                               sku_id, lot_no, quantity, reference_id, reason, actor, occurred_at
                          FROM stock_movement
                         WHERE sku_id = :skuId
                           AND EXISTS (SELECT 1 FROM sku s
                                        WHERE s.id = stock_movement.sku_id
                        """ + OWNER_SCOPE + """
                                      )
                           AND (CAST(:afterAt AS timestamptz) IS NULL
                                OR (occurred_at, id) < (CAST(:afterAt AS timestamptz),
                                                        CAST(:afterId AS uuid)))
                         ORDER BY occurred_at DESC, id DESC
                         LIMIT :limit
                        """)
                .param("skuId", skuId)
                .param("partner", AccessSubject.current().partnerScope())
                .param("afterAt", after == null ? null : after.at())
                .param("afterId", after == null ? null : after.id())
                .param("limit", pageSize + 1)
                .query(StockMovementView.class)
                .list();
        return Slice.of(rows, pageSize, view -> Keyset.of(view.occurredAt(), view.id()).encode());
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

    /** A cursor on a (timestamp, id) sort: the movement ledger, and the newest-first listings. */
    record Keyset(OffsetDateTime at, UUID id) {

        static Keyset of(Instant at, UUID id) {
            return new Keyset(at.atOffset(ZoneOffset.UTC), id);
        }

        String encode() {
            return at.toInstant() + "_" + id;
        }

        static Keyset parse(String cursor) {
            if (cursor == null || cursor.isBlank()) {
                return null;
            }
            int split = cursor.lastIndexOf('_');
            try {
                return new Keyset(
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
