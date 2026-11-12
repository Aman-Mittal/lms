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

import com.lms.order.api.FreightMode;
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
        /**
         * FTL unless stated. Fixed when the order is raised: changing the mode
         * of an order already planned would leave consignments numbered,
         * consolidated and priced under the other mode's rules.
         */
        FreightMode freightMode,
        /** Where an LTL booking enters the network; null for FTL. */
        UUID originHubTerminalId,
        /** Where an LTL booking leaves the line haul for delivery; null for FTL. */
        UUID destinationHubTerminalId,
        @Version Long version,
        Instant createdAt,
        Instant updatedAt,
        /** How a CLOSED order was closed; null for every other status. */
        CloseKind closeKind,
        /** Why, as the person closing it said; null when none was needed. */
        String closeReason,
        Instant closedAt,
        /** The client contract version the order was resolved against; null when none was in force. */
        UUID contractId,
        /** The lane of that contract the order travels on; null for a direct order or when none applies. */
        UUID contractLaneId,
        /** The contract as a person means it, shared by its versions: what a CONTRACT-scoped setting names. */
        UUID contractFamilyId,
        /** The load or vehicle type the order is booked for, upper case; null when none is stated. */
        String loadType,
        /** Days the movement is promised to take: the lane's, else from distance; null while unknown. */
        Integer transitDays,
        /** True when a person named the lane, so it is held to and not replaced when the order is judged again. */
        boolean laneChosen) {

    /** How the demand reached the platform (3.3: manual, bulk upload, or ERP). */
    public enum OrderSource {
        MANUAL, BULK_UPLOAD, API
    }

    public enum OrderStatus {
        DRAFT, VALIDATED, PARTIALLY_PLANNED, FULLY_PLANNED, FULFILLED, CANCELLED, CLOSED
    }

    private static final Map<OrderStatus, Set<OrderStatus>> TRANSITIONS = new EnumMap<>(OrderStatus.class);

    static {
        TRANSITIONS.put(OrderStatus.DRAFT,
                EnumSet.of(OrderStatus.VALIDATED, OrderStatus.CANCELLED));
        TRANSITIONS.put(OrderStatus.VALIDATED,
                EnumSet.of(OrderStatus.PARTIALLY_PLANNED, OrderStatus.FULLY_PLANNED, OrderStatus.CANCELLED,
                        OrderStatus.CLOSED));
        // Planning can go backwards: cancelling a load releases its
        // consignments, and an order that was fully planned is then only
        // partially planned again. A forward-only machine would leave the order
        // claiming coverage it no longer has.
        TRANSITIONS.put(OrderStatus.PARTIALLY_PLANNED,
                EnumSet.of(OrderStatus.FULLY_PLANNED, OrderStatus.VALIDATED, OrderStatus.CANCELLED,
                        OrderStatus.CLOSED));
        // Back to VALIDATED when the only consignment cut from it is cancelled
        // on its own: its lines are demand waiting to be planned again.
        TRANSITIONS.put(OrderStatus.FULLY_PLANNED,
                EnumSet.of(OrderStatus.FULFILLED, OrderStatus.PARTIALLY_PLANNED, OrderStatus.VALIDATED,
                        OrderStatus.CANCELLED, OrderStatus.CLOSED));
        // Terminal. A delivered order is history; a cancelled one is reopened by
        // raising a new order, not by editing this one, so that the audit trail
        // of what was promised stays intact.
        TRANSITIONS.put(OrderStatus.FULFILLED, EnumSet.noneOf(OrderStatus.class));
        TRANSITIONS.put(OrderStatus.CANCELLED, EnumSet.noneOf(OrderStatus.class));
        // Closed by hand, or by the platform within the tenant's tolerance: what
        // was not carried never will be. Terminal for the same reason.
        TRANSITIONS.put(OrderStatus.CLOSED, EnumSet.noneOf(OrderStatus.class));
    }

    /** A full-truckload order: the behaviour every order had before freight modes. */
    public static SalesOrder raise(UUID id, UUID tenantId, UUID orgUnitId, UUID customerPartnerId,
                                   String orderNo, UUID originTerminalId,
                                   Instant requestedPickupAt, Instant requestedDeliveryAt,
                                   OrderSource source) {
        return raise(id, tenantId, orgUnitId, customerPartnerId, orderNo, originTerminalId,
                requestedPickupAt, requestedDeliveryAt, source, FreightMode.FTL, null, null);
    }

    /**
     * An order in a stated freight mode.
     *
     * <p>Reserved modes are refused rather than stored and ignored. An order
     * accepted as RAIL and then planned onto a lorry would be a promise the
     * platform quietly broke.
     *
     * <p>An LTL order must name both hubs, and an FTL order must name neither.
     * The hubs are what a docket is consolidated and rated on; on a full
     * truckload they would be data nothing reads, and an operator who filled
     * them in would reasonably believe they had changed something.
     */
    public static SalesOrder raise(UUID id, UUID tenantId, UUID orgUnitId, UUID customerPartnerId,
                                   String orderNo, UUID originTerminalId,
                                   Instant requestedPickupAt, Instant requestedDeliveryAt,
                                   OrderSource source, FreightMode freightMode,
                                   UUID originHubTerminalId, UUID destinationHubTerminalId) {
        return raise(id, tenantId, orgUnitId, customerPartnerId, orderNo, originTerminalId, requestedPickupAt,
                requestedDeliveryAt, source, freightMode, originHubTerminalId, destinationHubTerminalId, null);
    }

    /** As the other {@code raise}, booked for a load or vehicle type (null when none is stated). */
    public static SalesOrder raise(UUID id, UUID tenantId, UUID orgUnitId, UUID customerPartnerId,
                                   String orderNo, UUID originTerminalId,
                                   Instant requestedPickupAt, Instant requestedDeliveryAt,
                                   OrderSource source, FreightMode freightMode,
                                   UUID originHubTerminalId, UUID destinationHubTerminalId, String loadType) {
        if (orderNo == null || orderNo.isBlank()) {
            throw new IllegalArgumentException("Order number is mandatory");
        }
        FreightMode mode = requireCoherent(orderNo, customerPartnerId, requestedPickupAt,
                requestedDeliveryAt, freightMode, originHubTerminalId, destinationHubTerminalId);
        return new SalesOrder(id, tenantId, orgUnitId, customerPartnerId, orderNo,
                OrderStatus.DRAFT, originTerminalId, requestedPickupAt, requestedDeliveryAt,
                source == null ? OrderSource.MANUAL : source, mode,
                originHubTerminalId, destinationHubTerminalId, null, Instant.now(), Instant.now(), null, null, null,
                null, null, null, normaliseLoadType(loadType), null, false);
    }

    /**
     * The shape rules shared by raising and revising: a customer, a delivery
     * no earlier than the pickup, a supported mode, and hubs exactly when the
     * mode is LTL.
     *
     * @return the mode, defaulted
     */
    private static FreightMode requireCoherent(String orderNo, UUID customerPartnerId,
                                               Instant requestedPickupAt, Instant requestedDeliveryAt,
                                               FreightMode freightMode, UUID originHubTerminalId,
                                               UUID destinationHubTerminalId) {
        if (customerPartnerId == null) {
            throw new IllegalArgumentException("An order must name a customer");
        }
        if (requestedPickupAt != null && requestedDeliveryAt != null
                && requestedDeliveryAt.isBefore(requestedPickupAt)) {
            throw new IllegalArgumentException(
                    "Requested delivery " + requestedDeliveryAt
                            + " is before requested pickup " + requestedPickupAt);
        }
        FreightMode mode = FreightMode.orDefault(freightMode);
        if (!mode.isSupported()) {
            throw new BusinessRuleViolationException("freight-mode-not-supported",
                    "Freight mode " + mode + " is reserved and cannot be booked yet");
        }
        boolean namesHubs = originHubTerminalId != null || destinationHubTerminalId != null;
        if (mode == FreightMode.LTL
                && (originHubTerminalId == null || destinationHubTerminalId == null)) {
            throw new BusinessRuleViolationException("ltl-order-needs-hubs",
                    "Part-load order " + orderNo + " must name both an origin and a destination hub");
        }
        if (mode == FreightMode.FTL && namesHubs) {
            throw new BusinessRuleViolationException("hubs-only-for-ltl",
                    "Order " + orderNo + " is a full truckload, which does not travel through hubs");
        }
        return mode;
    }

    /**
     * The order with its commercial header replaced and its status kept.
     *
     * <p>The order number, organisational unit, source and freight mode never
     * change here: the number is how people refer to the order, the unit is
     * where it lives for access decisions, and the mode decides how freight
     * cut from it is numbered, consolidated and priced.
     *
     * @param expectedVersion the version the caller last read; the save is an
     *                        update guarded on it, so a stale value loses
     */
    public SalesOrder revised(UUID newCustomerPartnerId, UUID newOriginTerminalId,
                              Instant newPickupAt, Instant newDeliveryAt,
                              UUID newOriginHubTerminalId, UUID newDestinationHubTerminalId,
                              Long expectedVersion) {
        return revised(newCustomerPartnerId, newOriginTerminalId, newPickupAt, newDeliveryAt,
                newOriginHubTerminalId, newDestinationHubTerminalId, loadType, expectedVersion);
    }

    /**
     * As the other {@code revised}, restating the load type. What the order
     * resolved against its contract is kept as it was; the service resolves a
     * changed header afresh ({@link #withContract}) before it saves.
     */
    public SalesOrder revised(UUID newCustomerPartnerId, UUID newOriginTerminalId,
                              Instant newPickupAt, Instant newDeliveryAt,
                              UUID newOriginHubTerminalId, UUID newDestinationHubTerminalId,
                              String newLoadType, Long expectedVersion) {
        requireCoherent(orderNo, newCustomerPartnerId, newPickupAt, newDeliveryAt, freightMode,
                newOriginHubTerminalId, newDestinationHubTerminalId);
        return new SalesOrder(id, tenantId, orgUnitId, newCustomerPartnerId, orderNo, status,
                newOriginTerminalId, newPickupAt, newDeliveryAt, source, freightMode,
                newOriginHubTerminalId, newDestinationHubTerminalId,
                expectedVersion, createdAt, Instant.now(), closeKind, closeReason, closedAt,
                contractId, contractLaneId, contractFamilyId, normaliseLoadType(newLoadType), transitDays, laneChosen);
    }

    /** Upper case and trimmed; blank is none. */
    public static String normaliseLoadType(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String clean = value.strip().toUpperCase(java.util.Locale.ROOT);
        if (clean.length() > 60) {
            throw new IllegalArgumentException("A load type is at most 60 characters");
        }
        return clean;
    }

    /**
     * The order with what it resolved against its contract recorded: the
     * version, its family, the lane and the promised days. All null clears it.
     */
    public SalesOrder withContract(UUID newContractId, UUID newContractFamilyId, UUID newContractLaneId,
                                   Integer newTransitDays, boolean newLaneChosen) {
        return new SalesOrder(id, tenantId, orgUnitId, customerPartnerId, orderNo, status,
                originTerminalId, requestedPickupAt, requestedDeliveryAt, source, freightMode,
                originHubTerminalId, destinationHubTerminalId, version, createdAt, Instant.now(),
                closeKind, closeReason, closedAt, newContractId, newContractLaneId, newContractFamilyId,
                loadType, newTransitDays, newLaneChosen);
    }

    /** The same order, its version replaced by the one the caller says it read. */
    public SalesOrder expecting(Long expectedVersion) {
        return new SalesOrder(id, tenantId, orgUnitId, customerPartnerId, orderNo, status,
                originTerminalId, requestedPickupAt, requestedDeliveryAt, source, freightMode,
                originHubTerminalId, destinationHubTerminalId,
                expectedVersion, createdAt, Instant.now(), closeKind, closeReason, closedAt,
                contractId, contractLaneId, contractFamilyId, loadType, transitDays, laneChosen);
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
                freightMode, originHubTerminalId, destinationHubTerminalId,
                version, createdAt, Instant.now(), closeKind, closeReason, closedAt,
                contractId, contractLaneId, contractFamilyId, loadType, transitDays, laneChosen);
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
        return withCoverage(plannedLines, 0, totalLines);
    }

    /**
     * As {@link #withCoverage(int, int)}, counting lines closed without being
     * planned as spoken for: nothing more will be cut for them, so an order
     * whose other lines are all planned is fully planned. An order with
     * nothing planned stays VALIDATED whatever was closed.
     *
     * @param closedUnplannedLines lines closed before any of them was planned
     */
    public SalesOrder withCoverage(int plannedLines, int closedUnplannedLines, int totalLines) {
        if (totalLines <= 0) {
            throw new BusinessRuleViolationException("order-has-no-lines",
                    "Order " + orderNo + " has no lines to plan");
        }
        if (plannedLines + closedUnplannedLines > totalLines) {
            throw new IllegalArgumentException(
                    "Planned line count " + plannedLines + " exceeds the order's " + totalLines);
        }
        if (plannedLines == 0) {
            return transitionTo(OrderStatus.VALIDATED);
        }
        return transitionTo(plannedLines + closedUnplannedLines == totalLines
                ? OrderStatus.FULLY_PLANNED
                : OrderStatus.PARTIALLY_PLANNED);
    }

    /** Whether the order is open to being closed: validated and not yet at an end. */
    public boolean isClosable() {
        return status == OrderStatus.VALIDATED || status == OrderStatus.PARTIALLY_PLANNED
                || status == OrderStatus.FULLY_PLANNED;
    }

    /**
     * The order, closed. The state machine decides whether it may be; the
     * caller has already decided whether it should.
     */
    public SalesOrder closed(CloseKind kind, String reason, Instant at) {
        if (status == OrderStatus.CLOSED) {
            // Not the state machine's "already there" no-op: a second close
            // would overwrite how and why the first one closed it.
            throw new BusinessRuleViolationException("order-illegal-transition",
                    "Order " + orderNo + " is already CLOSED");
        }
        SalesOrder moved = transitionTo(OrderStatus.CLOSED);
        return new SalesOrder(id, tenantId, orgUnitId, customerPartnerId, orderNo, moved.status,
                originTerminalId, requestedPickupAt, requestedDeliveryAt, source,
                freightMode, originHubTerminalId, destinationHubTerminalId,
                version, createdAt, at, kind, reason, at,
                contractId, contractLaneId, contractFamilyId, loadType, transitDays, laneChosen);
    }
}
