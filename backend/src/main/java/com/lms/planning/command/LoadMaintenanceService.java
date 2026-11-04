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
import java.util.List;
import java.util.UUID;

import com.lms.planning.api.LoadAllocationGuard;
import com.lms.planning.api.LoadCancellationHook;
import com.lms.planning.api.LoadInUseGuard;
import com.lms.planning.command.domain.Consignment;
import com.lms.planning.command.domain.LoadUnit;
import com.lms.planning.command.domain.LoadUnit.LoadStatus;
import com.lms.planning.events.ConsignmentRemovedFromLoad;
import com.lms.planning.events.LoadCancelled;
import com.lms.planning.events.LoadUnplanned;
import com.lms.shared.audit.TransitionAudit;
import com.lms.shared.error.BusinessRuleViolationException;
import com.lms.shared.error.ResourceNotFoundException;
import com.lms.shared.tenant.TenantContext;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Changing a load after it was built: taking a consignment off, opening a
 * planned load for building again, and cancelling a load outright.
 *
 * <p>All three are guarded by the version the caller last read, as order
 * edits are: a stale version is {@code concurrent-modification} before any
 * work is done, and the versioned save of the load settles a race between two
 * callers that read the same version. Every change to a load's freight saves
 * the load, so two planners working the same vehicle -- removing, adding in
 * bulk, planning -- cannot both win.
 *
 * <p>Nothing changes once the load has left ({@code load-dispatched}). An
 * awarded load keeps its freight ({@code load-awarded}), and a planned one
 * that is out with a vendor too ({@code load-allocated}, asked of
 * {@link LoadAllocationGuard}): the allocation is cancelled first, in
 * sourcing, which also takes an award back. Cancelling the load is the
 * exception, because it is the end of the vendor's interest anyway: it takes
 * the allocation down with it through {@link LoadCancellationHook}, as a load
 * emptied by an order's cancellation does, unless a trip is already raised
 * against it ({@code load-has-active-trip}).
 *
 * <p>A consignment taken off goes back to the status it boarded from --
 * PLANNED, or ARRIVED_AT_HUB for a docket on a delivery load -- and the drops
 * left behind are numbered again without a gap. The load's totals are
 * restated from what is still aboard. That removal is {@link LoadRelease}'s,
 * shared with cancelling a consignment, so the two read the same on the load;
 * they differ only in what an emptied load becomes -- removal refuses to empty
 * a PLANNED load, while a cancellation cancels the load it empties.
 */
@Service
public class LoadMaintenanceService {

    private final LoadUnitRepository loads;
    private final ConsignmentRepository consignments;
    private final PlanningLinks links;
    private final LoadRelease loadRelease;
    private final List<LoadAllocationGuard> allocationGuards;
    private final List<LoadInUseGuard> inUseGuards;
    private final List<LoadCancellationHook> cancellationHooks;
    private final TransitionAudit audit;
    private final ApplicationEventPublisher events;

    public LoadMaintenanceService(LoadUnitRepository loads, ConsignmentRepository consignments,
                                  PlanningLinks links, LoadRelease loadRelease,
                                  List<LoadAllocationGuard> allocationGuards,
                                  List<LoadInUseGuard> inUseGuards,
                                  List<LoadCancellationHook> cancellationHooks,
                                  TransitionAudit audit, ApplicationEventPublisher events) {
        this.loads = loads;
        this.consignments = consignments;
        this.links = links;
        this.loadRelease = loadRelease;
        this.allocationGuards = allocationGuards;
        this.inUseGuards = inUseGuards;
        this.cancellationHooks = cancellationHooks;
        this.audit = audit;
        this.events = events;
    }

    /**
     * Takes one consignment off a load that has not been awarded or left.
     *
     * <p>Refusals, beyond those every change makes: {@code consignment-not-on-load}
     * when it is not aboard this load (including a repeat of a removal that
     * went through), and {@code load-would-be-empty} for the last consignment
     * of a PLANNED load, which would leave an empty load open to sourcing --
     * un-plan it first, or cancel it.
     */
    @Transactional
    @PreAuthorize("hasAuthority('LOAD_CREATE')")
    public void removeConsignment(UUID loadId, UUID consignmentId, long expectedVersion) {
        UUID tenantId = TenantContext.requireTenantId();
        LoadUnit load = require(loadId);
        Consignment consignment = consignments.findById(consignmentId)
                .orElseThrow(() -> new ResourceNotFoundException("Consignment", consignmentId));
        requireVersion(load, expectedVersion);
        load.requireFreightRemovable();

        List<Consignment> aboard = consignments.findOnLoad(tenantId, loadId);
        if (aboard.stream().noneMatch(c -> c.id().equals(consignmentId))) {
            throw new BusinessRuleViolationException("consignment-not-on-load",
                    "Consignment " + consignment.trackingRef() + " is not on load " + load.loadNo());
        }
        if (load.status() == LoadStatus.PLANNED) {
            allocationGuards.forEach(guard -> guard.requireNotAllocated(loadId));
            if (aboard.size() == 1) {
                throw new BusinessRuleViolationException("load-would-be-empty",
                        "Consignment " + consignment.trackingRef() + " is the last on planned load "
                                + load.loadNo() + "; un-plan or cancel the load instead");
            }
        }

        // The load first, through the removal every path shares: drops numbered
        // again, totals restated, audited. Its versioned save is what a
        // concurrent change to the same load loses on.
        LoadRelease.Released restated = loadRelease.takeOff(tenantId, load, aboard, consignment, false);
        Consignment saved = consignments.save(consignment.releasedFromLoad());

        audit.record("CONSIGNMENT_REMOVED_FROM_LOAD", PlanningAudit.CONSIGNMENT, consignmentId,
                PlanningAudit.consignmentOn(consignment, loadId), PlanningAudit.consignment(saved));

        events.publishEvent(new ConsignmentRemovedFromLoad(tenantId, loadId, load.loadNo(),
                consignmentId, consignment.trackingRef(), saved.status().name(),
                restated.plannedWeightKg(), restated.plannedVolumeM3(), restated.requiresHazmat(),
                restated.consignmentsLeft(), Instant.now()));
    }

