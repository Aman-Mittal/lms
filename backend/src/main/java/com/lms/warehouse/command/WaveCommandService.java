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
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

import com.lms.shared.error.BusinessRuleViolationException;
import com.lms.shared.error.ResourceNotFoundException;
import com.lms.shared.tenant.TenantContext;
import com.lms.warehouse.command.domain.OutboundOrder;
import com.lms.warehouse.command.domain.PickWave;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Wave picking: several outbound orders of one warehouse, picked in one pass.
 *
 * <p>A wave draws on one running balance per bin and lot, so it never
 * allocates a unit twice however many of its orders want the same SKU; it
 * serves its orders in the sequence they were given, and a line it cannot
 * fill is short on that order alone. Once released, a wave's orders are not
 * picked singly until the wave has been.
 */
@Service
public class WaveCommandService {

    private final PickWaveRepository waves;
    private final OutboundOrderRepository orders;
    private final WaveMembers members;
    private final OrderPicker picker;
    private final Warehouses warehouses;

    public WaveCommandService(PickWaveRepository waves, OutboundOrderRepository orders,
                              WaveMembers members, OrderPicker picker, Warehouses warehouses) {
        this.waves = waves;
        this.orders = orders;
        this.members = members;
        this.picker = picker;
        this.warehouses = warehouses;
    }

    /**
     * Forms and releases a wave.
     *
     * @param orderIds served in this sequence; each must ship from the
     *                 warehouse, be open or owed a back-order, and not
     *                 already wait in another released wave
     */
    @Transactional
    @PreAuthorize("hasAuthority('WAREHOUSE_OPERATE')"
            + " and @abac.permits('WAREHOUSE_OPERATE', 'TERMINAL', #warehouseId)")
    public UUID release(UUID warehouseId, List<UUID> orderIds) {
        warehouses.requireWarehouse(warehouseId);
        if (orderIds == null || orderIds.isEmpty()) {
            throw new BusinessRuleViolationException("pick-wave-has-no-orders",
                    "A wave needs at least one outbound order");
        }
        if (new HashSet<>(orderIds).size() != orderIds.size()) {
            throw new BusinessRuleViolationException("pick-wave-order-repeated",
                    "An outbound order appears in the wave more than once");
        }
        UUID tenantId = TenantContext.requireTenantId();
        PickWave wave = waves.save(PickWave.release(tenantId, warehouseId, Instant.now()));
        int seq = 0;
        for (UUID orderId : orderIds) {
            OutboundOrder order = requireOrder(orderId);
            if (!order.warehouseId().equals(warehouseId)) {
                throw new BusinessRuleViolationException("pick-wave-mixes-warehouses",
                        "Outbound order " + order.orderNo()
                                + " ships from another warehouse; a wave is picked in one");
            }
            if (!order.pickable()) {
                throw new BusinessRuleViolationException("outbound-order-not-pickable",
                        "Outbound order " + order.orderNo() + " is " + order.status()
                                + "; only an open or back-ordered order can join a wave");
            }
            members.awaitingWave(orderId).ifPresent(waveNo -> {
                throw new BusinessRuleViolationException("outbound-order-in-wave",
                        "Outbound order " + order.orderNo() + " is already waiting in wave "
                                + waveNo);
            });
            members.add(tenantId, wave.id(), ++seq, orderId);
        }
        return wave.id();
    }

    /** Picks every order in the wave together. Once per wave. */
    @Transactional
    @PreAuthorize("hasAuthority('WAREHOUSE_OPERATE')")
    public void pick(UUID waveId) {
        PickWave wave = waves.findById(waveId)
                .orElseThrow(() -> new ResourceNotFoundException("Pick wave", waveId));
        // Saved first: a second picker racing this one fails here on the
        // version, before either has moved a unit.
        waves.save(wave.picked(Warehouses.currentActor(), Instant.now()));
        List<OutboundOrder> batch = members.ordersOf(waveId).stream()
                .map(this::requireOrder)
                .toList();
        picker.pick(wave.warehouseId(), batch, waveId, false);
    }

    private OutboundOrder requireOrder(UUID orderId) {
        return orders.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Outbound order", orderId));
    }
}
