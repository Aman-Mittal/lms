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

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import com.lms.order.api.OrderCancellationHook;
import com.lms.planning.api.LoadCancellationHook;
import com.lms.planning.api.LoadInUseGuard;
import com.lms.planning.command.PlanningLinks.Placement;
import com.lms.planning.command.domain.Consignment;
import com.lms.planning.command.domain.Consignment.ConsignmentStatus;
import com.lms.planning.command.domain.LoadUnit;
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
     * A full truckload's consignments stay ASSIGNED_TO_LOAD while the load is
     * on the road, so the load's own status is what says it has left.
     */
    private static final Set<String> LEFT = Set.of(LoadStatus.DISPATCHED.name(),
            LoadStatus.COMPLETED.name());

    private final ConsignmentRepository consignments;
    private final LoadUnitRepository loads;
    private final PlanningLinks links;
    private final List<LoadInUseGuard> inUseGuards;
    private final List<LoadCancellationHook> cancellationHooks;
    private final TransitionAudit audit;
    private final ApplicationEventPublisher events;

    public OrderCancellationAdapter(ConsignmentRepository consignments, LoadUnitRepository loads,
                                    PlanningLinks links, List<LoadInUseGuard> inUseGuards,
                                    List<LoadCancellationHook> cancellationHooks,
                                    TransitionAudit audit, ApplicationEventPublisher events) {
        this.consignments = consignments;
        this.loads = loads;
        this.links = links;
        this.inUseGuards = inUseGuards;
        this.cancellationHooks = cancellationHooks;
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

        // CANCELLED ones are skipped, which is what makes a repeat harmless.
        List<Consignment> toCancel = all.stream()
                .filter(c -> c.status() != ConsignmentStatus.CANCELLED).toList();
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
        List<UUID> cancelledLoads = releaseLoads(tenantId, touchedLoads);
        if (!cancelledLoads.isEmpty()) {
            cancellationHooks.forEach(hook -> hook.onLoadsCancelling(cancelledLoads));
        }
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

    /**
     * Restates each touched load from the freight still aboard, and cancels
     * one that is left with none.
     *
     * @return the loads this cancelled, which sourcing is told about
     */
    private List<UUID> releaseLoads(UUID tenantId, Set<UUID> loadIds) {
        if (loadIds.isEmpty()) {
            return List.of();
        }
        List<UUID> cancelledLoads = new ArrayList<>();
        List<TransitionAudit.Entry> cancelledEntries = new ArrayList<>();
        Map<UUID, LoadUnit> touched = new java.util.HashMap<>();
        loads.findAllById(loadIds).forEach(load -> touched.put(load.id(), load));
        for (UUID loadId : loadIds) {
            LoadUnit load = touched.get(loadId);
            if (load == null) {
                continue;
            }
            List<Consignment> aboard = consignments.findOnLoad(tenantId, loadId);
            LoadUnit restated = load.restated(
                    sum(aboard, Consignment::totalDeadWeightKg),
                    sum(aboard, Consignment::totalVolumeM3),
                    aboard.stream().anyMatch(Consignment::containsHazmat));
            if (aboard.isEmpty()) {
                LoadUnit cancelledLoad = loads.save(restated.transitionTo(LoadStatus.CANCELLED));
                cancelledLoads.add(loadId);
                cancelledEntries.add(PlanningAudit.entry(load, cancelledLoad));
            } else {
                loads.save(restated);
            }
        }
        audit.recordAll("LOAD_CANCELLED", PlanningAudit.LOAD, cancelledEntries);
        return cancelledLoads;
    }

    private static BigDecimal sum(List<Consignment> aboard,
                                  java.util.function.Function<Consignment, BigDecimal> field) {
        return aboard.stream().map(field).filter(java.util.Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
