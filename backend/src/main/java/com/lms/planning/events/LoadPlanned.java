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
 * A load is closed to further building and ready to be sourced.
 *
 * <p>The handover point to vendor allocation (3.5): the load's shape is fixed,
 * so it can be priced and offered.
 */
public record LoadPlanned(
        UUID tenantId,
        UUID loadId,
        String loadNo,
        UUID originTerminalId,
        String vehicleType,
        int consignmentCount,
        BigDecimal plannedWeightKg,
        BigDecimal weightUtilisationPct,
        boolean requiresHazmat,
        Instant occurredAt) {
}
