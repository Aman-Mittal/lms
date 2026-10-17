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
package com.lms.milkrun.command;

import com.lms.shared.tenant.TenantContext;
import com.lms.telematics.events.GeofenceCrossed;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Turns geofence crossings into circuit stops.
 *
 * <p>Shaped like the control tower's route-deviation listener, for the same
 * reasons: a plain after-commit listener rather than
 * {@code @ApplicationModuleListener}, so the tenant scope is bound
 * <em>before</em> the service opens its transaction; and the scope is taken
 * from the event, because on the outbox's republication path after a restart
 * nothing else has bound one.
 *
 * <p>Most crossings are not about a circuit at all -- a toll plaza, a trip
 * with one origin and one destination. The service answers those with one
 * indexed lookup and returns.
 */
@Component
public class GeofenceCrossingListener {

    private final CircuitRunCommandService circuits;

    public GeofenceCrossingListener(CircuitRunCommandService circuits) {
        this.circuits = circuits;
    }

    @TransactionalEventListener
    public void onGeofenceCrossed(GeofenceCrossed event) {
        TenantContext.runWith(event.tenantId(), null, () ->
                circuits.observeCrossing(event.tripId(), event.terminalId(), event.eventType(),
                        event.occurredAt()));
    }
}
