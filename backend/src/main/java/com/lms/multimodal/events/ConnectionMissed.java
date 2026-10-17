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
package com.lms.multimodal.events;

import java.time.Instant;
import java.util.UUID;

/**
 * A leg arrived too late for the next one to start as planned.
 *
 * <p>Published once per leg, when the predecessor's arrival plus the next
 * leg's transfer dwell lands after the next leg's planned departure. Carries
 * the trip of the late-arriving leg when it was a road leg, so a listener that
 * raises a ticket against trips has something to raise it against; a rail leg
 * has none.
 *
 * @param earliestStartAt the predecessor's arrival plus the transfer dwell --
 *                        the soonest the missed leg can now start
 */
public record ConnectionMissed(
        UUID tenantId,
        UUID itineraryId,
        String itineraryNo,
        UUID legId,
        int legSeq,
        String legMode,
        UUID predecessorLegId,
        UUID predecessorTripId,
        Instant plannedDepartureAt,
        Instant earliestStartAt,
        Instant detectedAt) {
}
