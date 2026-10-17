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
package com.lms.warehouse.command;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.lms.shared.error.BusinessRuleViolationException;
import com.lms.shared.error.ResourceNotFoundException;
import com.lms.shared.tenant.TenantContext;
import com.lms.warehouse.command.domain.HandlingUnit;
import com.lms.warehouse.command.domain.LocationType;
import com.lms.warehouse.command.domain.MovementType;
import com.lms.warehouse.command.domain.OutboundLine;
import com.lms.warehouse.command.domain.OutboundOrder;
import com.lms.warehouse.command.domain.Sku;
import com.lms.warehouse.events.HandlingUnitDispatched;
import com.lms.warehouse.events.PickShortfallRecorded;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Goods out: order picking, packing into handling units, and the hand-off.
 *
 * <p>Picking takes what the bins hold and no more. A line that cannot be
 * filled is picked short, the shortfall is published, and the order carries
 * on with what was found -- holding a whole shipment for one missing SKU is a
 * decision for whoever reads the shortfall, not for the picker.
 */
@Service
public class OutboundCommandService {

    private final OutboundOrderRepository orders;
    private final OutboundLineRepository lines;
    private final HandlingUnitRepository handlingUnits;
    private final SkuRepository skus;
    private final StockLedger ledger;
    private final Warehouses warehouses;
    private final ApplicationEventPublisher events;

    public OutboundCommandService(OutboundOrderRepository orders, OutboundLineRepository lines,
                                  HandlingUnitRepository handlingUnits, SkuRepository skus,
                                  StockLedger ledger, Warehouses warehouses,
                                  ApplicationEventPublisher events) {
        this.orders = orders;
        this.lines = lines;
        this.handlingUnits = handlingUnits;
        this.skus = skus;
        this.ledger = ledger;
        this.warehouses = warehouses;
        this.events = events;
    }

    public record RequestedLine(UUID skuId, int quantity) {
    }

    @Transactional
    @PreAuthorize("hasAuthority('WAREHOUSE_OPERATE')")
    public UUID createOrder(UUID warehouseId, String orderNo, String shipTo,
                            List<RequestedLine> requested) {
        warehouses.requireWarehouse(warehouseId);
        if (requested == null || requested.isEmpty()) {
            throw new BusinessRuleViolationException("outbound-order-has-no-lines",
                    "An outbound order needs at least one line");
        }
        UUID tenantId = TenantContext.requireTenantId();
        OutboundOrder order = OutboundOrder.open(tenantId, warehouseId, orderNo, shipTo,
                Instant.now());
        if (orders.existsByOrderNo(order.orderNo())) {
            throw new BusinessRuleViolationException("outbound-order-number-taken",
                    "There is already an outbound order " + order.orderNo());
        }
        orders.save(order);
        int lineNo = 0;
        for (RequestedLine line : requested) {
            if (line.skuId() == null || !skus.existsById(line.skuId())) {
                throw new ResourceNotFoundException("SKU", line.skuId());
            }
            lines.save(OutboundLine.request(tenantId, order.id(), ++lineNo, line.skuId(),
                    line.quantity()));
        }
        return order.id();
    }

    /**
     * Picks every line from the bins, by bin code then lot, into the order's
     * outbound staging.
     */
    @Transactional
    @PreAuthorize("hasAuthority('WAREHOUSE_OPERATE')")
    public void pick(UUID orderId) {
        UUID tenantId = TenantContext.requireTenantId();
        OutboundOrder order = requireOrder(orderId);
        Instant now = Instant.now();
        String actor = Warehouses.currentActor();

        boolean shortfall = false;
        List<PickShortfallRecorded> shortfalls = new ArrayList<>();
        for (OutboundLine line : lines.findByOrderIdOrderByLineNo(orderId)) {
            int remaining = line.orderedQty();
            for (StockLedger.Holding holding : ledger.pickableStock(order.warehouseId(),
                    line.skuId())) {
                if (remaining == 0) {
                    break;
                }
                int take = Math.min(remaining, holding.onHand());
                ledger.transfer(order.warehouseId(), MovementType.PICK, LocationType.BIN,
                        holding.locationId(), LocationType.OUTBOUND, orderId, line.skuId(),
                        holding.lotNo(), take, orderId, actor, now);
                remaining -= take;
            }
            OutboundLine picked = lines.save(line.withPicked(line.orderedQty() - remaining));
            if (picked.shortQty() > 0) {
                shortfall = true;
                shortfalls.add(new PickShortfallRecorded(tenantId, orderId, order.orderNo(),
                        order.warehouseId(), picked.lineNo(), picked.skuId(),
                        picked.orderedQty(), picked.pickedQty(), picked.shortQty(), now));
            }
        }
        orders.save(order.picked(shortfall, now));
        shortfalls.forEach(events::publishEvent);
    }

