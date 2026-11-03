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
package com.lms.controltower.query;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * One ticket, flattened for the control tower board.
 *
 * <p>{@code tripId}, {@code tripNo} and the vehicle are null on a ticket that
 * is about something other than a trip; {@code subjectType},
 * {@code subjectId} and {@code subjectLabel} then say what it is about.
 */
public record ExceptionView(
        UUID id,
        String ticketNo,
        UUID tripId,
        String tripNo,
        UUID vehicleId,
        String registrationNo,
        String subjectType,
        UUID subjectId,
        String subjectLabel,
        String category,
        String severity,
        String status,
        String description,
        String source,
        BigDecimal lat,
        BigDecimal lon,
        String positionSource,
        Instant positionAt,
        boolean suspendsSettlement,
        String raisedBy,
        Instant raisedAt,
        UUID assigneeUserId,
        String assigneeName,
        Instant assignedAt,
        String resolution,
        String resolvedBy,
        Instant resolvedAt,
        String closedBy,
        Instant closedAt) {
}
