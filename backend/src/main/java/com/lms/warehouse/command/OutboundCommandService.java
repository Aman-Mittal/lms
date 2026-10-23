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
 * decision for whoever reads the shortfall, not for the picker. The remainder
 * stays owed as a back-order, which a later pick fills into a further
 * handling unit.
 */
@Service
public class OutboundCommandService {

    private final OutboundOrderRepository orders;
    private final OutboundLineRepository lines;
    private final HandlingUnitRepository handlingUnits;
    private final SkuRepository skus;
    private final StockLedger ledger;
    private final Warehouses warehouses;
    private final OrderPicker picker;
    private final WaveMembers waveMembers;
    private final ApplicationEventPublisher events;

    public OutboundCommandService(OutboundOrderRepository orders, OutboundLineRepository lines,
                                  HandlingUnitRepository handlingUnits, SkuRepository skus,
                                  StockLedger ledger, Warehouses warehouses, OrderPicker picker,
                                  WaveMembers waveMembers, ApplicationEventPublisher events) {
        this.orders = orders;
        this.lines = lines;
        this.handlingUnits = handlingUnits;
        this.skus = skus;
        this.ledger = ledger;
        this.warehouses = warehouses;
        this.picker = picker;
        this.waveMembers = waveMembers;
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
     * Picks what the order is still owed from the bins, by bin code then lot,
     * into its outbound staging: every line on a first pick, the back-order
     * on a later one.
     *
     * <p>Refused while the order waits in a released wave -- the wave will
     * pick it -- and, for an order already picked, when no bin holds anything
     * it is owed.
     */
    @Transactional
    @PreAuthorize("hasAuthority('WAREHOUSE_OPERATE')")
    public void pick(UUID orderId) {
        OutboundOrder order = requireOrder(orderId);
        order.requirePickable();
        waveMembers.awaitingWave(orderId).ifPresent(waveNo -> {
            throw new BusinessRuleViolationException("outbound-order-in-wave",
                    "Outbound order " + order.orderNo() + " is waiting in wave " + waveNo
                            + "; pick the wave instead");
        });
        picker.pick(order.warehouseId(), List.of(order), null, true);
    }

    /**
     * Cancels the remainder a back-ordered order is still owed. Refused while
     * the order waits in a released wave -- the wave would pick it.
     */
    @Transactional
    @PreAuthorize("hasAuthority('WAREHOUSE_OPERATE')")
    public void cancelBackOrder(UUID orderId) {
        OutboundOrder order = requireOrder(orderId);
        waveMembers.awaitingWave(orderId).ifPresent(waveNo -> {
            throw new BusinessRuleViolationException("outbound-order-in-wave",
                    "Outbound order " + order.orderNo() + " is waiting in wave " + waveNo
                            + "; its remainder cannot be cancelled until the wave is picked");
        });
        orders.save(order.backOrderCancelled(Instant.now()));
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
        dispatch(handlingUnitId, reference, null);
    }

    /**
     * Hands a packed unit over to transport, saying where it goes.
     *
     * <p>With a {@code handOff}, the published event carries the destination,
     * consignee and weighed gross, and planning turns the unit into a
     * consignment ready to be put on a load. Without one the unit simply
     * leaves, as before. The destination is checked against master data here,
     * synchronously, because a foreign key does not consult row-level security
     * and a bad address found later by a listener has nobody to tell.
     */
    @Transactional
    @PreAuthorize("hasAuthority('WAREHOUSE_OPERATE')")
    public void dispatch(UUID handlingUnitId, String reference, HandlingUnit.HandOff handOff) {
        UUID tenantId = TenantContext.requireTenantId();
        HandlingUnit unit = handlingUnits.findById(handlingUnitId)
                .orElseThrow(() -> new ResourceNotFoundException("Handling unit",
                        handlingUnitId));
        if (handOff != null) {
            warehouses.requireTerminal(handOff.destinationTerminalId());
        }
        Instant now = Instant.now();
        HandlingUnit dispatched = handlingUnits.save(unit.dispatch(reference, handOff, now));
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
            boolean backOrdered = lines.findByOrderIdOrderByLineNo(order.id()).stream()
                    .anyMatch(line -> line.shortQty() > 0);
            orders.save(order.dispatched(backOrdered, now));
            shipTo = order.shipTo();
        }
        events.publishEvent(new HandlingUnitDispatched(tenantId, dispatched.id(),
                dispatched.huNo(), dispatched.warehouseId(), dispatched.outboundOrderId(),
                dispatched.asnId(), shipTo, reference, contents, now,
                dispatched.destinationTerminalId(), dispatched.consigneePartnerId(),
                dispatched.grossWeightKg(), dispatched.volumeM3()));
    }

    private OutboundOrder requireOrder(UUID orderId) {
        return orders.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Outbound order", orderId));
    }
}
