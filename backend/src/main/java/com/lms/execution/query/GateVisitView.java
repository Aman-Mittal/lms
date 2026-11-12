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
import java.util.List;
import java.util.UUID;

/**
 * One visit in the gate register: the entry and, once the vehicle has left,
 * its exit. {@code dwellMinutes} is how long it was inside, or has been so
 * far for an open visit.
 */
public record GateVisitView(
        UUID id,
        UUID orgUnitId,
        String orgUnitName,
        UUID terminalId,
        String terminalCode,
        UUID vehicleId,
        String registrationNo,
        UUID driverId,
        String driverName,
        String purpose,
        UUID tripId,
        String tripNo,
        UUID gatePassId,
        String gatePassNo,
        String status,
        Instant enteredAt,
        List<String> entrySealNos,
        String entryWeightRef,
        String entryRemarks,
        Instant exitedAt,
        List<String> exitSealNos,
        String exitWeightRef,
        String exitRemarks,
        long dwellMinutes,
        Long version) {
}
