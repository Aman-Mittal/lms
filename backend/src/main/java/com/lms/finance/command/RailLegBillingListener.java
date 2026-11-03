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
package com.lms.finance.command;

import com.lms.multimodal.events.RailLegCompleted;
import com.lms.shared.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Raises the rail carrier's freight bill when a rail leg is released.
 *
 * <p>Shaped like {@link TripBillingListener}: a plain after-commit listener, so
 * the tenant scope, taken from the event, is bound before the service opens
 * its own transaction. Safe to run twice; the service is idempotent on the leg.
 */
@Component
public class RailLegBillingListener {

    private static final Logger log = LoggerFactory.getLogger(RailLegBillingListener.class);

    private final BillingService billing;

    public RailLegBillingListener(BillingService billing) {
        this.billing = billing;
    }

    @TransactionalEventListener
    public void onRailLegCompleted(RailLegCompleted event) {
        TenantContext.runWith(event.tenantId(), null, () ->
                billing.raiseRailLegBill(event).ifPresent(billId ->
                        log.debug("Raised rail freight bill {} for leg {} of {}", billId,
                                event.legSeq(), event.itineraryNo())));
    }
}
