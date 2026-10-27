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
 * Where a trip's latest fix says it will arrive, as of the end of an ingested
 * batch. One per trip per batch, not one per ping: the projection from the
 * last fix supersedes the ones before it.
 *
 * <p>Telematics does not know what was promised, so it does not judge it. The
 * control tower holds the committed window and the tenant's tolerance and
 * decides whether this is a slip.
 *
 * @param projectedArrivalAt the fix time plus the straight-line remaining
 *                           distance at the assumed average speed; the same
 *                           stand-in for a routing engine the progress view
 *                           uses
 * @param basedOnFixAt       when the device recorded the fix it was projected from
 */
public record TripEtaProjected(
        UUID tenantId,
        UUID tripId,
        String tripNo,
        UUID destinationTerminalId,
        Instant projectedArrivalAt,
        Instant basedOnFixAt,
        BigDecimal lat,
        BigDecimal lon,
        Instant publishedAt) {
}
