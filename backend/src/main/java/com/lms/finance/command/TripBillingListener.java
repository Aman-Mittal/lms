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
 */package com.lms.finance.command;

import com.lms.execution.events.TripCompleted;
import com.lms.shared.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Turns a finished trip into a freight bill.
 *
 * <p>The only place finance reacts to another module rather than being asked.
 * Pricing cannot happen before the movement is over -- the dwell that detention
 * is billed on is not known until the lorry leaves -- so this is genuinely
 * after the fact, which is what makes an event the right shape.
 *
 * <p>Two details are load-bearing.
 *
 * <p>First, it is a plain {@code @TransactionalEventListener} rather than
 * {@code @ApplicationModuleListener}. The two differ by {@code @Async} and a
 * transaction on the listener method itself, and both would break here: a
 * transaction started on the listener is opened <em>before</em> anything can
 * bind a tenant, and {@code TenantAwareTransactionManager} issues its
 * {@code SET LOCAL app.tenant_id} at transaction start -- so every statement
 * would run with no tenant and row-level security would return nothing. Scope
 * first, transaction second, exactly as the scheduled jobs learned. Spring
 * Modulith's event publication registry still tracks this listener, because it
 * tracks every transactional one, so an incomplete publication is still
 * republished on restart.
 *
 * <p>Second, the scope is taken from the event rather than assumed to be
 * present. After-commit runs on the committing thread, so in practice the
 * scope is still bound -- but on the restart-republication path it is not, and
 * a listener that only worked on the happy path would fail precisely when the
 * outbox was doing its job.
 */
@Component
public class TripBillingListener {

    private static final Logger log = LoggerFactory.getLogger(TripBillingListener.class);

    private final BillingService billing;

    public TripBillingListener(BillingService billing) {
        this.billing = billing;
    }

    @TransactionalEventListener
    public void onTripCompleted(TripCompleted event) {
        TenantContext.runWith(event.tenantId(), null, () -> {
            billing.raiseBillFor(event.tripId(), event.loadId(), event.vendorPartnerId(),
                            event.tripNo(), event.dispatchedAt(), event.originDwell(),
                            event.payloadWeightKg())
                    .ifPresent(billId ->
                            log.debug("Raised freight bill {} for trip {}", billId, event.tripNo()));
        });
    }
}