    /**
     * Opens a PLANNED load for building again: back to DRAFT, its freight
     * untouched. Refused for a load that is not PLANNED ({@code load-not-planned}),
     * awarded or out with a vendor.
     */
    @Transactional
    @PreAuthorize("hasAuthority('LOAD_CREATE')")
    public void unplanLoad(UUID loadId, long expectedVersion) {
        UUID tenantId = TenantContext.requireTenantId();
        LoadUnit load = require(loadId);
        requireVersion(load, expectedVersion);
        LoadUnit draft = load.unplanned();
        allocationGuards.forEach(guard -> guard.requireNotAllocated(loadId));

        LoadUnit saved = loads.save(draft);
        audit.record("LOAD_UNPLANNED", PlanningAudit.LOAD, loadId, PlanningAudit.load(load),
                PlanningAudit.load(saved));
        events.publishEvent(new LoadUnplanned(tenantId, loadId, load.loadNo(), Instant.now()));
    }

    /**
     * Cancels a load that has not left, releasing everything aboard to be
     * planned again and withdrawing its allocation, offered or awarded.
     *
     * <p>Cancelled is final ({@code load-already-cancelled} on a repeat). The
     * consignments are not cancelled: their orders still want them carried.
     *
     * @param reason why, kept in the audit trail; may be null
     */
    @Transactional
    @PreAuthorize("hasAuthority('LOAD_CREATE')")
    public void cancelLoad(UUID loadId, long expectedVersion, String reason) {
        UUID tenantId = TenantContext.requireTenantId();
        LoadUnit load = require(loadId);
        requireVersion(load, expectedVersion);
        load.requireCancellable();
        // Before anything is written: a trip raised against the load has a
        // vendor, a vehicle and a driver committed to it.
        inUseGuards.forEach(guard -> guard.requireNotInUse(List.of(loadId)));

        List<Consignment> aboard = consignments.findOnLoad(tenantId, loadId);
        List<Consignment> released = aboard.stream().map(Consignment::releasedFromLoad).toList();
        links.unlinkLoad(tenantId, loadId);

        LoadUnit cancelled = loads.save(load.restated(BigDecimal.ZERO, BigDecimal.ZERO, false)
                .transitionTo(LoadStatus.CANCELLED));
        List<Consignment> saved = new ArrayList<>();
        consignments.saveAll(released).forEach(saved::add);
        cancellationHooks.forEach(hook -> hook.onLoadsCancelling(List.of(loadId)));

        audit.record("LOAD_CANCELLED", PlanningAudit.LOAD, loadId,
                PlanningAudit.loadContents(load, aboard.size(), "reason", null),
                PlanningAudit.loadContents(cancelled, 0, "reason", reason));
        List<TransitionAudit.Entry> entries = new ArrayList<>();
        for (int i = 0; i < aboard.size(); i++) {
            entries.add(new TransitionAudit.Entry(aboard.get(i).id(),
                    PlanningAudit.consignmentOn(aboard.get(i), loadId),
                    PlanningAudit.consignment(saved.get(i))));
        }
        audit.recordAll("CONSIGNMENT_REMOVED_FROM_LOAD", PlanningAudit.CONSIGNMENT, entries);

        events.publishEvent(new LoadCancelled(tenantId, loadId, load.loadNo(),
                load.status().name(), aboard.stream().map(Consignment::id).toList(), reason,
                Instant.now()));
    }

    // --------------------------------------------------------------- helpers

    private LoadUnit require(UUID loadId) {
        return loads.findById(loadId)
                .orElseThrow(() -> new ResourceNotFoundException("Load", loadId));
    }

    /**
     * Refuses a version other than the stored one before any work is done.
     * The versioned save settles a race; this spares a stale caller the work
     * and answers the same 409.
     */
    private static void requireVersion(LoadUnit load, long expectedVersion) {
        if (load.version() == null || load.version() != expectedVersion) {
            throw new OptimisticLockingFailureException(
                    "Load " + load.loadNo() + " has changed since version " + expectedVersion);
        }
    }
}
