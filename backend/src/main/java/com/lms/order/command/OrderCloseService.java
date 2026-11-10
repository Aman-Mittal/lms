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

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.lms.order.api.OrderFreightProbe;
import com.lms.order.api.OrderFreightProbe.LineFreight;
import com.lms.order.command.domain.CloseKind;
import com.lms.order.command.domain.OrderLine;
import com.lms.order.command.domain.SalesOrder;
import com.lms.order.events.OrderClosed;
import com.lms.order.events.OrderFulfilled;
import com.lms.shared.audit.TransitionAudit;
import com.lms.shared.config.Json;
import com.lms.shared.error.BusinessRuleViolationException;
import com.lms.shared.error.ResourceNotFoundException;
import com.lms.shared.tenant.TenantContext;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Ends an order, or one of its lines, short of cancelling it.
 *
 * <p>Three ways a person closes, and one the platform does:
 * <ul>
 *   <li><b>NORMAL</b> -- every open line is planned and everything cut for it has
 *       been delivered; refused with {@code order-not-complete} otherwise, naming
 *       each line and why. The tenant's tolerance is not applied: a person asking
 *       for a normal close is told exactly what is outstanding.</li>
 *   <li><b>SHORT</b> -- what is not yet planned will not be: the unplanned lines are
 *       closed and planning is never offered them again. Freight already cut keeps
 *       moving and is not touched. Refused with {@code order-freight-pending} while
 *       freight cut from an open line has not left, because closing would leave a
 *       consignment being loaded and sourced for an order nobody is waiting on.</li>
 *   <li><b>FORCE</b> -- closed whatever the freight is doing, by someone holding
 *       {@code ORDER_FORCE_CLOSE}. Nothing downstream is touched.</li>
 *   <li><b>AUTO</b> -- when the last consignment is delivered and the quantity still
 *       unplanned is within the tenant's tolerance, the platform short-closes the
 *       rest itself ({@link #onEverythingDelivered}).</li>
 * </ul>
 *
 * <p>Short and forced closes need a reason ({@code close-reason-required}); a
 * reason on a normal close is kept too. Only a validated order still open to
 * planning or delivery can be closed: {@code order-already-closed} for one that
 * is closed or fulfilled, {@code order-is-cancelled}, and
 * {@code order-not-validated} for a draft, which is cancelled instead.
 *
 * <p>Every close is audited in its own transaction ({@code ORDER_CLOSED},
 * {@code ORDER_LINE_CLOSED}) and so appears in the order's history.
 */
@Service
public class OrderCloseService {

    /** The reason the platform gives when it closes an order within tolerance. */
    static final String AUTO_REASON = "What is still on the road is within the close tolerance";

    private final SalesOrderRepository orders;
    private final OrderLineRepository lines;
    private final List<OrderFreightProbe> freight;
    private final OrderCloseSettings settings;
    private final TransitionAudit audit;
    private final ApplicationEventPublisher events;

    public OrderCloseService(SalesOrderRepository orders, OrderLineRepository lines,
                             List<OrderFreightProbe> freight, OrderCloseSettings settings,
                             TransitionAudit audit, ApplicationEventPublisher events) {
        this.orders = orders;
        this.lines = lines;
        this.freight = freight;
        this.settings = settings;
        this.audit = audit;
        this.events = events;
    }

    /** Closes the whole order; see the class comment for the kinds and refusals. */
    @Transactional
    @PreAuthorize("hasAuthority('ORDER_UPDATE') and (#kind == null or #kind.name() != 'FORCE'"
            + " or hasAuthority('ORDER_FORCE_CLOSE'))")
    public void close(UUID orderId, CloseKind kind, String reason) {
        CloseKind how = requested(kind);
        String why = requireReason(how, reason);
        UUID tenantId = TenantContext.requireTenantId();
        SalesOrder order = requireClosable(orderId);
        List<OrderLine> all = lines.findByOrder(tenantId, orderId);
        List<OrderLine> open = all.stream().filter(OrderLine::isOpen).toList();
        Map<UUID, LineFreight> carried = freightOf(orderId);

        switch (how) {
            case NORMAL -> {
                List<String> outstanding = new ArrayList<>();
                for (OrderLine line : open) {
                    String gap = outstanding(line, carried.get(line.id()));
                    if (gap != null) {
                        outstanding.add(gap);
                    }
                }
                if (!outstanding.isEmpty()) {
                    throw new BusinessRuleViolationException("order-not-complete",
                            "Order " + order.orderNo() + " cannot be closed normally: "
                                    + String.join("; ", outstanding));
                }
            }
            case SHORT -> requireNothingWaiting(order.orderNo(), open, carried);
            default -> { }
        }
        closeOrder(order, open, how, why);
    }

    /**
     * Closes one line, with the same kinds and rules as the whole order. When
     * every other line is then closed or fulfilled, the order closes too, the
     * same way; otherwise its planning status is restated, since a closed line
     * that was never planned no longer waits for a consignment.
     */
    @Transactional
    @PreAuthorize("hasAuthority('ORDER_UPDATE') and (#kind == null or #kind.name() != 'FORCE'"
            + " or hasAuthority('ORDER_FORCE_CLOSE'))")
    public void closeLine(UUID orderId, UUID lineId, CloseKind kind, String reason) {
        CloseKind how = requested(kind);
        String why = requireReason(how, reason);
        UUID tenantId = TenantContext.requireTenantId();
        SalesOrder order = requireClosable(orderId);
        List<OrderLine> all = lines.findByOrder(tenantId, orderId);
        OrderLine line = all.stream().filter(l -> l.id().equals(lineId)).findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("OrderLine", lineId));
        if (line.isClosed()) {
            throw new BusinessRuleViolationException("order-line-already-closed",
                    "Line " + line.lineNo() + " of order " + order.orderNo() + " is already closed");
        }
        if (line.cancelled()) {
            throw new BusinessRuleViolationException("order-line-already-cancelled",
                    "Line " + line.lineNo() + " of order " + order.orderNo()
                            + " is cancelled and has nothing to close");
        }
        Map<UUID, LineFreight> carried = freightOf(orderId);
        switch (how) {
            case NORMAL -> {
                String gap = outstanding(line, carried.get(line.id()));
                if (gap != null) {
                    throw new BusinessRuleViolationException("order-line-not-complete",
                            "Line " + line.lineNo() + " of order " + order.orderNo()
                                    + " cannot be closed normally: " + gap);
                }
            }
            case SHORT -> requireNothingWaiting(order.orderNo(), List.of(line), carried);
            default -> { }
        }

        Instant now = Instant.now();
        lines.save(line.closed(how, why, now));
        audit.record("ORDER_LINE_CLOSED", OrderCommandService.AUDIT_TYPE, orderId,
                Json.object("status", order.status().name(), "lineNo", String.valueOf(line.lineNo())),
                Json.object("status", order.status().name(), "lineNo", String.valueOf(line.lineNo()),
                        "lineId", line.id().toString(), "closeKind", how.name(),
                        "planned", String.valueOf(line.planned()), "reason", why));

        List<OrderLine> stillOpen = all.stream()
                .filter(l -> l.isOpen() && !l.id().equals(lineId)).toList();
        boolean nothingLeft = stillOpen.stream()
                .allMatch(l -> l.planned() && fulfilled(carried.get(l.id())));
        if (nothingLeft) {
            closeOrder(order, stillOpen, how, why);
            return;
        }
        restateCoverage(tenantId, order);
    }

    /**
     * Planning has delivered everything cut from the order. A fully planned
     * order is fulfilled, unless the tenant has turned automatic fulfilment
     * off, when it is left for a person to close. Idempotent: an order no
     * longer open to planning is not touched.
     */
    @Transactional
    public void onEverythingDelivered(UUID orderId) {
        SalesOrder order = orders.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order", orderId));
        if (order.status() != SalesOrder.OrderStatus.FULLY_PLANNED || !settings.effective().autoFulfil()) {
            return;
        }
        orders.save(order.transitionTo(SalesOrder.OrderStatus.FULFILLED));
        audit.record("ORDER_FULFILLED", OrderCommandService.AUDIT_TYPE, orderId,
                Json.object("status", order.status().name()),
                Json.object("status", "FULFILLED"));
        events.publishEvent(new OrderFulfilled(order.tenantId(), orderId, order.orderNo(), Instant.now()));
    }

    /**
     * Some of the order's freight has been delivered and some is still moving.
     * When the tenant has a close tolerance and every open line of a fully
     * planned order is delivered to within it, the platform closes the order
     * (kind AUTO) and its lines; the freight still moving is not touched. The
     * settings are read first, so a tenant without a tolerance pays one
     * indexed read per order and nothing more.
     */
    @Transactional
    public void onPartDelivered(UUID orderId) {
        OrderCloseSettings.Policy policy = settings.effective();
        if (!policy.autoFulfil() || policy.tolerancePercent().signum() <= 0) {
            return;
        }
        SalesOrder order = orders.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order", orderId));
        if (order.status() != SalesOrder.OrderStatus.FULLY_PLANNED) {
            return;
        }
        List<OrderLine> open = lines.findByOrder(order.tenantId(), orderId).stream()
                .filter(OrderLine::isOpen).toList();
        Map<UUID, LineFreight> carried = freightOf(orderId);
        boolean within = open.stream().allMatch(l -> l.planned() && carried.get(l.id()) != null
                && carried.get(l.id()).fulfilledWithin(policy.tolerancePercent()));
        if (within) {
            closeOrder(order, open, CloseKind.AUTO, AUTO_REASON);
        }
    }

    private void closeOrder(SalesOrder order, List<OrderLine> open, CloseKind how, String why) {
        Instant now = Instant.now();
        lines.saveAll(open.stream().map(l -> l.closed(how, why, now)).toList());
        orders.save(order.closed(how, why, now));
        long unplanned = open.stream().filter(l -> !l.planned()).count();
        audit.record("ORDER_CLOSED", OrderCommandService.AUDIT_TYPE, order.id(),
                Json.object("status", order.status().name()),
                Json.object("status", "CLOSED", "closeKind", how.name(),
                        "linesClosed", String.valueOf(open.size()),
                        "unplannedLinesClosed", String.valueOf(unplanned), "reason", why));
        events.publishEvent(new OrderClosed(order.tenantId(), order.id(), order.orderNo(),
                order.status().name(), how.name(), why, open.size(), now));
    }

    /** As planning's report restates it: planned lines, and closed ones that never were. */
    private void restateCoverage(UUID tenantId, SalesOrder order) {
        long total = lines.countByOrder(tenantId, order.id());
        long planned = lines.countPlannedByOrder(tenantId, order.id());
        long closedUnplanned = lines.countClosedUnplannedByOrder(tenantId, order.id());
        SalesOrder covered = orders.save(order.withCoverage((int) planned, (int) closedUnplanned, (int) total));
        if (covered.status() != order.status()) {
            audit.record(covered.status() == SalesOrder.OrderStatus.FULLY_PLANNED
                            ? "ORDER_FULLY_PLANNED" : "ORDER_COVERAGE_CHANGED",
                    OrderCommandService.AUDIT_TYPE, order.id(),
                    Json.object("status", order.status().name()),
                    Json.object("status", covered.status().name(),
                            "plannedLines", String.valueOf(planned),
                            "closedLines", String.valueOf(closedUnplanned),
                            "totalLines", String.valueOf(total)));
        }
    }

    private static CloseKind requested(CloseKind kind) {
        if (kind == CloseKind.AUTO) {
            throw new IllegalArgumentException("AUTO is how the platform closes an order; ask for NORMAL, SHORT or FORCE");
        }
        return kind == null ? CloseKind.NORMAL : kind;
    }

    private static String requireReason(CloseKind kind, String reason) {
        String why = reason == null || reason.isBlank() ? null : reason.strip();
        if (why == null && kind.needsReason()) {
            throw new BusinessRuleViolationException("close-reason-required",
                    "A " + kind.name().toLowerCase(java.util.Locale.ROOT) + " close must say why");
        }
        return why;
    }

    private SalesOrder requireClosable(UUID orderId) {
        SalesOrder order = orders.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order", orderId));
        switch (order.status()) {
            case CLOSED, FULFILLED -> throw new BusinessRuleViolationException("order-already-closed",
                    "Order " + order.orderNo() + " is already " + order.status());
            case CANCELLED -> throw new BusinessRuleViolationException("order-is-cancelled",
                    "Order " + order.orderNo() + " is cancelled and cannot be closed");
            case DRAFT -> throw new BusinessRuleViolationException("order-not-validated",
                    "Order " + order.orderNo() + " is still a draft; cancel it rather than close it");
            default -> { }
        }
        return order;
    }

    /** Why a line is not complete, or null when it is. */
    private static String outstanding(OrderLine line, LineFreight carried) {
        if (!line.planned()) {
            return "line " + line.lineNo() + " is not planned";
        }
        if (!fulfilled(carried)) {
            return "line " + line.lineNo() + " has freight not yet delivered";
        }
        return null;
    }

    private static boolean fulfilled(LineFreight carried) {
        return carried != null && carried.fulfilled();
    }

    private static void requireNothingWaiting(String orderNo, List<OrderLine> open,
                                              Map<UUID, LineFreight> carried) {
        List<String> waiting = open.stream()
                .filter(l -> carried.get(l.id()) != null && carried.get(l.id()).waiting() > 0)
                .map(l -> "line " + l.lineNo())
                .toList();
        if (!waiting.isEmpty()) {
            throw new BusinessRuleViolationException("order-freight-pending",
                    "Order " + orderNo + " has freight cut but not yet dispatched for "
                            + String.join(", ", waiting)
                            + "; dispatch it, or force the close");
        }
    }

    private Map<UUID, LineFreight> freightOf(UUID orderId) {
        Map<UUID, LineFreight> all = new HashMap<>();
        freight.forEach(probe -> all.putAll(probe.freightByLine(orderId)));
        return all;
    }
}
