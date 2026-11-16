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

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * What another context may know about a consignment: its reference, the unit
 * its order was raised under, its state and the load it travels on. Read-only
 * and deliberately narrow; the statutory module asks it to find the freight a
 * bill is raised for and the freight aboard a load.
 */
public interface ConsignmentLookupPort {

    /** The consignment, or empty when there is none (or it belongs to another tenant). */
    Optional<ConsignmentRef> find(UUID consignmentId);

    /** The consignments on a load, in drop order; empty for a load with none or no such load. */
    List<ConsignmentRef> onLoad(UUID loadId);

    /**
     * @param orgUnitId the unit the consignment's order was raised under
     * @param status    PLANNED, ASSIGNED_TO_LOAD, IN_TRANSIT, DELIVERED or CANCELLED
     * @param loadId    null while the consignment is on no load
     */
    record ConsignmentRef(UUID id, String trackingRef, UUID orgUnitId, String status, UUID loadId) {

        /** Whether freight can still need a bill: neither delivered nor cancelled. */
        public boolean isOpen() {
            return !"DELIVERED".equals(status) && !"CANCELLED".equals(status);
        }
    }
}
