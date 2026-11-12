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

import java.time.Instant;
import java.util.UUID;

/**
 * A trip's vehicle reached one of its stops.
 *
 * <p>Carries the plan beside the fact -- {@code plannedArrivalAt} is null when
 * the stop was given no time -- so a listener can judge lateness against its
 * own tolerance. {@code outOfOrder} says an earlier stop was still waiting;
 * {@code source} is MANUAL, GEOFENCE or TRIP (the trip's own milestone).
 */
public record TripStopArrived(
        UUID tenantId,
        UUID tripId,
        String tripNo,
        UUID stopId,
        int seq,
        String kind,
        UUID terminalId,
        Instant plannedArrivalAt,
        Instant arrivedAt,
        boolean outOfOrder,
        String source,
        Instant occurredAt) {
}
