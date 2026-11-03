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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import com.lms.masterdata.api.PartnerStandingPort;
import com.lms.masterdata.api.TerminalCategoryPort;
import com.lms.order.api.FreightMode;
import com.lms.order.api.MaterialClass;
import com.lms.order.api.OrderAmendmentGuard;
import com.lms.order.api.OrderCancellationHook;
import com.lms.order.command.domain.OrderLine;
import com.lms.order.command.domain.SalesOrder;
import com.lms.order.events.OrderValidated;
import com.lms.shared.audit.TransitionAudit;
import com.lms.shared.config.Json;
import com.lms.shared.error.BusinessRuleViolationException;
import com.lms.shared.error.ResourceNotFoundException;
import com.lms.shared.tenant.TenantContext;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.OptimisticLockingFailureException;
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

    /** Master data's name for the functional category of a consolidation hub. */
    private static final String HUB_CATEGORY = "HUB";

    /** The resource type of every audit row about an order. */
    static final String AUDIT_TYPE = "SalesOrder";

    private final SalesOrderRepository orders;
    private final OrderLineRepository lines;
    private final ApplicationEventPublisher events;
    private final TerminalCategoryPort terminalCategories;
    private final PartnerStandingPort partners;
    private final List<OrderCancellationHook> cancellationHooks;
    private final List<OrderAmendmentGuard> amendmentGuards;
    private final TransitionAudit audit;

    public OrderCommandService(SalesOrderRepository orders, OrderLineRepository lines,
                               ApplicationEventPublisher events,
                               TerminalCategoryPort terminalCategories,
                               PartnerStandingPort partners,
                               List<OrderCancellationHook> cancellationHooks,
                               List<OrderAmendmentGuard> amendmentGuards, TransitionAudit audit) {
        this.orders = orders;
        this.lines = lines;
        this.events = events;
        this.terminalCategories = terminalCategories;
        this.partners = partners;
        this.cancellationHooks = cancellationHooks;
        this.amendmentGuards = amendmentGuards;
        this.audit = audit;
    }

    /** Raises a full-truckload order, which is what an order without a mode has always been. */
    @Transactional
    @PreAuthorize("hasAuthority('ORDER_CREATE') and @abac.permitsIn('ORDER_CREATE', #orgUnitId)")
    public UUID raiseOrder(UUID orgUnitId, UUID customerPartnerId, String orderNo,
                           UUID originTerminalId, Instant requestedPickupAt,
                           Instant requestedDeliveryAt, SalesOrder.OrderSource source) {
        return raiseOrder(orgUnitId, customerPartnerId, orderNo, originTerminalId,
                requestedPickupAt, requestedDeliveryAt, source, FreightMode.FTL, null, null);
    }

    /**
     * Raises an order in a stated freight mode.
     *
     * <p>An LTL order names the hub it enters the network at and the hub its
     * freight is delivered from; each consignment generated from it becomes a
     * docket between those two hubs.
     */
    @Transactional
    @PreAuthorize("hasAuthority('ORDER_CREATE') and @abac.permitsIn('ORDER_CREATE', #orgUnitId)")
    public UUID raiseOrder(UUID orgUnitId, UUID customerPartnerId, String orderNo,
                           UUID originTerminalId, Instant requestedPickupAt,
                           Instant requestedDeliveryAt, SalesOrder.OrderSource source,
                           FreightMode freightMode, UUID originHubTerminalId,
                           UUID destinationHubTerminalId) {
        UUID tenantId = TenantContext.requireTenantId();

        // Ids from the body, looked up under row-level security: a foreign
        // tenant's partner or terminal reads as absent, exactly as a made-up one.
        requirePartner(customerPartnerId);
        requireTerminal(originTerminalId);

        orders.findByOrderNo(tenantId, orderNo).ifPresent(existing -> {
            throw new BusinessRuleViolationException("order-no-taken",
                    "An order numbered " + orderNo + " already exists");
        });

        SalesOrder order = SalesOrder.raise(UUID.randomUUID(), tenantId, orgUnitId,
                customerPartnerId, orderNo, originTerminalId,
                requestedPickupAt, requestedDeliveryAt, source, freightMode,
                originHubTerminalId, destinationHubTerminalId);

        if (order.freightMode() == FreightMode.LTL) {
            requireHub(orderNo, "origin", originHubTerminalId);
            requireHub(orderNo, "destination", destinationHubTerminalId);
        }

        SalesOrder raised = orders.save(order);
        audit.record("ORDER_RAISED", AUDIT_TYPE, raised.id(), null,
                Json.object("status", raised.status().name(), "orderNo", raised.orderNo(),
                        "customerPartnerId", raised.customerPartnerId().toString(),
                        "freightMode", raised.freightMode().name()));
        return raised.id();
    }

    private void requirePartner(UUID partnerId) {
        if (partnerId != null && !partners.exists(partnerId)) {
            throw new ResourceNotFoundException("BusinessPartner", partnerId);
        }
    }

    private void requireTerminal(UUID terminalId) {
        if (terminalId != null && terminalCategories.functionalCategoryOf(terminalId).isEmpty()) {
            throw new ResourceNotFoundException("Terminal", terminalId);
        }
    }

    /**
     * A part load's hubs must be terminals registered as hubs.
     *
     * <p>Checked after the aggregate has accepted the shape of the order, so
     * that a booking with no hubs at all is refused for that, not for naming
     * one that is not a hub. Asked of master data rather than enforced by a
     * foreign key: the database can say a terminal exists, not what kind of
     * site it is. A customer's yard named as a hub would consolidate dockets at
     * a place no line haul calls, and rate them on a zone pair that means
     * nothing. A terminal the tenant cannot see is refused the same way --
     * from here it is indistinguishable from one that is not a hub.
     */
    private void requireHub(String orderNo, String which, UUID terminalId) {
        String category = terminalCategories.functionalCategoryOf(terminalId).orElse(null);
        if (!HUB_CATEGORY.equals(category)) {
            throw new BusinessRuleViolationException("ltl-hub-not-a-hub",
                    "Part-load order " + orderNo + " names terminal " + terminalId + " as its "
                            + which + " hub, but it is "
                            + (category == null ? "not a known terminal" : "a " + category)
                            + " rather than a HUB");
        }
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
        requirePartner(consigneePartnerId);
        requireTerminal(destinationTerminalId);

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

        requireValid(order, orderLines);

        orders.save(order.transitionTo(SalesOrder.OrderStatus.VALIDATED));
        audit.record("ORDER_VALIDATED", AUDIT_TYPE, orderId,
                Json.object("status", order.status().name()),
                Json.object("status", "VALIDATED", "lines", String.valueOf(orderLines.size())));

        BigDecimal totalChargeable = orderLines.stream()
                .map(OrderLine::chargeableWeightKg)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        events.publishEvent(new OrderValidated(tenantId, orderId, order.orderNo(),
                order.customerPartnerId(), orderLines.size(), totalChargeable,
                orderLines.stream().anyMatch(OrderLine::isHazmat), order.freightMode().name(),
                Instant.now()));
    }

    /**
     * Cancels an order, and with it the freight cut from it.
     *
     * <p>Whatever planning holds for the order is wound up in this transaction:
     * its undelivered consignments are cancelled and taken off any load that
     * has not left, and a load left empty is cancelled. If any of that freight
     * has been dispatched the cancellation is refused
     * ({@code order-work-dispatched}) and nothing changes: a lorry on the road
     * cannot be un-ordered by a click.
     *
     * <p>Cancelling an order that is already cancelled is a no-op, so a retry
     * after a lost response is harmless. An order that was delivered cannot be
     * cancelled (the state machine refuses it).
     *
     * @param reason why, for the audit trail; optional, so that existing
     *               callers that send nothing keep working
     */
    @Transactional
    @PreAuthorize("hasAuthority('ORDER_UPDATE')")
    public void cancel(UUID orderId, String reason) {
        SalesOrder order = require(orderId);
        if (order.status() == SalesOrder.OrderStatus.CANCELLED) {
            return;
        }
        // Legality first, so a delivered order is refused for being delivered
        // before anything downstream is asked about it.
        SalesOrder cancelled = order.transitionTo(SalesOrder.OrderStatus.CANCELLED);
        cancellationHooks.forEach(hook -> hook.onOrderCancelling(orderId));
        orders.save(cancelled);

        audit.record("ORDER_CANCELLED", AUDIT_TYPE, orderId,
                Json.object("status", order.status().name()),
                reason == null || reason.isBlank()
                        ? Json.object("status", "CANCELLED")
                        : Json.object("status", "CANCELLED", "reason", reason.strip()));
    }

    // ---------------------------------------------------------------- editing

    /** Why an edit may not proceed, and how a caller is told. */
    private static final String NOT_EDITABLE = "order-not-editable";

    /**
     * Replaces the header of a draft order.
     *
     * <p>Every edit is guarded by the version the caller last read: a stale one
     * is refused with 409 {@code concurrent-modification} and changes nothing,
     * so two people editing one order cannot overwrite each other unseen.
     * Refused with {@code order-not-editable} once the order is validated; a
     * validated order has its own, stricter path ({@link #amend}).
     */
    @Transactional
    @PreAuthorize("hasAuthority('ORDER_UPDATE') and @abac.permits('ORDER_CREATE', 'ORDER', #orderId)")
    public void reviseDraft(UUID orderId, long expectedVersion, OrderHeaderChange header) {
        UUID tenantId = TenantContext.requireTenantId();
        SalesOrder order = requireEditable(orderId, expectedVersion);
        List<OrderLine> current = lines.findByOrder(tenantId, orderId);

        SalesOrder revised = reviseHeader(order, header, expectedVersion);
        SalesOrder saved = orders.save(revised);
        audit.record("ORDER_REVISED", AUDIT_TYPE, orderId,
                snapshot(order, current), snapshot(saved, current));
    }

    /**
     * Replaces every line of a draft order with the given set.
     *
     * <p>One transaction: the lines and the order's version move together, so a
     * reader never sees half the new set. The order is saved first, which is
     * where a lost race is detected, before any line is touched.
     */
    @Transactional
    @PreAuthorize("hasAuthority('ORDER_UPDATE') and @abac.permits('ORDER_CREATE', 'ORDER', #orderId)")
    public void replaceLines(UUID orderId, long expectedVersion, List<OrderLineSpec> specs) {
        UUID tenantId = TenantContext.requireTenantId();
        SalesOrder order = requireEditable(orderId, expectedVersion);
        requireDistinctLineNos(specs);
        List<OrderLine> before = lines.findByOrder(tenantId, orderId);

        SalesOrder saved = orders.save(order.expecting(expectedVersion));
        List<OrderLine> after = swapLines(tenantId, orderId, before, specs);
        audit.record("ORDER_LINES_REPLACED", AUDIT_TYPE, orderId,
                snapshot(order, before), snapshot(saved, after));
    }

    /**
     * Removes one line from a draft order.
     *
     * <p>A line that is not on the order is a 404, not a quiet success: the
     * caller's picture of the order is wrong and should be refreshed.
     */
    @Transactional
    @PreAuthorize("hasAuthority('ORDER_UPDATE') and @abac.permits('ORDER_CREATE', 'ORDER', #orderId)")
    public void removeLine(UUID orderId, UUID lineId, long expectedVersion) {
        UUID tenantId = TenantContext.requireTenantId();
        SalesOrder order = requireEditable(orderId, expectedVersion);
        List<OrderLine> before = lines.findByOrder(tenantId, orderId);
        OrderLine line = before.stream().filter(l -> l.id().equals(lineId)).findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("OrderLine", lineId));

        SalesOrder saved = orders.save(order.expecting(expectedVersion));
        lines.delete(line);
        List<OrderLine> after = before.stream().filter(l -> !l.id().equals(lineId)).toList();
        audit.record("ORDER_LINE_REMOVED", AUDIT_TYPE, orderId,
                snapshot(order, before), snapshot(saved, after));
    }

    /**
     * Amends a validated order that planning has not yet touched.
     *
     * <p>The one controlled way to change demand after validation. It is
     * allowed only while the order is VALIDATED and no consignment has been cut
     * from it (every {@link OrderAmendmentGuard} is asked, and a planned line is
     * refused too): amending later would leave planning holding freight built
     * from demand that no longer matches. Whatever the caller changes, the
     * order is validated again against the result in this transaction, so the
     * hazmat and compatibility checks that passed before cannot be bypassed by
     * editing afterwards. The status stays VALIDATED and the version moves on.
     *
     * <p>Refusals, all 409: {@code order-not-amendable} for a cancelled or
     * fulfilled order, {@code order-has-consignments} once freight has been cut,
     * {@code order-not-validated} for a draft (edit it instead),
     * {@code order-validation-failed} when the amended order would not validate.
     *
     * @param header the replacement header, or null to leave it as it is
     * @param specs  the replacement lines, or null to leave them as they are
     */
    @Transactional
    @PreAuthorize("hasAuthority('ORDER_UPDATE') and @abac.permits('ORDER_CREATE', 'ORDER', #orderId)")
    public void amend(UUID orderId, long expectedVersion, OrderHeaderChange header,
                      List<OrderLineSpec> specs) {
        UUID tenantId = TenantContext.requireTenantId();
        SalesOrder order = require(orderId);
        requireVersion(order, expectedVersion);

        switch (order.status()) {
            case VALIDATED -> { }
            case DRAFT -> throw new BusinessRuleViolationException("order-not-validated",
                    "Order " + order.orderNo() + " is still a draft; edit it rather than amend it");
            case PARTIALLY_PLANNED, FULLY_PLANNED -> throw new BusinessRuleViolationException(
                    "order-has-consignments", "Order " + order.orderNo() + " is " + order.status()
                            + ": freight has been cut from it and it can no longer be amended");
            case CANCELLED, FULFILLED -> throw new BusinessRuleViolationException(
                    "order-not-amendable", "Order " + order.orderNo() + " is " + order.status()
                            + " and can no longer be amended");
        }
        amendmentGuards.forEach(guard -> guard.onOrderAmending(orderId));

        List<OrderLine> before = lines.findByOrder(tenantId, orderId);
        if (before.stream().anyMatch(OrderLine::planned)) {
            throw new BusinessRuleViolationException("order-has-consignments",
                    "Order " + order.orderNo() + " has lines already on a consignment and can no longer"
                            + " be amended");
        }
        if (specs != null) {
            requireDistinctLineNos(specs);
        }

        SalesOrder revised = header == null ? order.expecting(expectedVersion)
                : reviseHeader(order, header, expectedVersion);
        SalesOrder saved = orders.save(revised);
        List<OrderLine> after = specs == null ? before : swapLines(tenantId, orderId, before, specs);
        requireValid(saved, after);

        audit.record("ORDER_AMENDED", AUDIT_TYPE, orderId, snapshot(order, before),
                snapshot(saved, after));
    }

    private SalesOrder requireEditable(UUID orderId, long expectedVersion) {
        SalesOrder order = require(orderId);
        requireVersion(order, expectedVersion);
        if (!order.isEditable()) {
            throw new BusinessRuleViolationException(NOT_EDITABLE,
                    "Order " + order.orderNo() + " is " + order.status() + " and can no longer be edited");
        }
        return order;
    }

    /**
     * Refuses a version other than the stored one before any work is done. The
     * guarded save is what settles a race between two writers; this only
     * spares a stale caller the work, and answers the same 409.
     */
    private static void requireVersion(SalesOrder order, long expectedVersion) {
        if (order.version() == null || order.version() != expectedVersion) {
            throw new OptimisticLockingFailureException(
                    "Order " + order.orderNo() + " has changed since version " + expectedVersion);
        }
    }

    private SalesOrder reviseHeader(SalesOrder order, OrderHeaderChange header, long expectedVersion) {
        SalesOrder revised = order.revised(header.customerPartnerId(), header.originTerminalId(),
                header.requestedPickupAt(), header.requestedDeliveryAt(),
                header.originHubTerminalId(), header.destinationHubTerminalId(), expectedVersion);
        if (revised.freightMode() == FreightMode.LTL) {
            requireHub(order.orderNo(), "origin", header.originHubTerminalId());
            requireHub(order.orderNo(), "destination", header.destinationHubTerminalId());
        }
        return revised;
    }

    private static void requireDistinctLineNos(List<OrderLineSpec> specs) {
        if (specs.isEmpty()) {
            throw new IllegalArgumentException("An order needs at least one line");
        }
        if (specs.stream().map(OrderLineSpec::lineNo).collect(Collectors.toCollection(HashSet::new))
                .size() != specs.size()) {
            throw new IllegalArgumentException("Line numbers must be distinct within an order");
        }
    }

    /** Deletes the order's lines and stores the new set, in that order, so line numbers can be reused. */
    private List<OrderLine> swapLines(UUID tenantId, UUID orderId, List<OrderLine> existing,
                                      List<OrderLineSpec> specs) {
        lines.deleteAll(existing);
        List<OrderLine> created = new ArrayList<>();
        for (OrderLineSpec spec : specs) {
            created.add(lines.save(new OrderLine(UUID.randomUUID(), tenantId, orderId, spec.lineNo(),
                    spec.materialCode(), spec.materialDescription(),
                    spec.materialClass() == null ? MaterialClass.GENERAL : spec.materialClass(),
                    spec.hazmatUnCode(), spec.quantity(), spec.uom(), spec.deadWeightKg(),
                    spec.lengthM(), spec.widthM(), spec.heightM(), spec.volumetricDivisor(),
                    spec.consigneePartnerId(), spec.destinationTerminalId(), false, null,
                    Instant.now())));
        }
        created.sort(java.util.Comparator.comparingInt(OrderLine::lineNo));
        return created;
    }

    /**
     * Both sides of an edit, from an explicit list of fields so the entry reads
     * the same years later. The lines are a digest, one segment per line, in
     * line order: enough to see what changed without a second table.
     */
    private static Json snapshot(SalesOrder order, List<OrderLine> orderLines) {
        String digest = orderLines.stream()
                .sorted(java.util.Comparator.comparingInt(OrderLine::lineNo))
                .map(l -> l.lineNo() + ":" + l.materialCode() + ":" + l.materialClass() + ":"
                        + l.quantity().stripTrailingZeros().toPlainString() + l.uom() + ":"
                        + l.deadWeightKg().stripTrailingZeros().toPlainString() + "kg:"
                        + (l.hazmatUnCode() == null ? "" : "UN" + l.hazmatUnCode() + ":")
                        + l.consigneePartnerId() + ">" + l.destinationTerminalId())
                .collect(Collectors.joining(";"));
        BigDecimal chargeable = orderLines.stream().map(OrderLine::chargeableWeightKg)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return Json.object("status", order.status().name(),
                "version", String.valueOf(order.version()),
                "customerPartnerId", String.valueOf(order.customerPartnerId()),
                "originTerminalId", order.originTerminalId() == null ? null : order.originTerminalId().toString(),
                "requestedPickupAt", order.requestedPickupAt() == null ? null : order.requestedPickupAt().toString(),
                "requestedDeliveryAt", order.requestedDeliveryAt() == null ? null : order.requestedDeliveryAt().toString(),
                "originHubTerminalId", order.originHubTerminalId() == null ? null : order.originHubTerminalId().toString(),
                "destinationHubTerminalId", order.destinationHubTerminalId() == null ? null : order.destinationHubTerminalId().toString(),
                "lineCount", String.valueOf(orderLines.size()),
                "totalChargeableWeightKg", chargeable.toPlainString(),
                "lines", digest);
    }

    /** Every rule validation applies, reported together. */
    private void requireValid(SalesOrder order, List<OrderLine> orderLines) {
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
