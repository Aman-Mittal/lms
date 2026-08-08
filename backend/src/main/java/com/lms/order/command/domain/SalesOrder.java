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
package com.lms.order.command.domain;

import java.time.Instant;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.lms.shared.error.BusinessRuleViolationException;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.relational.core.mapping.Table;

/**
 * A customer's demand: the indent or sales order of vision document 3.3.
 *
 * <p>The aggregate root for its line items, but the lines are <em>not</em>
 * modelled as a nested collection. Spring Data JDBC would then delete and
 * reinsert every line on each save of the order, which turns a status change
 * into a rewrite of the whole order and invalidates the line identifiers
 * planning is holding. Lines are their own repository, referenced by
 * {@code order_id} (DOCS/adr/0002).
 */
@Table("sales_order")
public record SalesOrder(
        @Id UUID id,
        UUID tenantId,
        UUID orgUnitId,
        UUID customerPartnerId,
        String orderNo,
        OrderStatus status,
        UUID originTerminalId,
        Instant requestedPickupAt,
        Instant requestedDeliveryAt,
        OrderSource source,
        @Version Long version,
        Instant createdAt,
        Instant updatedAt) {

    /** How the demand reached the platform (3.3: manual, bulk upload, or ERP). */
    public enum OrderSource {
        MANUAL, BULK_UPLOAD, API
    }

    public enum OrderStatus {
        DRAFT, VALIDATED, PARTIALLY_PLANNED, FULLY_PLANNED, FULFILLED, CANCELLED
    }

    private static final Map<OrderStatus, Set<OrderStatus>> TRANSITIONS = new EnumMap<>(OrderStatus.class);

    static {
        TRANSITIONS.put(OrderStatus.DRAFT,
                EnumSet.of(OrderStatus.VALIDATED, OrderStatus.CANCELLED));
        TRANSITIONS.put(OrderStatus.VALIDATED,
                EnumSet.of(OrderStatus.PARTIALLY_PLANNED, OrderStatus.FULLY_PLANNED, OrderStatus.CANCELLED));
        // Planning can go backwards: cancelling a load releases its
        // consignments, and an order that was fully planned is then only
        // partially planned again. A forward-only machine would leave the order
        // claiming coverage it no longer has.
        TRANSITIONS.put(OrderStatus.PARTIALLY_PLANNED,
                EnumSet.of(OrderStatus.FULLY_PLANNED, OrderStatus.VALIDATED, OrderStatus.CANCELLED));
        TRANSITIONS.put(OrderStatus.FULLY_PLANNED,
                EnumSet.of(OrderStatus.FULFILLED, OrderStatus.PARTIALLY_PLANNED, OrderStatus.CANCELLED));
        // Terminal. A delivered order is history; a cancelled one is reopened by
        // raising a new order, not by editing this one, so that the audit trail
        // of what was promised stays intact.
        TRANSITIONS.put(OrderStatus.FULFILLED, EnumSet.noneOf(OrderStatus.class));
        TRANSITIONS.put(OrderStatus.CANCELLED, EnumSet.noneOf(OrderStatus.class));
    }

    public static SalesOrder raise(UUID id, UUID tenantId, UUID orgUnitId, UUID customerPartnerId,
                                   String orderNo, UUID originTerminalId,
                                   Instant requestedPickupAt, Instant requestedDeliveryAt,
                                   OrderSource source) {
        if (orderNo == null || orderNo.isBlank()) {
            throw new IllegalArgumentException("Order number is mandatory");
        }
        if (customerPartnerId == null) {
            throw new IllegalArgumentException("An order must name a customer");
        }
        if (requestedPickupAt != null && requestedDeliveryAt != null
                && requestedDeliveryAt.isBefore(requestedPickupAt)) {
            throw new IllegalArgumentException(
                    "Requested delivery " + requestedDeliveryAt
                            + " is before requested pickup " + requestedPickupAt);
        }
        return new SalesOrder(id, tenantId, orgUnitId, customerPartnerId, orderNo,
                OrderStatus.DRAFT, originTerminalId, requestedPickupAt, requestedDeliveryAt,
                source == null ? OrderSource.MANUAL : source, null, Instant.now(), Instant.now());
    }

    /** Whether lines may still be added or amended. */
    public boolean isEditable() {
        return status == OrderStatus.DRAFT;
    }

    /** Whether planning may draw consignments from this order. */
    public boolean isPlannable() {
        return status == OrderStatus.VALIDATED
                || status == OrderStatus.PARTIALLY_PLANNED;
    }

    public SalesOrder transitionTo(OrderStatus target) {
        if (status == target) {
            return this;
        }
        if (!TRANSITIONS.getOrDefault(status, Set.of()).contains(target)) {
            throw new BusinessRuleViolationException("order-illegal-transition",
                    "Order " + orderNo + " cannot move from " + status + " to " + target);
        }
        return new SalesOrder(id, tenantId, orgUnitId, customerPartnerId, orderNo, target,
                originTerminalId, requestedPickupAt, requestedDeliveryAt, source,
                version, createdAt, Instant.now());
    }

    /**
     * Recomputes the planning status from how much of the order is covered.
     *
     * <p>The rule lives here rather than in planning because it is a statement
     * about an order, and because expressing it as "how many lines are spoken
     * for" keeps it true no matter how planning chooses to group them -- one
     * consignment per line or one for the lot.
     *
     * @param plannedLines lines already assigned to a consignment
     * @param totalLines   lines on the order
     */
    public SalesOrder withCoverage(int plannedLines, int totalLines) {
        if (totalLines <= 0) {
            throw new BusinessRuleViolationException("order-has-no-lines",
                    "Order " + orderNo + " has no lines to plan");
        }
        if (plannedLines > totalLines) {
            throw new IllegalArgumentException(
                    "Planned line count " + plannedLines + " exceeds the order's " + totalLines);
        }
        if (plannedLines == 0) {
            return transitionTo(OrderStatus.VALIDATED);
        }
        return transitionTo(plannedLines == totalLines
                ? OrderStatus.FULLY_PLANNED
                : OrderStatus.PARTIALLY_PLANNED);
    }
}
