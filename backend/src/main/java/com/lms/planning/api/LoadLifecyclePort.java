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
package com.lms.planning.api;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

/**
 * Reading a load, and moving it along its lifecycle.
 *
 * <p>The write side is expressed as events that happened -- awarded,
 * dispatched, completed -- rather than as a status to set. Planning owns the
 * load state machine, and a caller that could assign a status directly would
 * be a second place the transition rules live, which is how two modules come
 * to disagree about whether a load may be dispatched.
 */
public interface LoadLifecyclePort {

    /**
     * A load's shape, or empty if there is no such load.
     *
     * <p>Empty rather than an exception: sourcing and execution both ask about
     * identifiers a user supplied, and "no such load" is an answer, not a
     * failure of the system.
     */
    Optional<LoadSummary> summarise(UUID loadId);

    /**
     * Records that a vendor has taken the load.
     *
     * @throws com.lms.shared.error.BusinessRuleViolationException if the load
     *         is not in a state that can be awarded
     */
    void recordAwarded(UUID loadId, UUID vendorPartnerId);

    /** Records that the load has physically left the origin. */
    void recordDispatched(UUID loadId);

    /** Records that every consignment on the load has been delivered. */
    void recordCompleted(UUID loadId);

    /**
     * What other contexts are allowed to know about a load.
     *
     * @param plannedWeightKg the aggregate weight the load was built to, which
     *                        the weighbridge reading is cross-checked against
     * @param requiresHazmat  true when any consignment aboard is dangerous
     *                        goods, which gates dispatch on a certified vehicle
     *                        and an endorsed driver
     */
    record LoadSummary(
            UUID loadId,
            String loadNo,
            String status,
            UUID orgUnitId,
            UUID originTerminalId,
            /**
             * The final drop. A load may be a milk run with several
             * destinations; the lane it is sourced on is origin to last drop,
             * because that is the distance the vendor is quoting for.
             */
            UUID destinationTerminalId,
            UUID vehicleId,
            String vehicleType,
            UUID awardedVendorPartnerId,
            BigDecimal plannedWeightKg,
            BigDecimal plannedVolumeM3,
            boolean requiresHazmat,
            int consignmentCount) {

        /** Whether the load is closed to building and can be offered to vendors. */
        public boolean isSourceable() {
            return "PLANNED".equals(status);
        }
    }
}
