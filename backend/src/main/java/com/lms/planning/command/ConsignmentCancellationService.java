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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.lms.order.api.OrderPlanningPort;
import com.lms.planning.api.LoadInUseGuard;
import com.lms.planning.command.PlanningLinks.Placement;
import com.lms.planning.command.domain.Consignment;
import com.lms.planning.command.domain.Consignment.ConsignmentStatus;
import com.lms.planning.command.domain.LoadUnit.LoadStatus;
import com.lms.planning.events.ConsignmentCancelled;
import com.lms.planning.events.ConsignmentsCancelled;
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
 * Cancels one consignment, leaving its order standing.
 *
 * <p>What cancelling an order does to its freight, for one consignment: taken
 * off its load first if it is on one, by the same removal as taking it off by
 * hand ({@link LoadRelease#takeOff}: drops numbered again, totals restated from
 * what is left, audited on the load), except that a load left with nothing is
 * cancelled rather than refused; then cancelled, audited,
 * and its order lines given back to the order, whose coverage steps back to
 * match ({@link OrderPlanningPort#releaseLines}). The planner can then
 * generate again from those lines.
 *
 * <p>Refused, each by its code: see {@link Consignment#requireCancellable} for
 * the consignment's own refusals; {@code consignment-dispatched} too when the
 * load it is on has left, even if the consignment has not yet been moved on;
 * and {@code load-has-active-trip} while a trip that is not cancelled is raised
 * against that load (every {@link LoadInUseGuard}).
 */
@Service
public class ConsignmentCancellationService {

    /** Statuses of a load that has left with its freight. */
    private static final Set<String> LEFT = Set.of(LoadStatus.DISPATCHED.name(),
            LoadStatus.COMPLETED.name());

    /**
     * What a cancellation did.
     *
     * @param load the load it was taken off, as that load now stands; null
     *             when it was on none
     */
    public record Cancellation(UUID consignmentId, String trackingRef, List<UUID> releasedLineIds,
                               LoadAfter load) {
    }

    /** A load's totals after the consignment came off it, and whether that cancelled it. */
    public record LoadAfter(UUID loadId, String loadNo, String status, BigDecimal plannedWeightKg,
                            BigDecimal plannedVolumeM3) {
    }

    private final ConsignmentRepository consignments;
    private final PlanningLinks links;
    private final List<LoadInUseGuard> inUseGuards;
    private final LoadRelease loadRelease;
    private final OrderPlanningPort orderPlanning;
    private final TransitionAudit audit;
    private final ApplicationEventPublisher events;

    ConsignmentCancellationService(ConsignmentRepository consignments, PlanningLinks links,
                                   List<LoadInUseGuard> inUseGuards, LoadRelease loadRelease,
                                   OrderPlanningPort orderPlanning, TransitionAudit audit,
                                   ApplicationEventPublisher events) {
        this.consignments = consignments;
        this.links = links;
        this.inUseGuards = inUseGuards;
        this.loadRelease = loadRelease;
        this.orderPlanning = orderPlanning;
        this.audit = audit;
        this.events = events;
    }

    /**
     * Cancels the consignment; see the class for what that does and refuses.
     *
     * @param reason why, for the audit trail and the event; optional
     */
    @Transactional
    @PreAuthorize("hasAuthority('LOAD_CREATE')")
    public Cancellation cancel(UUID consignmentId, String reason) {
        Consignment consignment = consignments.findById(consignmentId)
                .orElseThrow(() -> new ResourceNotFoundException("Consignment", consignmentId));
        return cancelWithin(consignment, reason);
    }

    /**
     * Cancels a consignment inside the caller's transaction; the caller has
     * read it, and checked the caller's right to.
     */
    Cancellation cancelWithin(Consignment consignment, String reason) {
        UUID tenantId = TenantContext.requireTenantId();
        String why = reason == null || reason.isBlank() ? null : reason.strip();
        consignment.requireCancellable();

        List<Placement> placements = links.placementsOf(tenantId, consignment.id());
        if (placements.stream().anyMatch(p -> LEFT.contains(p.loadStatus()))) {
            throw new BusinessRuleViolationException("consignment-dispatched",
                    "Consignment " + consignment.trackingRef() + " is on a load that has left "
                            + "and can no longer be cancelled");
        }
        Set<UUID> loadIds = new LinkedHashSet<>();
        placements.forEach(p -> loadIds.add(p.loadId()));
        if (!loadIds.isEmpty()) {
            inUseGuards.forEach(guard -> guard.requireNotInUse(loadIds));
        }
        UUID loadId = loadIds.isEmpty() ? null : loadIds.iterator().next();

        // Off the load first, through the removal every path shares: drops
        // numbered again, totals restated from what stays aboard, audited on the
        // load. Unlike a removal, a load this empties is cancelled.
        List<LoadRelease.Released> released = new ArrayList<>();
        for (UUID onLoad : loadIds) {
            LoadRelease.Released after = loadRelease.takeOff(tenantId, onLoad, consignment, true);
            if (after != null) {
                released.add(after);
            }
        }
        Consignment cancelled = consignments.save(consignment.transitionTo(ConsignmentStatus.CANCELLED));
        audit.record("CONSIGNMENT_CANCELLED", PlanningAudit.CONSIGNMENT, cancelled.id(),
                PlanningAudit.consignmentOn(consignment, loadId),
                Json.object("status", cancelled.status().name(),
                        "trackingRef", cancelled.trackingRef(),
                        "orderId", cancelled.orderId() == null ? null : cancelled.orderId().toString(),
                        "removedFromLoadId", loadId == null ? null : loadId.toString(),
                        "reason", why));

        List<UUID> lineIds = links.orderLinesOf(tenantId, consignment.id());
        links.unlinkLines(tenantId, consignment.id());
        if (consignment.orderId() != null && !lineIds.isEmpty()) {
            orderPlanning.releaseLines(consignment.orderId(), new LinkedHashSet<>(lineIds));
        }

        Instant now = Instant.now();
        events.publishEvent(new ConsignmentsCancelled(tenantId, consignment.orderId(),
                List.of(consignment.id()),
                consignment.isDocket() ? List.of(consignment.id()) : List.of(), now));
        events.publishEvent(new ConsignmentCancelled(tenantId, consignment.id(),
                consignment.trackingRef(), consignment.orderId(), consignment.isDocket(), loadId,
                why, now));

        LoadAfter load = released.stream()
                .filter(r -> r.loadId().equals(loadId))
                .findFirst()
                .map(r -> new LoadAfter(r.loadId(), r.loadNo(), r.status().name(),
                        r.plannedWeightKg(), r.plannedVolumeM3()))
                .orElse(null);
        return new Cancellation(consignment.id(), consignment.trackingRef(), List.copyOf(lineIds), load);
    }
}
