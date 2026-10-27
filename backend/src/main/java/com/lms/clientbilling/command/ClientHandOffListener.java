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

import com.lms.shared.tenant.TenantContext;
import com.lms.warehouse.events.HandlingUnitDispatched;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Turns a warehouse hand-off into handling and transport charges for the
 * client that owns the goods.
 *
 * <p>Shaped like {@link ClientTransportListener}: a plain after-commit
 * listener, the tenant scope taken from the event and bound before the
 * charge service opens its own transaction. Capture is idempotent per unit,
 * client and service, so the outbox redelivering the event changes nothing.
 */
@Component
public class ClientHandOffListener {

    private final ClientChargeService charges;

    public ClientHandOffListener(ClientChargeService charges) {
        this.charges = charges;
    }

    @TransactionalEventListener
    public void onHandlingUnitDispatched(HandlingUnitDispatched event) {
        if (!event.isHandedToTransport()) {
            return;
        }
        TenantContext.runWith(event.tenantId(), null, () -> charges.captureHandOff(event));
    }
}
