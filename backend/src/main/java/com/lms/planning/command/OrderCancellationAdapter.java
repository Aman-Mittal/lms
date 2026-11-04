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

import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import com.lms.order.api.OrderCancellationHook;
import com.lms.planning.api.LoadCancellationHook;
import com.lms.planning.api.LoadInUseGuard;
import com.lms.planning.command.PlanningLinks.Placement;
import com.lms.planning.command.domain.Consignment;
import com.lms.planning.command.domain.Consignment.ConsignmentStatus;
import com.lms.planning.command.domain.LoadUnit.LoadStatus;
import com.lms.planning.events.ConsignmentsCancelled;
import com.lms.shared.audit.TransitionAudit;
import com.lms.shared.error.BusinessRuleViolationException;
import com.lms.shared.tenant.TenantContext;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Winds up the freight of an order that is being cancelled.
 *
 * <p>Runs inside the order's cancellation transaction, so either the order
 * and all its freight are cancelled or none of it is. Refuses when any of the
 * freight has left (dispatched, in transit, at a hub, out for delivery or
 * delivered); otherwise cancels the undelivered consignments, takes them off
 * their loads, and cancels a load that is left empty.
 *
 * <p>Two more refusals and one cascade, all from the loads the freight leaves.
 * While a trip that is not cancelled is raised against a load the freight is on,
 * the order cannot be cancelled ({@code load-has-active-trip}, asked of
 * every {@link LoadInUseGuard}; execution implements it): the trip's vendor,
 * vehicle and driver are committed to that freight even before it is dispatched.
 * And a load this cancellation leaves empty is cancelled, so the
 * {@link LoadCancellationHook}s (sourcing) withdraw its pending offer and
 * cancel its allocation, awarded or not, in this same transaction.
 *
 * <p>Statements are bounded by the number of consignments: one read of the
 * order's consignments, one of their placements, one delete of their load
 * links, one update per consignment, and per touched load one read and one
 * update. Nothing is looked up per consignment. The audit is one insert for
 * the consignments, one for the loads, and an event is one fact for the
 * whole cancellation, however many consignments it covers.
 */
@Service
public class OrderCancellationAdapter implements OrderCancellationHook {

    /** A consignment in one of these has physically moved, whatever its load says. */
    private static final Set<ConsignmentStatus> MOVED = EnumSet.of(
            ConsignmentStatus.IN_TRANSIT, ConsignmentStatus.ARRIVED_AT_HUB,
            ConsignmentStatus.ASSIGNED_TO_DELIVERY, ConsignmentStatus.OUT_FOR_DELIVERY,
            ConsignmentStatus.DELIVERED);

    /**
     * A full truckload dispatched before dispatch moved its consignments to
     * IN_TRANSIT still reads ASSIGNED_TO_LOAD on the road, so the load's own
     * status is what says it has left.
     */
    private static final Set<String> LEFT = Set.of(LoadStatus.DISPATCHED.name(),
            LoadStatus.COMPLETED.name());

    private final ConsignmentRepository consignments;
    private final PlanningLinks links;
    private final List<LoadInUseGuard> inUseGuards;
    private final LoadRelease loadRelease;
    private final TransitionAudit audit;
    private final ApplicationEventPublisher events;

    OrderCancellationAdapter(ConsignmentRepository consignments, PlanningLinks links,
                             List<LoadInUseGuard> inUseGuards, LoadRelease loadRelease,
                             TransitionAudit audit, ApplicationEventPublisher events) {
        this.consignments = consignments;
        this.links = links;
        this.inUseGuards = inUseGuards;
        this.loadRelease = loadRelease;
        this.audit = audit;
        this.events = events;
    }

    @Override
    @Transactional
    public void onOrderCancelling(UUID orderId) {
        UUID tenantId = TenantContext.requireTenantId();

        List<Consignment> all = consignments.findByOrder(tenantId, orderId);
        if (all.isEmpty()) {
            return;
        }
        List<Placement> placements = links.placementsOfOrder(tenantId, orderId);

        refuseIfLeft(orderId, all, placements);

        // CANCELLED ones are skipped, which is what makes a repeat harmless. A
        // SPLIT one is skipped too: it carries nothing, and its parts -- cut
        // from the same order -- are in this list and cancelled in its place.
        List<Consignment> toCancel = all.stream()
                .filter(c -> c.status() != ConsignmentStatus.CANCELLED
                        && c.status() != ConsignmentStatus.SPLIT).toList();
        Set<UUID> cancelling = toCancel.stream().map(Consignment::id)
                .collect(Collectors.toSet());
        Set<UUID> touchedLoads = placements.stream()
                .filter(p -> cancelling.contains(p.consignmentId()))
                .map(Placement::loadId)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        // Before anything is written: a trip raised against a load the freight
        // is on refuses the whole cancellation.
        if (!touchedLoads.isEmpty()) {
            inUseGuards.forEach(guard -> guard.requireNotInUse(touchedLoads));
        }

        links.unlinkAll(tenantId, cancelling);
        List<Consignment> cancelled = new ArrayList<>();
        for (Consignment consignment : toCancel) {
            cancelled.add(consignments.save(consignment.transitionTo(ConsignmentStatus.CANCELLED)));
        }
        // Audited before the loads are touched, so that a failure further on
        // rolls these rows back with everything else.
        audit.recordAll("CONSIGNMENT_CANCELLED", PlanningAudit.CONSIGNMENT,
                PlanningAudit.entries(toCancel, cancelled));
        loadRelease.release(tenantId, touchedLoads);
        if (!cancelled.isEmpty()) {
            events.publishEvent(new ConsignmentsCancelled(tenantId, orderId,
                    cancelled.stream().map(Consignment::id).toList(),
                    cancelled.stream().filter(Consignment::isDocket).map(Consignment::id).toList(),
                    Instant.now()));
        }
    }

    private static void refuseIfLeft(UUID orderId, List<Consignment> all,
                                     List<Placement> placements) {
        Set<UUID> onLoadThatLeft = placements.stream()
                .filter(p -> LEFT.contains(p.loadStatus()))
                .map(Placement::consignmentId)
                .collect(Collectors.toSet());
        List<String> left = all.stream()
                .filter(c -> MOVED.contains(c.status()) || onLoadThatLeft.contains(c.id()))
                .map(Consignment::trackingRef)
                .toList();
        if (!left.isEmpty()) {
            throw new BusinessRuleViolationException("order-work-dispatched",
                    "Order " + orderId + " cannot be cancelled: consignment(s) "
                            + String.join(", ", left)
                            + " already dispatched, in transit or delivered");
        }
    }
}
