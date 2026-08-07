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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.lms.masterdata.api.FleetCapacityPort;
import com.lms.order.api.MaterialClass;
import com.lms.order.api.OrderPlanningPort;
import com.lms.planning.command.domain.Consignment;
import com.lms.planning.command.domain.LoadUnit;
import com.lms.planning.events.ConsignmentsGenerated;
import com.lms.planning.events.LoadPlanned;
import com.lms.shared.error.BusinessRuleViolationException;
import com.lms.shared.error.ResourceNotFoundException;
import com.lms.shared.tenant.TenantContext;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Write side for planning (vision document 3.4).
 *
 * <p>Two operations that together turn commercial demand into something a
 * driver can be handed: generating consignments from an order's lines, and
 * aggregating consignments onto a vehicle.
 */
@Service
public class PlanningCommandService {

    private final OrderPlanningPort orderPlanning;
    private final FleetCapacityPort fleetCapacity;
    private final ConsignmentRepository consignments;
    private final LoadUnitRepository loads;
    private final PlanningLinks links;
    private final ApplicationEventPublisher events;

    public PlanningCommandService(OrderPlanningPort orderPlanning, FleetCapacityPort fleetCapacity,
                                  ConsignmentRepository consignments, LoadUnitRepository loads,
                                  PlanningLinks links, ApplicationEventPublisher events) {
        this.orderPlanning = orderPlanning;
        this.fleetCapacity = fleetCapacity;
        this.consignments = consignments;
        this.loads = loads;
        this.links = links;
        this.events = events;
    }

    // ------------------------------------------------------------ 3.4.1

    /**
     * Groups an order's unplanned lines into consignments.
     *
     * <p>The grouping rule of 3.4.1: one consignment per consignee per
     * destination. Two pallets going to the same customer at the same yard
     * travel under one lorry receipt; the same pallets going to two branches do
     * not, however convenient that would be for the planner.
     *
     * <p>Idempotent by construction. Only unplanned lines are drawn, and each
     * is marked as it is taken, so running this twice on the same order
     * produces consignments the first time and nothing the second -- which is
     * what a retried request after a timeout needs.
     *
     * @return the consignments created, empty if the order had nothing left to
     *         plan
     */
    @Transactional
    @PreAuthorize("hasAuthority('LOAD_CREATE')")
    public List<UUID> generateConsignments(UUID orderId) {
        UUID tenantId = TenantContext.requireTenantId();

        List<OrderPlanningPort.PlannableLine> lines = orderPlanning.plannableLines(orderId);
        if (lines.isEmpty()) {
            return List.of();
        }

        Map<GroupKey, List<OrderPlanningPort.PlannableLine>> groups = new LinkedHashMap<>();
        for (OrderPlanningPort.PlannableLine line : lines) {
            groups.computeIfAbsent(
                    new GroupKey(line.consigneePartnerId(), line.destinationTerminalId()),
                    key -> new ArrayList<>()).add(line);
        }

        List<UUID> created = new ArrayList<>();
        List<String> refs = new ArrayList<>();
        Set<UUID> plannedLineIds = new LinkedHashSet<>();

        for (Map.Entry<GroupKey, List<OrderPlanningPort.PlannableLine>> group : groups.entrySet()) {
            List<OrderPlanningPort.PlannableLine> members = group.getValue();

            // Order validation already checked this. Checked again here because
            // the two modules are separately deployable in principle and this is
            // the last point before the rule becomes a physical fact about a
            // lorry. A duplicated cheap check is worth more than a shared
            // assumption.
            rejectIncompatible(members);

            BigDecimal deadWeight = sum(members, OrderPlanningPort.PlannableLine::deadWeightKg);
            BigDecimal volume = sum(members, OrderPlanningPort.PlannableLine::volumeM3);
            BigDecimal chargeable = sum(members, OrderPlanningPort.PlannableLine::chargeableWeightKg);
            boolean hazmat = members.stream().anyMatch(OrderPlanningPort.PlannableLine::hazmat);
            Set<MaterialClass> classes = members.stream()
                    .map(OrderPlanningPort.PlannableLine::materialClass)
                    .collect(java.util.stream.Collectors.toCollection(
                            () -> EnumSet.noneOf(MaterialClass.class)));

            Consignment consignment = consignments.save(Consignment.generate(
                    UUID.randomUUID(), tenantId, orderId, nextTrackingRef(tenantId),
                    group.getKey().consigneePartnerId(), group.getKey().destinationTerminalId(),
                    deadWeight, volume, chargeable, hazmat, classes));

            for (OrderPlanningPort.PlannableLine member : members) {
                links.linkOrderLine(tenantId, consignment.id(), member.lineId());
                plannedLineIds.add(member.lineId());
            }

            created.add(consignment.id());
            refs.add(consignment.trackingRef());
        }

        // Last, so that a failure anywhere above rolls back the whole thing and
        // leaves the order's coverage untouched rather than claiming lines that
        // no consignment holds.
        orderPlanning.markLinesPlanned(orderId, plannedLineIds);

        events.publishEvent(new ConsignmentsGenerated(tenantId, orderId, created, refs, Instant.now()));
        return created;
    }

