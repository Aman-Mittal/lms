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
package com.lms.planning.command;

import com.lms.delivery.events.DeliveryProofRecorded;
import com.lms.delivery.events.DeliveryProofReversed;
import com.lms.shared.tenant.TenantContext;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * A confirmed proof of delivery moves its consignment to DELIVERED.
 *
 * <p>Shaped like {@link HandlingUnitHandOffListener}: after commit, the tenant
 * scope from the event, then a new transaction. Safe to run twice: the second
 * delivery finds the consignment DELIVERED and changes nothing.
 */
@Component
public class DeliveryProofListener {

    private final ProofOfDeliveryService deliveries;
    private final TransactionTemplate transactions;

    public DeliveryProofListener(ProofOfDeliveryService deliveries,
                                 PlatformTransactionManager transactionManager) {
        this.deliveries = deliveries;
        this.transactions = new TransactionTemplate(transactionManager);
        this.transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @TransactionalEventListener
    public void onProofRecorded(DeliveryProofRecorded event) {
        TenantContext.runWith(event.tenantId(), null, () ->
                transactions.execute(status ->
                        deliveries.deliverOnProof(event.consignmentId(), event.deliveredAt())));
    }

    /** A reversed proof takes back the delivery it made; see {@link ProofOfDeliveryService#undeliverOnReversal}. */
    @TransactionalEventListener
    public void onProofReversed(DeliveryProofReversed event) {
        TenantContext.runWith(event.tenantId(), null, () ->
                transactions.execute(status -> deliveries.undeliverOnReversal(event.consignmentId())));
    }
}
