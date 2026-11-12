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
package com.lms.execution.command;

import java.time.Instant;

import com.lms.execution.command.domain.Trip;
import com.lms.execution.events.TripCompleted;
import com.lms.planning.api.LoadLifecyclePort;
import com.lms.shared.audit.AuditTrail;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * Completing a trip, whoever asks: a person through
 * {@link TripCommandService#complete}, or the vehicle leaving its destination
 * when the trip's settings say that completes it ({@link TripTrackingAdapter}).
 * Joins the caller's transaction and checks no permission; the callers do.
 */
@Component
class TripCompletion {

    private final TripRepository trips;
    private final LoadLifecyclePort loads;
    private final AuditTrail audit;
    private final ApplicationEventPublisher events;
    private final TripStops tripStops;

    TripCompletion(TripRepository trips, LoadLifecyclePort loads, AuditTrail audit,
                   ApplicationEventPublisher events, TripStops tripStops) {
        this.tripStops = tripStops;
        this.trips = trips;
        this.loads = loads;
        this.audit = audit;
        this.events = events;
    }

    Trip complete(Trip trip, Instant when) {
        Trip completed = trips.save(trip.complete(when));
        // Synchronous, in this transaction, like dispatch: planning moves the
        // freight aboard -- a part-load line haul's dockets have now arrived
        // at their destination hub -- and a refusal there must undo this.
        loads.recordTripCompleted(completed.loadId());
        if (completed.destinationTerminalId() != null) {
            tripStops.leftTerminal(completed, completed.destinationTerminalId(), when,
                    com.lms.execution.command.domain.TripStop.Source.TRIP);
        }
        audit.recordAtomically("TRIP_COMPLETED", "TRIP", completed.id(), TripCommandService.snapshot(trip),
                TripCommandService.snapshot(completed, "at", when.toString()));

        events.publishEvent(new TripCompleted(completed.tenantId(), completed.id(), completed.tripNo(),
                completed.loadId(), completed.vendorPartnerId(), completed.dispatchedAt(),
                when, completed.originDwell(), completed.payloadWeightKg(), Instant.now()));
        return completed;
    }
}
