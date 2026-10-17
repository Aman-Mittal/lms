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
package com.lms.multimodal.command;

import com.lms.execution.events.TripCompleted;
import com.lms.execution.events.TripDispatched;
import com.lms.shared.tenant.TenantContext;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Moves road legs as their trips move.
 *
 * <p>Shaped like {@code TripBillingListener} and {@code RouteDeviationListener},
 * for the same reasons: a plain after-commit listener, so the tenant scope is
 * bound <em>before</em> the service opens its transaction; and the scope is
 * taken from the event, because on the outbox's republication path after a
 * restart nothing else has bound one. Both handlers are no-ops for a trip that
 * runs no leg, which is most trips.
 */
@Component
public class TripLegListener {

    private final ItineraryCommandService itineraries;

    public TripLegListener(ItineraryCommandService itineraries) {
        this.itineraries = itineraries;
    }

    @TransactionalEventListener
    public void onTripDispatched(TripDispatched event) {
        TenantContext.runWith(event.tenantId(), null, () ->
                itineraries.tripDispatched(event.tripId(), event.dispatchedAt()));
    }

    @TransactionalEventListener
    public void onTripCompleted(TripCompleted event) {
        TenantContext.runWith(event.tenantId(), null, () ->
                itineraries.tripCompleted(event.tripId(), event.dispatchedAt(),
                        event.completedAt()));
    }
}
