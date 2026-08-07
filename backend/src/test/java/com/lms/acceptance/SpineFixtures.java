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
package com.lms.acceptance;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import com.lms.order.api.MaterialClass;
import com.lms.order.command.OrderCommandService;
import com.lms.order.command.domain.SalesOrder;
import com.lms.planning.api.LoadLifecyclePort;
import com.lms.planning.command.PlanningCommandService;
import org.springframework.stereotype.Component;

/**
 * Builds a load that is far enough along to be sourced or executed.
 *
 * <p>Sourcing and execution scenarios are about what happens to a load, not
 * about how it came to exist. Spelling out the order, the line, the validation
 * and the consignment in every Background would bury the rule under a page of
 * setup, and the reader would stop reading it -- which is how a scenario ends
 * up asserting something other than what its name claims.
 *
 * <p>It goes through the real command services rather than inserting rows.
 * A fixture that wrote the tables directly could produce a load in a state the
 * domain would never allow, and the scenario would then prove nothing about
 * the platform.
 */
@Component
public class SpineFixtures {

    private final OrderCommandService orders;
    private final PlanningCommandService planning;
    private final LoadLifecyclePort loads;

    public SpineFixtures(OrderCommandService orders, PlanningCommandService planning,
                         LoadLifecyclePort loads) {
        this.orders = orders;
        this.planning = planning;
        this.loads = loads;
    }

    /**
     * An order through to a closed, sourceable load carrying one consignment.
     *
     * @param loadNo already made scenario-unique by the caller
     */
    public UUID plannedLoad(UUID orgUnitId, String loadNo, UUID customerPartnerId,
                            UUID originTerminalId, UUID consigneePartnerId,
                            UUID destinationTerminalId, UUID vehicleId,
                            BigDecimal weightKg, boolean hazmat) {
        UUID loadId = draftLoad(orgUnitId, loadNo, customerPartnerId, originTerminalId,
                consigneePartnerId, destinationTerminalId, vehicleId, weightKg, hazmat);
        planning.planLoad(loadId);
        return loadId;
    }

    /** The same, stopped one step earlier so a scenario can prove a draft load is not sourceable. */
    public UUID draftLoad(UUID orgUnitId, String loadNo, UUID customerPartnerId,
                          UUID originTerminalId, UUID consigneePartnerId,
                          UUID destinationTerminalId, UUID vehicleId,
                          BigDecimal weightKg, boolean hazmat) {
        UUID orderId = orders.raiseOrder(orgUnitId, customerPartnerId, "SO-FOR-" + loadNo,
                originTerminalId, null, null, SalesOrder.OrderSource.MANUAL);

        orders.addLine(orderId, 1, "MAT-1", hazmat ? "Dangerous goods" : "General cargo",
                hazmat ? MaterialClass.FLAMMABLE : MaterialClass.GENERAL,
                hazmat ? "UN1203" : null,
                BigDecimal.ONE, "EA", weightKg, null, null, null, null,
                consigneePartnerId, destinationTerminalId);

        orders.validate(orderId);
        List<UUID> consignments = planning.generateConsignments(orderId);

        UUID loadId = planning.openLoad(orgUnitId, loadNo, originTerminalId, vehicleId);
        planning.assignConsignment(loadId, consignments.get(0));
        return loadId;
    }

    /**
     * A load already taken by a vendor, ready for a trip.
     *
     * <p>Awarded through the port rather than by running an allocation.
     * Execution's scenarios are about dispatch, and making each of them depend
     * on a routing guide would mean a change to sourcing breaking every
     * execution test for reasons that have nothing to do with execution.
     */
    public UUID awardedLoad(UUID orgUnitId, String loadNo, UUID customerPartnerId,
                            UUID originTerminalId, UUID consigneePartnerId,
                            UUID destinationTerminalId, UUID vehicleId, UUID vendorPartnerId,
                            BigDecimal weightKg, boolean hazmat) {
        UUID loadId = plannedLoad(orgUnitId, loadNo, customerPartnerId, originTerminalId,
                consigneePartnerId, destinationTerminalId, vehicleId, weightKg, hazmat);
        loads.recordAwarded(loadId, vendorPartnerId);
        return loadId;
    }
}