    // ------------------------------------------------------------ 3.4.2

    /** Opens an empty load sized against a specific, available vehicle. */
    @Transactional
    @PreAuthorize("hasAuthority('LOAD_CREATE')")
    public UUID openLoad(UUID orgUnitId, String loadNo, UUID originTerminalId, UUID vehicleId) {
        UUID tenantId = TenantContext.requireTenantId();

        loads.findByLoadNo(tenantId, loadNo).ifPresent(existing -> {
            throw new BusinessRuleViolationException("load-no-taken",
                    "A load numbered " + loadNo + " already exists");
        });

        FleetCapacityPort.VehicleCapacity capacity = fleetCapacity.capacityOf(vehicleId)
                .orElseThrow(() -> new BusinessRuleViolationException("vehicle-unavailable",
                        "Vehicle " + vehicleId + " does not exist or is not available to plan against"));

        return loads.save(LoadUnit.open(UUID.randomUUID(), tenantId, orgUnitId, loadNo,
                originTerminalId, capacity.vehicleId(), capacity.vehicleType(),
                capacity.hazmatCertified(), capacity.payloadCapacityKg(),
                capacity.maxVolumeM3())).id();
    }

    /**
     * Puts a consignment on a load.
     *
     * <p>Three refusals, all hard: capacity (3.4.2), dangerous goods
     * certification (3.2.2 read through to planning), and material
     * compatibility against everything already on the vehicle (3.3).
     */
    @Transactional
    @PreAuthorize("hasAuthority('LOAD_CREATE')")
    public void assignConsignment(UUID loadId, UUID consignmentId) {
        UUID tenantId = TenantContext.requireTenantId();

        LoadUnit load = loads.findById(loadId)
                .orElseThrow(() -> new ResourceNotFoundException("Load", loadId));
        Consignment consignment = consignments.findById(consignmentId)
                .orElseThrow(() -> new ResourceNotFoundException("Consignment", consignmentId));

        if (consignment.status() != Consignment.ConsignmentStatus.PLANNED) {
            throw new BusinessRuleViolationException("consignment-not-assignable",
                    "Consignment " + consignment.trackingRef() + " is " + consignment.status()
                            + " and cannot be added to a load");
        }

        rejectIncompatibleWithLoad(tenantId, load, consignment);

        // Capacity and hazmat certification are the aggregate's own rules; it
        // throws rather than returning false so that no caller can forget to
        // check the answer.
        LoadUnit updated = load.addConsignment(
                consignment.totalDeadWeightKg(), consignment.totalVolumeM3(),
                consignment.containsHazmat());

        // The link row goes in before the load is saved. Its unique constraint
        // on consignment_id is what makes double-booking impossible under
        // concurrency, and a violation must abort before the load's totals have
        // been written.
        links.linkToLoad(tenantId, loadId, consignmentId, links.nextDropSequence(tenantId, loadId));

        loads.save(updated);
        consignments.save(consignment.transitionTo(Consignment.ConsignmentStatus.ASSIGNED_TO_LOAD));
    }

