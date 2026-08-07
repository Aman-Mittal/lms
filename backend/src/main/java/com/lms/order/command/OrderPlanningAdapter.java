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

import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.lms.order.api.OrderPlanningPort;
import com.lms.order.command.domain.OrderLine;
import com.lms.order.command.domain.SalesOrder;
import com.lms.shared.error.ResourceNotFoundException;
import com.lms.shared.tenant.TenantContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Implements the port planning calls, keeping order's internals on this side of
 * the boundary.
 *
 * <p>A separate class from {@link OrderCommandService} on purpose. The port is
 * a published contract with one known consumer; the command service is the
 * module's own surface. Merging them would mean every method added to the
 * service for internal use appeared, by accident, to be part of the contract.
 */
@Service
public class OrderPlanningAdapter implements OrderPlanningPort {

    private final SalesOrderRepository orders;
    private final OrderLineRepository lines;

    public OrderPlanningAdapter(SalesOrderRepository orders, OrderLineRepository lines) {
        this.orders = orders;
        this.lines = lines;
    }

    @Override
    @Transactional(readOnly = true)
    public List<PlannableLine> plannableLines(UUID orderId) {
        UUID tenantId = TenantContext.requireTenantId();
        SalesOrder order = require(orderId);

        if (!order.isPlannable()) {
            // Not an error: planning legitimately asks about orders it has not
            // been told about. An empty answer says "nothing to do here", which
            // is exactly true for a draft or a cancelled order.
            return List.of();
        }

        return lines.findUnplannedByOrder(tenantId, orderId).stream()
                .map(OrderPlanningAdapter::toPlannable)
                .toList();
    }

    @Override
    @Transactional
    public void markLinesPlanned(UUID orderId, Set<UUID> plannedLineIds) {
        UUID tenantId = TenantContext.requireTenantId();
        SalesOrder order = require(orderId);

        if (plannedLineIds != null && !plannedLineIds.isEmpty()) {
            List<OrderLine> toMark = lines.findByOrder(tenantId, orderId).stream()
                    .filter(line -> plannedLineIds.contains(line.id()))
                    .filter(line -> !line.planned())
                    .map(OrderLine::markPlanned)
                    .toList();
            lines.saveAll(toMark);
        }

        // Recounted from the database rather than derived from the set that was
        // just passed in. The caller knows what it planned this time; only the
        // table knows what was planned before.
        long total = lines.countByOrder(tenantId, orderId);
        long planned = lines.countPlannedByOrder(tenantId, orderId);
        orders.save(order.withCoverage((int) planned, (int) total));
    }

    private static PlannableLine toPlannable(OrderLine line) {
        return new PlannableLine(line.id(), line.consigneePartnerId(), line.destinationTerminalId(),
                line.materialClass(), line.isHazmat(),
                line.deadWeightKg(), line.volumeM3(), line.chargeableWeightKg());
    }

    private SalesOrder require(UUID orderId) {
        return orders.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order", orderId));
    }
}
