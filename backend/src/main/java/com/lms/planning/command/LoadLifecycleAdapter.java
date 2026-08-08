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

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.lms.planning.api.LoadLifecyclePort;
import com.lms.planning.command.domain.Consignment;
import com.lms.planning.command.domain.LoadUnit;
import com.lms.shared.error.BusinessRuleViolationException;
import com.lms.shared.error.ResourceNotFoundException;
import com.lms.shared.tenant.TenantContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Implements the port that sourcing and execution call, keeping planning's
 * aggregates on this side of the boundary.
 */
@Service
public class LoadLifecycleAdapter implements LoadLifecyclePort {

    private final LoadUnitRepository loads;
    private final ConsignmentRepository consignments;

    public LoadLifecycleAdapter(LoadUnitRepository loads, ConsignmentRepository consignments) {
        this.loads = loads;
        this.consignments = consignments;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<LoadSummary> summarise(UUID loadId) {
        UUID tenantId = TenantContext.requireTenantId();

        return loads.findById(loadId).map(load -> {
            List<Consignment> aboard = consignments.findOnLoad(tenantId, loadId);
            return new LoadSummary(load.id(), load.loadNo(), load.status().name(),
                    load.orgUnitId(), load.originTerminalId(), finalDrop(aboard),
                    load.vehicleId(), load.vehicleType(), load.awardedVendorPartnerId(),
                    load.plannedWeightKg(), load.plannedVolumeM3(),
                    chargeableWeight(aboard), load.requiresHazmat(), aboard.size(),
                    dropCount(aboard));
        });
    }

    @Override
    @Transactional
    public void recordAwarded(UUID loadId, UUID vendorPartnerId) {
        loads.save(require(loadId).awardTo(vendorPartnerId));
    }

    @Override
    @Transactional
    public void recordDispatched(UUID loadId) {
        loads.save(require(loadId).transitionTo(LoadUnit.LoadStatus.DISPATCHED));
    }

    @Override
    @Transactional
    public void recordCompleted(UUID loadId) {
        UUID tenantId = TenantContext.requireTenantId();
        LoadUnit load = require(loadId);

        // A load is complete when its freight has been delivered, not when
        // somebody says so. Without this, a mis-click on one trip would close a
        // load whose other drops are still on the road, and the consignments
        // still sitting IN_TRANSIT would be invisible to every screen that
        // filters on open loads.
        List<Consignment> undelivered = consignments.findOnLoad(tenantId, loadId).stream()
                .filter(c -> c.status() != Consignment.ConsignmentStatus.DELIVERED)
                .filter(c -> c.status() != Consignment.ConsignmentStatus.CANCELLED)
                .toList();

        if (!undelivered.isEmpty()) {
            throw new BusinessRuleViolationException("load-has-undelivered-consignments",
                    "Load " + load.loadNo() + " still has " + undelivered.size()
                            + " consignment(s) that have not been delivered");
        }

        loads.save(load.transitionTo(LoadUnit.LoadStatus.COMPLETED));
    }

    /**
     * The last drop on the run, which is the destination the lane is priced on.
     *
     * <p>Null for an empty load. That is only reachable while a load is still
     * being built, and nothing downstream asks for a destination until the load
     * is closed.
     */
    /**
     * What the load is billed on: the sum of its consignments' chargeable
     * weights, each already the greater of dead and volumetric.
     *
     * <p>Summed here rather than kept on the load because the load's own totals
     * exist to answer "will it fit", and conflating the two would make a
     * capacity check start refusing loads that fit perfectly well.
     */
    private static java.math.BigDecimal chargeableWeight(List<Consignment> aboard) {
        return aboard.stream()
                .map(Consignment::chargeableWeightKg)
                .filter(java.util.Objects::nonNull)
                .reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add);
    }

    /** Distinct destinations aboard -- see {@code LoadSummary.dropCount}. */
    private static int dropCount(List<Consignment> aboard) {
        return (int) aboard.stream()
                .map(Consignment::destinationTerminalId)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .count();
    }

    private static UUID finalDrop(List<Consignment> aboard) {
        // findOnLoad orders by drop_sequence, so the last element is the
        // furthest point of the run.
        return aboard.isEmpty() ? null : aboard.get(aboard.size() - 1).destinationTerminalId();
    }

    private LoadUnit require(UUID loadId) {
        return loads.findById(loadId)
                .orElseThrow(() -> new ResourceNotFoundException("Load", loadId));
    }
}
