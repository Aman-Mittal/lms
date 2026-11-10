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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.lms.order.api.OrderLineCancellationHook;
import com.lms.order.command.domain.OrderLine;
import com.lms.order.command.domain.SalesOrder;
import com.lms.shared.audit.TransitionAudit;
import com.lms.shared.config.Json;
import com.lms.shared.error.BusinessRuleViolationException;
import com.lms.shared.error.ResourceNotFoundException;
import com.lms.shared.tenant.TenantContext;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Cancels one line of a validated order, and the freight cut from it.
 *
 * <p>The order stays; the line stays on it, marked cancelled, as the record of
 * what was asked for. It no longer counts as demand: it is never planned, and
 * it drops out of the order's coverage, weight and credit exposure. If the
 * line is already on a consignment, planning cancels that consignment in this
 * transaction (every {@link OrderLineCancellationHook}), and any other lines
 * cut into the same consignment go back to waiting for one -- what a
 * consignment carries is fixed when it is generated, so the planner generates
 * again for them.
 */
@Service
public class OrderLineCancellationService {

    /** What a line cancellation did: the consignments it cancelled, and the other lines it freed. */
    public record LineCancellation(UUID lineId, List<UUID> cancelledConsignmentIds,
                                   List<UUID> releasedLineIds) {
    }

    private final SalesOrderRepository orders;
    private final OrderLineRepository lines;
    private final List<OrderLineCancellationHook> hooks;
    private final OrderPlanningAdapter coverage;
    private final TransitionAudit audit;

    OrderLineCancellationService(SalesOrderRepository orders, OrderLineRepository lines,
                                 List<OrderLineCancellationHook> hooks,
                                 OrderPlanningAdapter coverage, TransitionAudit audit) {
        this.orders = orders;
        this.lines = lines;
        this.hooks = hooks;
        this.coverage = coverage;
        this.audit = audit;
    }

    /**
     * Cancels the line.
     *
     * <p>Guarded by the order's version, like every order edit. Refusals, all
     * 409: {@code order-not-validated} for a draft (remove the line instead),
     * {@code order-not-amendable} for a cancelled, fulfilled or closed order,
     * {@code order-line-already-cancelled} for a repeat,
     * {@code order-line-already-closed} for a line closed on its own,
     * {@code order-line-last} for the only line still live -- neither
     * cancelled nor closed (cancel or close the order instead), and whatever
     * code the consignment it is on is refused with -- {@code consignment-dispatched},
     * {@code load-has-active-trip}, {@code order-line-on-split-consignment}.
     */
    @Transactional
    @PreAuthorize("hasAuthority('ORDER_UPDATE') and @abac.permits('ORDER_CREATE', 'ORDER', #orderId)")
    public LineCancellation cancel(UUID orderId, UUID lineId, long expectedVersion, String reason) {
        UUID tenantId = TenantContext.requireTenantId();
        SalesOrder order = orders.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("SalesOrder", orderId));
        if (order.version() == null || order.version() != expectedVersion) {
            throw new OptimisticLockingFailureException(
                    "Order " + order.orderNo() + " has changed since version " + expectedVersion);
        }
        switch (order.status()) {
            case VALIDATED, PARTIALLY_PLANNED, FULLY_PLANNED -> { }
            case DRAFT -> throw new BusinessRuleViolationException("order-not-validated",
                    "Order " + order.orderNo() + " is still a draft; remove the line rather than cancel it");
            case CANCELLED, FULFILLED, CLOSED -> throw new BusinessRuleViolationException("order-not-amendable",
                    "Order " + order.orderNo() + " is " + order.status() + " and its lines can no longer"
                            + " be cancelled");
        }
        List<OrderLine> before = lines.findByOrder(tenantId, orderId);
        OrderLine line = before.stream().filter(l -> l.id().equals(lineId)).findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("OrderLine", lineId));
        if (line.cancelled()) {
            throw new BusinessRuleViolationException("order-line-already-cancelled",
                    "Line " + line.lineNo() + " of order " + order.orderNo() + " is already cancelled");
        }
        if (line.isClosed()) {
            throw new BusinessRuleViolationException("order-line-already-closed",
                    "Line " + line.lineNo() + " of order " + order.orderNo()
                            + " is closed and can no longer be cancelled");
        }
        if (before.stream().filter(OrderLine::isOpen).count() == 1) {
            throw new BusinessRuleViolationException("order-line-last",
                    "Line " + line.lineNo() + " is the only line of order " + order.orderNo()
                            + " still live; cancel or close the order instead");
        }
        String why = reason == null || reason.isBlank() ? null : reason.strip();

        List<UUID> cancelledConsignments = new ArrayList<>();
        if (line.planned()) {
            hooks.forEach(hook -> cancelledConsignments.addAll(
                    hook.onOrderLineCancelling(orderId, lineId, why)));
        }

        // Planning may have released lines and moved the order on: read again.
        Map<UUID, OrderLine> now = lines.findByOrder(tenantId, orderId).stream()
                .collect(Collectors.toMap(OrderLine::id, Function.identity()));
        lines.save(now.get(lineId).cancel());
        // Always saves the order, so its version moves on even when its status
        // does not: an edit made from an earlier read of it then loses.
        coverage.recoverCoverage(orderId);
        SalesOrder after = orders.findById(orderId).orElseThrow();

        List<UUID> released = before.stream()
                .filter(l -> l.planned() && !l.id().equals(lineId))
                .filter(l -> !now.get(l.id()).planned())
                .map(OrderLine::id)
                .toList();
        audit.record("ORDER_LINE_CANCELLED", OrderCommandService.AUDIT_TYPE, orderId,
                Json.object("status", order.status().name(),
                        "lineNo", String.valueOf(line.lineNo()),
                        "planned", String.valueOf(line.planned())),
                Json.object("status", after.status().name(),
                        "lineNo", String.valueOf(line.lineNo()),
                        "cancelled", "true",
                        "cancelledConsignments", String.valueOf(cancelledConsignments.size()),
                        "releasedLines", String.valueOf(released.size()),
                        "reason", why));
        return new LineCancellation(lineId, List.copyOf(cancelledConsignments), released);
    }
}
