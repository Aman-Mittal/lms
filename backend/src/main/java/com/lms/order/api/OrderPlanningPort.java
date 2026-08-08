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
package com.lms.order.api;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * What planning is allowed to ask of, and tell, the order module.
 *
 * <p>Two operations, in both directions, and nothing else. Planning reads the
 * demand that still needs a consignment, and reports back which lines it has
 * taken. It never writes an order status: the coverage rule -- when an order
 * becomes partially rather than fully planned -- stays inside the module that
 * owns the order state machine, because that is the module whose tests will
 * fail if the rule changes and something else disagrees.
 *
 * <p>Deliberately expressed in flat records rather than the {@code OrderLine}
 * aggregate. Handing planning the aggregate would hand it every future field of
 * the aggregate too, and the boundary would erode one accessor at a time.
 */
public interface OrderPlanningPort {

    /**
     * Lines of a validated order that are not yet on a consignment.
     *
     * <p>Returns empty for an order that is not yet validated: unvalidated
     * demand has not been checked for hazmat completeness or material
     * compatibility, and planning it would push those failures downstream into
     * a load that is already built.
     */
    List<PlannableLine> plannableLines(UUID orderId);

    /**
     * Records that planning has taken these lines onto consignments, and
     * advances the order's state machine to match its new coverage.
     *
     * <p>Idempotent by set semantics: marking an already-planned line changes
     * nothing, so a retried consignment generation cannot corrupt coverage.
     */
    void markLinesPlanned(UUID orderId, Set<UUID> plannedLineIds);

    /**
     * One line of demand, reduced to what grouping and capacity arithmetic
     * needs.
     *
     * @param materialClass the class itself, not its name. Planning has to
     *                      apply the compatibility matrix when it puts two
     *                      consignments on one vehicle, and there must be
     *                      exactly one copy of that matrix in the system --
     *                      two copies that disagree is a load that one module
     *                      thinks is legal and the other does not. Sharing the
     *                      vocabulary is precisely what a named interface is
     *                      for.
     */
    record PlannableLine(
            UUID lineId,
            UUID consigneePartnerId,
            UUID destinationTerminalId,
            MaterialClass materialClass,
            boolean hazmat,
            BigDecimal deadWeightKg,
            BigDecimal volumeM3,
            BigDecimal chargeableWeightKg) {
    }
}
