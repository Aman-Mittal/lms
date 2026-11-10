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

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.lms.order.api.OrderLineCancellationHook;
import com.lms.planning.command.domain.Consignment;
import com.lms.planning.command.domain.Consignment.ConsignmentStatus;
import com.lms.shared.error.BusinessRuleViolationException;
import com.lms.shared.tenant.TenantContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Cancels the consignment an order line is on, when the line is cancelled.
 *
 * <p>The consignment goes exactly as if a planner had cancelled it
 * ({@link ConsignmentCancellationService}), so it is refused for the same
 * reasons and with the same codes, and the order's cancellation of the line
 * is refused with it. A line whose consignment was split is refused with
 * {@code order-line-on-split-consignment}: the parts share every line of the
 * whole, so none of them carries this one alone.
 */
@Service
class OrderLineCancellationAdapter implements OrderLineCancellationHook {

    private final ConsignmentRepository consignments;
    private final PlanningLinks links;
    private final ConsignmentCancellationService cancellation;

    OrderLineCancellationAdapter(ConsignmentRepository consignments, PlanningLinks links,
                                 ConsignmentCancellationService cancellation) {
        this.consignments = consignments;
        this.links = links;
        this.cancellation = cancellation;
    }

    @Override
    @Transactional
    public List<UUID> onOrderLineCancelling(UUID orderId, UUID lineId, String reason) {
        UUID tenantId = TenantContext.requireTenantId();
        List<Consignment> live = new ArrayList<>();
        consignments.findAllById(links.consignmentsOfLine(tenantId, lineId)).forEach(c -> {
            if (c.status() != ConsignmentStatus.CANCELLED) {
                live.add(c);
            }
        });
        if (live.stream().anyMatch(c -> c.status() == ConsignmentStatus.SPLIT || c.isPart())) {
            throw new BusinessRuleViolationException("order-line-on-split-consignment",
                    "The line is on a consignment that has been split across vehicles; cancel the "
                            + "order instead");
        }
        List<UUID> cancelled = new ArrayList<>();
        for (Consignment consignment : live) {
            cancelled.add(cancellation.cancelWithin(consignment, reason).consignmentId());
        }
        return cancelled;
    }
}
