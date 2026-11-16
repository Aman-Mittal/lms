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
package com.lms.statutory.api;

import java.util.List;
import java.util.UUID;

/**
 * A trip-level Part-B vehicle update, offered to trip execution: the vehicle of
 * every live bill on the load's consignments is set, and the answer says, per
 * consignment, what became of it.
 */
public interface EwayBillTripPartB {

    /**
     * @param vehicleNo the registration to put on Part-B
     * @return one outcome per consignment on the load, in drop order
     */
    List<ItemOutcome> updateVehicle(UUID loadId, String vehicleNo);

    /**
     * What became of one consignment.
     *
     * @param outcome UPDATED, REFUSED (the adapter said no), SKIPPED (no live bill, or the unit has no adapter)
     *                or ERROR (the call failed or timed out)
     * @param code    a stable problem code for anything but UPDATED
     */
    record ItemOutcome(UUID consignmentId, String trackingRef, UUID ewayBillId, String billNo, String outcome,
                       String code, String message) {
    }
}
