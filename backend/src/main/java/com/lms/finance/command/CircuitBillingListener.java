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

import com.lms.milkrun.events.CircuitRunCompleted;
import com.lms.shared.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Turns a completed milk-run circuit into the carrier's payable.
 *
 * <p>Shaped like {@link TripBillingListener}: a plain after-commit listener,
 * with the tenant scope taken from the event and bound <em>before</em> the
 * transaction opens, so that {@code TenantAwareTransactionManager} scopes the
 * connection to the right tenant -- on the happy path and equally on the
 * outbox's republication after a restart, where nothing else has bound one.
 *
 * <p>The transaction is opened here, from a {@link TransactionTemplate} set to
 * {@code REQUIRES_NEW}, rather than by an annotation on the service. The order
 * "scope, then transaction" is then written down in one place and cannot be
 * inverted by somebody adding {@code @Transactional} to a caller. The raise is
 * idempotent on the circuit run, so a redelivery changes nothing.
 */
@Component
public class CircuitBillingListener {

    private static final Logger log = LoggerFactory.getLogger(CircuitBillingListener.class);

    private final BillingService billing;
    private final TransactionTemplate transactions;

    public CircuitBillingListener(BillingService billing,
                                  PlatformTransactionManager transactionManager) {
        this.billing = billing;
        this.transactions = new TransactionTemplate(transactionManager);
        this.transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @TransactionalEventListener
    public void onCircuitRunCompleted(CircuitRunCompleted event) {
        BillingService.CircuitCharge charge = new BillingService.CircuitCharge(event.runId(),
                event.routeCode(), event.runDate(), event.tripId(), event.tripNo(),
                event.loadId(), event.vendorPartnerId(), event.stopsVisited(),
                event.totalPickedUpKg(), event.runRate(), event.perStopFee(),
                event.chargeAmount(), event.currency());

        TenantContext.runWith(event.tenantId(), null, () ->
                transactions.executeWithoutResult(status ->
                        billing.raiseCircuitBill(charge).ifPresent(billId ->
                                log.debug("Raised circuit payable {} for {} on {}", billId,
                                        event.routeCode(), event.runDate()))));
    }
}
