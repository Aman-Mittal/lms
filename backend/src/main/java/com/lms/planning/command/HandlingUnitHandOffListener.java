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

import java.util.UUID;

import com.lms.shared.tenant.TenantContext;
import com.lms.warehouse.events.HandlingUnitDispatched;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Receipt to loaded truck: a handling unit the warehouse handed to transport
 * becomes a consignment that planning can put on a load.
 *
 * <p>A plain after-commit listener, with the tenant scope taken from the event
 * and bound <em>before</em> a {@code REQUIRES_NEW} transaction is opened from
 * a {@link TransactionTemplate} -- the order {@code TenantAwareTransactionManager}
 * needs, held in one place. On the outbox's republication path after a
 * restart nothing else has bound a scope, which is why it comes from the
 * event and not from the thread.
 *
 * <p>A unit dispatched without a destination, consignee and weight left some
 * other way -- collected, or on another firm's lorry -- and is not planning's
 * to move, so it is ignored. The consignment is idempotent per unit, so a
 * redelivery changes nothing.
 */
@Component
public class HandlingUnitHandOffListener {

    private static final Logger log = LoggerFactory.getLogger(HandlingUnitHandOffListener.class);

    private final PlanningCommandService planning;
    private final TransactionTemplate transactions;

    public HandlingUnitHandOffListener(PlanningCommandService planning,
                                       PlatformTransactionManager transactionManager) {
        this.planning = planning;
        this.transactions = new TransactionTemplate(transactionManager);
        this.transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @TransactionalEventListener
    public void onHandlingUnitDispatched(HandlingUnitDispatched event) {
        if (!event.isHandedToTransport()) {
            return;
        }
        TenantContext.runWith(event.tenantId(), null, () -> {
            UUID consignmentId = transactions.execute(status -> planning.receiveHandOff(
                    event.handlingUnitId(), event.consigneePartnerId(),
                    event.destinationTerminalId(), event.grossWeightKg(), event.volumeM3()));
            log.debug("Handling unit {} is consignment {}", event.huNo(), consignmentId);
        });
    }
}