    /** Packs everything picked for the order into one new handling unit. */
    @Transactional
    @PreAuthorize("hasAuthority('WAREHOUSE_OPERATE')")
    public UUID pack(UUID orderId) {
        UUID tenantId = TenantContext.requireTenantId();
        OutboundOrder order = requireOrder(orderId);
        Instant now = Instant.now();
        OutboundOrder packed = order.packed(now);

        List<StockLedger.Holding> picked = ledger.contentsOf(LocationType.OUTBOUND, orderId);
        if (picked.isEmpty()) {
            throw new BusinessRuleViolationException("nothing-picked",
                    "Nothing was picked for outbound order " + order.orderNo()
                            + ", so there is nothing to pack");
        }
        HandlingUnit unit = handlingUnits.save(HandlingUnit.packedFor(tenantId,
                order.warehouseId(), orderId, now));
        String actor = Warehouses.currentActor();
        for (StockLedger.Holding holding : picked) {
            ledger.transfer(order.warehouseId(), MovementType.PACK, LocationType.OUTBOUND,
                    orderId, LocationType.HU, unit.id(), holding.skuId(), holding.lotNo(),
                    holding.onHand(), unit.id(), actor, now);
        }
        orders.save(packed);
        return unit.id();
    }

    /**
     * Hands a packed unit over to transport. Its contents leave the ledger
     * and the hand-off is published with them.
     */
    @Transactional
    @PreAuthorize("hasAuthority('WAREHOUSE_OPERATE')")
    public void dispatch(UUID handlingUnitId, String reference) {
        UUID tenantId = TenantContext.requireTenantId();
        HandlingUnit unit = handlingUnits.findById(handlingUnitId)
                .orElseThrow(() -> new ResourceNotFoundException("Handling unit",
                        handlingUnitId));
        Instant now = Instant.now();
        HandlingUnit dispatched = handlingUnits.save(unit.dispatch(reference, now));
        String actor = Warehouses.currentActor();

        List<HandlingUnitDispatched.Content> contents = new ArrayList<>();
        for (StockLedger.Holding holding : ledger.contentsOf(LocationType.HU, handlingUnitId)) {
            ledger.post(unit.warehouseId(), MovementType.DISPATCH, LocationType.HU,
                    handlingUnitId, holding.skuId(), holding.lotNo(), -holding.onHand(),
                    handlingUnitId, reference, actor, now);
            String skuCode = skus.findById(holding.skuId()).map(Sku::code).orElse(null);
            contents.add(new HandlingUnitDispatched.Content(holding.skuId(), skuCode,
                    holding.lotNo(), holding.onHand()));
        }

        String shipTo = null;
        if (unit.outboundOrderId() != null) {
            OutboundOrder order = requireOrder(unit.outboundOrderId());
            orders.save(order.dispatched(now));
            shipTo = order.shipTo();
        }
        events.publishEvent(new HandlingUnitDispatched(tenantId, dispatched.id(),
                dispatched.huNo(), dispatched.warehouseId(), dispatched.outboundOrderId(),
                dispatched.asnId(), shipTo, reference, contents, now));
    }

    private OutboundOrder requireOrder(UUID orderId) {
        return orders.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Outbound order", orderId));
    }
}
