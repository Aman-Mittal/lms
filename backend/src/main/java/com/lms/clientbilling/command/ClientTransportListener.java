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
package com.lms.clientbilling.command;

import com.lms.execution.events.TripCompleted;
import com.lms.shared.tenant.TenantContext;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Turns a finished trip into transport charges for the clients aboard.
 *
 * <p>Shaped exactly like finance's {@code TripBillingListener}, for the same
 * reasons: a plain after-commit listener so the tenant scope can be bound
 * <em>before</em> the capture opens its transaction, with the scope taken from
 * the event so the restart-republication path works as well as the happy one.
 * The capture is idempotent on trip and client, so a redelivery is a no-op.
 */
@Component
public class ClientTransportListener {

    private final ClientChargeService charges;

    public ClientTransportListener(ClientChargeService charges) {
        this.charges = charges;
    }

    @TransactionalEventListener
    public void onTripCompleted(TripCompleted event) {
        TenantContext.runWith(event.tenantId(), null, () ->
                charges.captureTransport(event.tripId(), event.tripNo(), event.loadId(),
                        event.dispatchedAt(), event.completedAt()));
    }
}
