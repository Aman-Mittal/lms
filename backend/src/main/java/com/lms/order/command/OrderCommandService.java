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

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.lms.order.api.MaterialClass;
import com.lms.order.command.domain.OrderLine;
import com.lms.order.command.domain.SalesOrder;
import com.lms.order.events.OrderValidated;
import com.lms.shared.error.BusinessRuleViolationException;
import com.lms.shared.error.ResourceNotFoundException;
import com.lms.shared.tenant.TenantContext;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Write side for demand (vision document 3.3).
 *
 * <p>Validation is a distinct, explicit step rather than something that happens
 * on every save. An order is captured in pieces -- header first, lines over the
 * following minutes -- and rejecting a half-entered order for being
 * incomplete would make it impossible to enter one at all.
 */
@Service
public class OrderCommandService {

    private final SalesOrderRepository orders;
    private final OrderLineRepository lines;
    private final ApplicationEventPublisher events;

    public OrderCommandService(SalesOrderRepository orders, OrderLineRepository lines,
                               ApplicationEventPublisher events) {
        this.orders = orders;
        this.lines = lines;
        this.events = events;
    }

    @Transactional
    @PreAuthorize("hasAuthority('ORDER_CREATE')")
    public UUID raiseOrder(UUID orgUnitId, UUID customerPartnerId, String orderNo,
                           UUID originTerminalId, Instant requestedPickupAt,
                           Instant requestedDeliveryAt, SalesOrder.OrderSource source) {
        UUID tenantId = TenantContext.requireTenantId();

        orders.findByOrderNo(tenantId, orderNo).ifPresent(existing -> {
            throw new BusinessRuleViolationException("order-no-taken",
                    "An order numbered " + orderNo + " already exists");
        });

        return orders.save(SalesOrder.raise(UUID.randomUUID(), tenantId, orgUnitId,
                customerPartnerId, orderNo, originTerminalId,
                requestedPickupAt, requestedDeliveryAt, source)).id();
    }

    /**
     * Adds a line to a draft order.
     *
     * <p>Refuses once the order has been validated. Amending a validated order
     * would leave planning holding consignments built from demand that no longer
     * matches the order, and the compatibility check that passed at validation
     * would never be re-run.
     */
    @Transactional
    @PreAuthorize("hasAuthority('ORDER_UPDATE')")
    public UUID addLine(UUID orderId, int lineNo, String materialCode, String materialDescription,
                        MaterialClass materialClass, String hazmatUnCode,
                        BigDecimal quantity, String uom, BigDecimal deadWeightKg,
                        BigDecimal lengthM, BigDecimal widthM, BigDecimal heightM,
                        BigDecimal volumetricDivisor,
                        UUID consigneePartnerId, UUID destinationTerminalId) {
        UUID tenantId = TenantContext.requireTenantId();
        SalesOrder order = require(orderId);

        if (!order.isEditable()) {
            throw new BusinessRuleViolationException("order-not-editable",
                    "Order " + order.orderNo() + " is " + order.status() + " and can no longer be amended");
        }

        OrderLine line = new OrderLine(UUID.randomUUID(), tenantId, orderId, lineNo,
                materialCode, materialDescription,
                materialClass == null ? MaterialClass.GENERAL : materialClass, hazmatUnCode,
                quantity, uom, deadWeightKg, lengthM, widthM, heightM, volumetricDivisor,
                consigneePartnerId, destinationTerminalId, false, null, Instant.now());

        return lines.save(line).id();
    }

    /**
     * Runs the 3.3 validations and opens the order to planning.
     *
     * <p>Every failure is collected before any is reported. An operator fixing
     * a fifty-line order one rejection at a time is the difference between a
     * usable system and one people work around by leaving things out.
     */
    @Transactional
    @PreAuthorize("hasAuthority('ORDER_UPDATE')")
    public void validate(UUID orderId) {
        UUID tenantId = TenantContext.requireTenantId();
        SalesOrder order = require(orderId);
        List<OrderLine> orderLines = lines.findByOrder(tenantId, orderId);

        if (orderLines.isEmpty()) {
            throw new BusinessRuleViolationException("order-has-no-lines",
                    "Order " + order.orderNo() + " has no line items to validate");
        }

        List<String> problems = new ArrayList<>();
        problems.addAll(hazmatProblems(orderLines));
        problems.addAll(compatibilityProblems(orderLines));

        if (!problems.isEmpty()) {
            throw new BusinessRuleViolationException("order-validation-failed",
                    "Order " + order.orderNo() + " cannot be validated: " + String.join("; ", problems));
        }

        orders.save(order.transitionTo(SalesOrder.OrderStatus.VALIDATED));

        BigDecimal totalChargeable = orderLines.stream()
                .map(OrderLine::chargeableWeightKg)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        events.publishEvent(new OrderValidated(tenantId, orderId, order.orderNo(),
                order.customerPartnerId(), orderLines.size(), totalChargeable,
                orderLines.stream().anyMatch(OrderLine::isHazmat), Instant.now()));
    }

    @Transactional
    @PreAuthorize("hasAuthority('ORDER_UPDATE')")
    public void cancel(UUID orderId) {
        SalesOrder order = require(orderId);
        orders.save(order.transitionTo(SalesOrder.OrderStatus.CANCELLED));
    }

    // ------------------------------------------------------------- validation

    /**
     * The dangerous-goods rule, enforced in both directions.
     *
     * <p>A UN number without a dangerous class is a mis-keyed line; a dangerous
     * class without a UN number is an undeclared shipment. The second is the
     * one that gets a carrier prosecuted, so neither is tolerated.
     */
    private static List<String> hazmatProblems(List<OrderLine> orderLines) {
        List<String> problems = new ArrayList<>();
        for (OrderLine line : orderLines) {
            if (line.materialClass().isDangerous() && !line.isHazmat()) {
                problems.add("line " + line.lineNo() + " is classed " + line.materialClass()
                        + " but carries no UN number");
            }
            if (line.isHazmat() && !line.materialClass().isDangerous()) {
                problems.add("line " + line.lineNo() + " carries UN number " + line.hazmatUnCode()
                        + " but is classed " + line.materialClass()
                        + ", which is not a dangerous goods class");
            }
        }
        return problems;
    }

    /**
     * The compatibility matrix, applied to lines that will end up together.
     *
     * <p>Checked per grouping key rather than across the whole order, because
     * consignment generation groups by consignee and destination: two
     * incompatible materials heading to different customers will never share a
     * transport unit, and rejecting that order would be wrong.
     */
    private static List<String> compatibilityProblems(List<OrderLine> orderLines) {
        Map<OrderLine.GroupingKey, List<OrderLine>> groups = new LinkedHashMap<>();
        for (OrderLine line : orderLines) {
            groups.computeIfAbsent(line.groupingKey(), key -> new ArrayList<>()).add(line);
        }

        List<String> problems = new ArrayList<>();
        for (List<OrderLine> group : groups.values()) {
            for (int i = 0; i < group.size(); i++) {
                for (int j = i + 1; j < group.size(); j++) {
                    OrderLine left = group.get(i);
                    OrderLine right = group.get(j);
                    if (!left.isCompatibleWith(right)) {
                        problems.add("lines " + left.lineNo() + " and " + right.lineNo()
                                + " are bound for the same destination but "
                                + left.materialClass() + " must not travel with "
                                + right.materialClass());
                    }
                }
            }
        }
        return problems;
    }

    private SalesOrder require(UUID orderId) {
        return orders.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order", orderId));
    }
}
