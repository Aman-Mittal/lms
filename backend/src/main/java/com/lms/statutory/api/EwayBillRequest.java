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

import java.time.Instant;

/**
 * One call to an adapter. Carries what the statutory system needs and nothing
 * secret: {@code credentialRef} names where the unit's credentials are kept
 * (for example {@code secret-ref:eway/branch-1}); resolving it is the adapter's
 * business.
 *
 * @param action        what to do
 * @param documentRef   the reference the bill is raised against (a consignment's lorry receipt number)
 * @param billNo        the bill acted on; null for GENERATE and FETCH
 * @param vehicleNo     the vehicle for PART_B and CHANGE_VEHICLE; may be set on GENERATE
 * @param reason        the reason for EXTEND, CANCEL and CHANGE_VEHICLE
 * @param distanceKm    the distance of the journey, for GENERATE
 * @param kmPerDay      the validity rule: kilometres a bill is valid for per day
 * @param extensionHours how far EXTEND moves the validity
 * @param currentValidUntil the bill's present validity, for EXTEND
 * @param gstin         the unit's registration, or null when none is set
 * @param credentialRef a reference to the credentials, never the credentials
 * @param sequence      how many bills the consignment has had before this one (0 for the first), so a
 *                      bill raised after a cancellation gets a new number
 * @param now           the moment of the call, so that an adapter is deterministic under test
 */
public record EwayBillRequest(EwayBillAction action, String documentRef, String billNo, String vehicleNo,
                              String reason, Integer distanceKm, int kmPerDay, int extensionHours,
                              Instant currentValidUntil, String gstin, String credentialRef, int sequence,
                              Instant now) {

    public EwayBillRequest {
        if (action == null) {
            throw new IllegalArgumentException("An adapter call needs an action");
        }
    }
}
