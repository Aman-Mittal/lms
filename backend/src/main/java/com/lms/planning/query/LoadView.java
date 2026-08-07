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
package com.lms.planning.query;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * A load as the planning board shows it.
 *
 * <p>Carries both utilisation percentages because they are the number the
 * platform exists to move, and because they diverge: a trailer can be at 40%
 * of its weight limit and completely full.
 */
public record LoadView(
        UUID id,
        String loadNo,
        String status,
        String originTerminalCode,
        String vehicleType,
        String vehicleRegistrationNo,
        BigDecimal capacityWeightKg,
        BigDecimal capacityVolumeM3,
        BigDecimal plannedWeightKg,
        BigDecimal plannedVolumeM3,
        BigDecimal weightUtilisationPct,
        BigDecimal volumeUtilisationPct,
        int consignmentCount,
        boolean requiresHazmat,
        Instant createdAt) {
}
