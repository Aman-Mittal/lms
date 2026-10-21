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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.lms.shared.error.BusinessRuleViolationException;
import com.lms.shared.tenant.TenantContext;
import com.lms.warehouse.command.domain.LocationType;
import com.lms.warehouse.command.domain.MovementType;
import com.lms.warehouse.command.domain.OutboundLine;
import com.lms.warehouse.command.domain.OutboundOrder;
import com.lms.warehouse.events.BackOrderFilled;
import com.lms.warehouse.events.PickShortfallRecorded;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * Picks a batch of outbound orders -- one order, or a whole wave -- from the
 * bins of one warehouse into each order's outbound staging.
 *
 * <p>What each line is still owed is allocated by {@link WaveAllocator}; every
 * take is then posted to the ledger as a PICK transfer from the bin to the
 * order. Picking a lone order is a wave of one, so the two can never disagree
 * about bin order or about what a shortfall is.
 *
 * <p>Events, per line: an order's first pick publishes a shortfall for every
 * line it leaves short; a later pick publishes a filled back-order for every
 * line it completes. Both name the wave when there was one.
 */
@Component
class OrderPicker {

    private final OutboundOrderRepository orders;
    private final OutboundLineRepository lines;
    private final StockLedger ledger;
    private final ApplicationEventPublisher events;

    OrderPicker(OutboundOrderRepository orders, OutboundLineRepository lines, StockLedger ledger,
                ApplicationEventPublisher events) {
        this.orders = orders;
        this.lines = lines;
        this.ledger = ledger;
        this.events = events;
    }

    /**
     * @param waveId                 the wave being picked, or null for a single order
     * @param refuseIfBackOrderStuck refuse, before anything moves, a pick of an
     *                               already-picked order that would find nothing
     *                               it is owed -- for a single order, where
     *                               saying so beats a pick that does nothing
     */
    void pick(UUID warehouseId, List<OutboundOrder> batch, UUID waveId,
              boolean refuseIfBackOrderStuck) {
        UUID tenantId = TenantContext.requireTenantId();
        Instant now = Instant.now();
        String actor = Warehouses.currentActor();

        List<WaveAllocator.Demand> demands = new ArrayList<>();
        List<OutboundLine> demandLines = new ArrayList<>();
        Map<UUID, OutboundOrder> byId = new HashMap<>();
        for (OutboundOrder order : batch) {
            order.requirePickable();
            byId.put(order.id(), order);
            for (OutboundLine line : lines.findByOrderIdOrderByLineNo(order.id())) {
                if (line.shortQty() > 0) {
                    demands.add(new WaveAllocator.Demand(order.id(), line.lineNo(), line.skuId(),
                            line.shortQty()));
                    demandLines.add(line);
                }
            }
        }

        List<WaveAllocator.Source> stock = demands.stream()
                .map(WaveAllocator.Demand::skuId)
                .distinct()
                .flatMap(skuId -> ledger.pickableStock(warehouseId, skuId).stream())
                .toList();
        WaveAllocator.Allocation allocation = WaveAllocator.allocate(demands, stock);

        if (refuseIfBackOrderStuck && batch.size() == 1
                && batch.get(0).status() != OutboundOrder.Status.OPEN
                && allocation.takes().isEmpty()) {
            throw new BusinessRuleViolationException("back-order-unfilled",
                    "No bin holds any of what outbound order " + batch.get(0).orderNo()
                            + " is still owed; nothing was picked");
        }

        for (WaveAllocator.Take take : allocation.takes()) {
            WaveAllocator.Demand demand = demands.get(take.demandIndex());
            ledger.transfer(warehouseId, MovementType.PICK, LocationType.BIN, take.binId(),
                    LocationType.OUTBOUND, demand.orderId(), demand.skuId(), take.lotNo(),
                    take.quantity(), demand.orderId(), actor, now);
        }

        Set<UUID> moved = new HashSet<>();
        Set<UUID> stillShort = new HashSet<>();
        List<Object> published = new ArrayList<>();
        for (int i = 0; i < demands.size(); i++) {
            OutboundLine line = demandLines.get(i);
            OutboundOrder order = byId.get(line.orderId());
            int filled = allocation.filledFor(i);
            OutboundLine after = line;
            if (filled > 0) {
                after = lines.save(line.withPicked(line.pickedQty() + filled));
                moved.add(order.id());
            }
            if (after.shortQty() > 0) {
                stillShort.add(order.id());
            }
            boolean firstPick = order.status() == OutboundOrder.Status.OPEN;
            if (firstPick && after.shortQty() > 0) {
                published.add(new PickShortfallRecorded(tenantId, order.id(), order.orderNo(),
                        warehouseId, after.lineNo(), after.skuId(), after.orderedQty(),
                        after.pickedQty(), after.shortQty(), waveId, now));
            } else if (!firstPick && filled > 0 && after.shortQty() == 0) {
                published.add(new BackOrderFilled(tenantId, order.id(), order.orderNo(),
                        warehouseId, after.lineNo(), after.skuId(), after.orderedQty(), filled,
                        waveId, now));
            }
        }

        for (OutboundOrder order : batch) {
            OutboundOrder next = order.picked(moved.contains(order.id()),
                    stillShort.contains(order.id()), now);
            if (next != order) {
                orders.save(next);
            }
        }
        published.forEach(events::publishEvent);
    }
}
