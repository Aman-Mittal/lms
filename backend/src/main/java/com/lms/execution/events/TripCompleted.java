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
package com.lms.execution.events;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Proof of delivery accepted; the trip is over.
 *
 * <p>Carries the origin dwell because that is what detention is billed on
 * (3.9.1), and a listener computing it for itself would have to reach back into
 * execution's timestamps -- which is exactly the coupling events exist to
 * avoid.
 */
public record TripCompleted(
        UUID tenantId,
        UUID tripId,
        String tripNo,
        UUID loadId,
        UUID vendorPartnerId,
        Instant dispatchedAt,
        Instant completedAt,
        Duration originDwell,
        /**
         * Gross less tare, as weighed at the origin.
         *
         * <p>Carried for the same reason as the dwell. A bill priced on what
         * the lorry was planned to carry rather than on what it was weighed
         * carrying is a bill nobody can defend, and the weighbridge ticket is
         * the one measurement both parties witnessed.
         */
        BigDecimal payloadWeightKg,
        Instant occurredAt) {
}
