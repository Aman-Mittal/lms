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
package com.lms.planning.events;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * A consignment was taken off a load before the load left, and is waiting to
 * be planned again.
 *
 * <p>Carries the load's totals as they are after the removal, so a reader
 * does not have to ask planning what is left aboard.
 *
 * @param restoredStatus the status the consignment went back to: PLANNED, or
 *                       ARRIVED_AT_HUB for a docket taken off a delivery load
 */
public record ConsignmentRemovedFromLoad(
        UUID tenantId,
        UUID loadId,
        String loadNo,
        UUID consignmentId,
        String trackingRef,
        String restoredStatus,
        BigDecimal plannedWeightKg,
        BigDecimal plannedVolumeM3,
        boolean requiresHazmat,
        int consignmentCount,
        Instant occurredAt) {
}
