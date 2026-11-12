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
package com.lms.execution.query;

import java.time.Instant;
import java.util.UUID;

/**
 * A gate pass. {@code status} is what is stored (ISSUED, USED, CANCELLED);
 * {@code expired} is true for an ISSUED pass whose window has closed, read from
 * the clock when the list is read.
 */
public record GatePassView(
        UUID id,
        String passNo,
        UUID orgUnitId,
        String orgUnitName,
        UUID terminalId,
        String terminalCode,
        UUID vehicleId,
        String registrationNo,
        UUID tripId,
        String tripNo,
        String purpose,
        Instant validFrom,
        Instant validUntil,
        String status,
        boolean expired,
        Instant usedAt,
        UUID usedVisitId,
        Instant cancelledAt,
        String cancelReason,
        Long version,
        Instant createdAt) {
}
