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
package com.lms.multimodal.query;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** One leg, flattened, with the terminal, trip and carrier it names. */
public record LegView(
        UUID id,
        int seq,
        String mode,
        String status,
        UUID fromTerminalId,
        String fromTerminalCode,
        UUID toTerminalId,
        String toTerminalCode,
        Instant plannedDepartureAt,
        Instant plannedArrivalAt,
        int transferDwellMinutes,
        UUID tripId,
        String tripNo,
        String unitType,
        Integer unitCount,
        String bookingRef,
        String trainNo,
        UUID carrierPartnerId,
        String carrierCode,
        BigDecimal costAmount,
        Instant placedAt,
        Instant loadedAt,
        Instant departedAt,
        Instant arrivedAt,
        Instant releasedAt,
        boolean startedEarly,
        Instant connectionMissedAt,
        Instant earliestStartAt) {
}
