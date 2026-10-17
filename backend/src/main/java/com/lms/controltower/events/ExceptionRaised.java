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
package com.lms.controltower.events;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * A ticket has been opened against a trip.
 *
 * <p>{@code suspendsSettlement} is the 3.8 rule made explicit rather than left
 * for each listener to re-derive from the severity: finance acts on the flag,
 * and if the grading of categories ever changes, the ticket that placed a hold
 * is still the one that releases it.
 */
public record ExceptionRaised(
        UUID tenantId,
        UUID exceptionId,
        String ticketNo,
        UUID tripId,
        String tripNo,
        String category,
        String severity,
        boolean suspendsSettlement,
        BigDecimal lat,
        BigDecimal lon,
        Instant raisedAt) {
}
