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
 * A leg arrived too late for the next one to start as planned -- or has not
 * arrived at all, and no longer can in time.
 *
 * <p>Published once per leg, by whichever notices first: the arrival, when
 * the predecessor's arrival plus the next leg's transfer dwell lands after the
 * next leg's planned departure; or the periodic sweep, when the predecessor's
 * planned arrival plus the dwell has passed with no arrival and arriving now
 * would already be too late. Carries
 * the trip of the late-arriving leg when it was a road leg, so a listener that
 * raises a ticket against trips has something to raise it against; a rail leg
 * has none.
 *
 * @param earliestStartAt the predecessor's arrival plus the transfer dwell --
 *                        the soonest the missed leg can now start. From the
 *                        sweep, with nothing arrived, a lower bound: the
 *                        detection time plus the dwell. The leg's own record
 *                        is revised when the predecessor arrives; the event
 *                        is not published again.
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
