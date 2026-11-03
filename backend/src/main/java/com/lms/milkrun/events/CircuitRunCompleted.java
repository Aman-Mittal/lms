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
package com.lms.milkrun.events;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * A circuit reached the plant, and this is what it is worth.
 *
 * <p>The charge is a fixed rate for running the circuit plus a fee per
 * supplier actually collected from, both taken from the route version in force
 * on the run date. Skipped suppliers are not charged for: the carrier did not
 * do that part of the work.
 *
 * <p>{@code tripNo}, {@code loadId} and {@code vendorPartnerId} were added so
 * that finance can raise the carrier payable without asking execution for
 * them: they are the carrying trip's, read when the circuit finished. The
 * vendor is null when the trip runs on the tenant's own fleet, and then there
 * is nobody to pay.
 */
public record CircuitRunCompleted(
        UUID tenantId,
        UUID runId,
        String routeCode,
        LocalDate runDate,
        UUID tripId,
        int stopsVisited,
        int stopsSkipped,
        BigDecimal totalPickedUpKg,
        BigDecimal runRate,
        BigDecimal perStopFee,
        BigDecimal chargeAmount,
        String currency,
        Instant completedAt,
        String tripNo,
        UUID loadId,
        UUID vendorPartnerId) {
}