    /** Closes a load to further building and offers it for sourcing. */
    @Transactional
    @PreAuthorize("hasAuthority('LOAD_CREATE')")
    public void planLoad(UUID loadId) {
        UUID tenantId = TenantContext.requireTenantId();

        LoadUnit load = loads.findById(loadId)
                .orElseThrow(() -> new ResourceNotFoundException("Load", loadId));

        List<Consignment> onLoad = consignments.findOnLoad(tenantId, loadId);
        if (onLoad.isEmpty()) {
            throw new BusinessRuleViolationException("load-is-empty",
                    "Load " + load.loadNo() + " has no consignments and cannot be planned");
        }

        LoadUnit planned = loads.save(load.transitionTo(LoadUnit.LoadStatus.PLANNED));

        events.publishEvent(new LoadPlanned(tenantId, planned.id(), planned.loadNo(),
                planned.originTerminalId(), planned.vehicleType(), onLoad.size(),
                planned.plannedWeightKg(), planned.weightUtilisationPct(),
                planned.requiresHazmat(), Instant.now()));
    }

    // --------------------------------------------------------------- helpers

    /**
     * Applies the compatibility matrix between a candidate consignment and
     * everything already on the load.
     *
     * <p>Every offending pair is reported at once. A planner told only about
     * the first conflict swaps one pallet, retries, and is told about the
     * second.
     */
    private void rejectIncompatibleWithLoad(UUID tenantId, LoadUnit load, Consignment candidate) {
        Set<MaterialClass> candidateClasses = candidate.materialClassSet();
        if (candidateClasses.isEmpty()) {
            return;
        }

        List<String> conflicts = new ArrayList<>();
        for (Consignment existing : consignments.findOnLoad(tenantId, load.id())) {
            for (MaterialClass onBoard : existing.materialClassSet()) {
                for (MaterialClass arriving : candidateClasses) {
                    if (!onBoard.isCompatibleWith(arriving)) {
                        conflicts.add(arriving + " in " + candidate.trackingRef()
                                + " must not travel with " + onBoard
                                + " already on " + existing.trackingRef());
                    }
                }
            }
        }

        if (!conflicts.isEmpty()) {
            throw new BusinessRuleViolationException("load-incompatible-materials",
                    "Load " + load.loadNo() + " cannot take this consignment: "
                            + String.join("; ", conflicts));
        }
    }

    private static void rejectIncompatible(List<OrderPlanningPort.PlannableLine> members) {
        for (int i = 0; i < members.size(); i++) {
            for (int j = i + 1; j < members.size(); j++) {
                MaterialClass left = members.get(i).materialClass();
                MaterialClass right = members.get(j).materialClass();
                if (!left.isCompatibleWith(right)) {
                    throw new BusinessRuleViolationException("consignment-incompatible-materials",
                            left + " and " + right + " are bound for the same destination "
                                    + "but must not share a transport unit");
                }
            }
        }
    }

    private static BigDecimal sum(List<OrderPlanningPort.PlannableLine> lines,
                                  java.util.function.Function<OrderPlanningPort.PlannableLine,
                                          BigDecimal> field) {
        return lines.stream().map(field).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /**
     * A fresh lorry receipt number.
     *
     * <p>Random rather than sequential. A sequential counter would need either
     * a database sequence read on every generation or a lock, and it would leak
     * how much freight a tenant moves to anyone who can see two references.
     * Collisions are caught by {@code consignment_ref_unique} rather than
     * assumed away.
     */
    private String nextTrackingRef(UUID tenantId) {
        for (int attempt = 0; attempt < 5; attempt++) {
            String candidate = "LR-" + UUID.randomUUID().toString()
                    .replace("-", "").substring(0, 10).toUpperCase(java.util.Locale.ROOT);
            if (consignments.findByTrackingRef(tenantId, candidate).isEmpty()) {
                return candidate;
            }
        }
        throw new IllegalStateException("Could not allocate a unique tracking reference");
    }

    /** Same consignee, same destination -- the 3.4.1 grouping. */
    private record GroupKey(UUID consigneePartnerId, UUID destinationTerminalId) {
    }
}
