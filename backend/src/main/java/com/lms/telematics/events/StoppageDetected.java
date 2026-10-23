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

package com.lms.telematics.events;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * A trip in transit has sat still, outside every terminal, for longer than the
 * threshold. Raised once per stop.
 *
 * @param stationarySince when the vehicle arrived at the spot, which names the
 *                        stop and so keys the ticket
 */
public record StoppageDetected(
        UUID tenantId,
        UUID tripId,
        String tripNo,
        Instant stationarySince,
        long stoppedMinutes,
        long thresholdMinutes,
        BigDecimal lat,
        BigDecimal lon,
        Instant detectedAt,
        Instant publishedAt) {
}
