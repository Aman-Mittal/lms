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
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

import com.lms.planning.api.LoadCancellationHook;
import com.lms.planning.command.domain.Consignment;
import com.lms.planning.command.domain.LoadUnit;
import com.lms.planning.command.domain.LoadUnit.LoadStatus;
import com.lms.shared.audit.TransitionAudit;
import org.springframework.stereotype.Component;

/**
 * Settles the loads freight has just been taken off.
 *
 * <p>The one place a load's freight is taken away before it leaves -- removing
 * one consignment from it, cancelling one consignment, cancelling an order --
 * so that a load reads the same whichever did it: the drops left behind are
 * numbered again 1..n without a gap, the totals (weight, volume, dangerous
 * goods) are restated from the freight still aboard rather than by
 * subtraction, so they can never drift from what is actually on it, and the
 * change is audited on the load. A load left with nothing is cancelled when
 * the caller says so, audited, and offered to every
 * {@link LoadCancellationHook} (sourcing withdraws its offer and allocation)
 * in the caller's transaction; a caller that must not empty a load refuses
 * before it gets here.
 *
 * <p>Per touched load: one read of the load, one of what is aboard, one
 * renumbering statement, one update.
 */
@Component
class LoadRelease {

    /** A load after freight was taken off it: its totals now, and its status. */
    record Released(UUID loadId, String loadNo, LoadStatus status, BigDecimal plannedWeightKg,
                    BigDecimal plannedVolumeM3, boolean requiresHazmat, int consignmentsLeft) {
    }

    private final ConsignmentRepository consignments;
    private final LoadUnitRepository loads;
    private final PlanningLinks links;
    private final List<LoadCancellationHook> cancellationHooks;
    private final TransitionAudit audit;

    LoadRelease(ConsignmentRepository consignments, LoadUnitRepository loads, PlanningLinks links,
                List<LoadCancellationHook> cancellationHooks, TransitionAudit audit) {
        this.consignments = consignments;
        this.loads = loads;
        this.links = links;
        this.cancellationHooks = cancellationHooks;
        this.audit = audit;
    }

    /**
     * Takes one consignment off one load and settles the load: reads the load
     * and what is aboard, then as {@link #takeOff(UUID, LoadUnit, List, Consignment, boolean)}.
     *
     * @return the load as it now stands, or null when there is no such load
     */
    Released takeOff(UUID tenantId, UUID loadId, Consignment consignment, boolean cancelWhenEmpty) {
        LoadUnit load = loads.findById(loadId).orElse(null);
        if (load == null) {
            return null;
        }
        return takeOff(tenantId, load, consignments.findOnLoad(tenantId, loadId), consignment,
                cancelWhenEmpty);
    }

    /**
     * Takes one consignment off a load the caller has already read, with what
     * was aboard it, and settles the load. The load is saved before anything
     * else the caller writes: its versioned save is what a concurrent change
     * to the same load loses on. Audited as {@code LOAD_CONSIGNMENT_REMOVED},
     * or {@code LOAD_CANCELLED} when it took the last consignment and
     * {@code cancelWhenEmpty} holds. The consignment itself is the caller's to
     * save and audit.
     */
    Released takeOff(UUID tenantId, LoadUnit load, List<Consignment> aboard, Consignment consignment,
                     boolean cancelWhenEmpty) {
        UUID loadId = load.id();
        links.unlinkFromLoad(tenantId, loadId, consignment.id());
        List<Consignment> left = aboard.stream().filter(c -> !c.id().equals(consignment.id())).toList();
        LoadUnit restated = restate(load, left);
        if (left.isEmpty() && cancelWhenEmpty) {
            LoadUnit saved = loads.save(restated.transitionTo(LoadStatus.CANCELLED));
            audit.record("LOAD_CANCELLED", PlanningAudit.LOAD, loadId,
                    PlanningAudit.loadContents(load, aboard.size(), "trackingRef", null),
                    PlanningAudit.loadContents(saved, 0, "trackingRef", consignment.trackingRef()));
            cancellationHooks.forEach(hook -> hook.onLoadsCancelling(List.of(loadId)));
            return released(saved, 0);
        }
        links.closeUpDropSequence(tenantId, loadId);
        LoadUnit saved = loads.save(restated);
        audit.record("LOAD_CONSIGNMENT_REMOVED", PlanningAudit.LOAD, loadId,
                PlanningAudit.loadContents(load, aboard.size(), "trackingRef", null),
                PlanningAudit.loadContents(saved, left.size(), "trackingRef", consignment.trackingRef()));
        return released(saved, left.size());
    }

    /**
     * Restates each load from the freight still aboard, and cancels one left
     * with none. For a caller that has already unlinked many consignments at
     * once -- an order's cancellation.
     *
     * @return each load as it now stands, in the order given
     */
    List<Released> release(UUID tenantId, Set<UUID> loadIds) {
        if (loadIds.isEmpty()) {
            return List.of();
        }
        List<Released> released = new ArrayList<>();
        List<UUID> cancelledLoads = new ArrayList<>();
        List<TransitionAudit.Entry> cancelledEntries = new ArrayList<>();
        Map<UUID, LoadUnit> touched = new HashMap<>();
        loads.findAllById(loadIds).forEach(load -> touched.put(load.id(), load));
        for (UUID loadId : loadIds) {
            LoadUnit load = touched.get(loadId);
            if (load == null) {
                continue;
            }
            List<Consignment> aboard = consignments.findOnLoad(tenantId, loadId);
            LoadUnit restated = restate(load, aboard);
            LoadUnit saved;
            if (aboard.isEmpty()) {
                saved = loads.save(restated.transitionTo(LoadStatus.CANCELLED));
                cancelledLoads.add(loadId);
                cancelledEntries.add(PlanningAudit.entry(load, saved));
            } else {
                links.closeUpDropSequence(tenantId, loadId);
                saved = loads.save(restated);
            }
            released.add(released(saved, aboard.size()));
        }
        audit.recordAll("LOAD_CANCELLED", PlanningAudit.LOAD, cancelledEntries);
        if (!cancelledLoads.isEmpty()) {
            cancellationHooks.forEach(hook -> hook.onLoadsCancelling(cancelledLoads));
        }
        return released;
    }

    private static LoadUnit restate(LoadUnit load, List<Consignment> aboard) {
        return load.restated(
                sum(aboard, Consignment::totalDeadWeightKg),
                sum(aboard, Consignment::totalVolumeM3),
                aboard.stream().anyMatch(Consignment::containsHazmat));
    }

    private static Released released(LoadUnit saved, int left) {
        return new Released(saved.id(), saved.loadNo(), saved.status(), saved.plannedWeightKg(),
                saved.plannedVolumeM3(), saved.requiresHazmat(), left);
    }

    private static BigDecimal sum(Collection<Consignment> aboard, Function<Consignment, BigDecimal> field) {
        return aboard.stream().map(field).filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
