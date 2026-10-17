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

import com.lms.controltower.events.ExceptionClosed;
import com.lms.controltower.events.ExceptionRaised;
import com.lms.shared.tenant.TenantContext;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Freezes and releases a trip's settlement as the control tower says (vision
 * document 3.8).
 *
 * <p>Acts on {@code suspendsSettlement} rather than on the severity. Which
 * categories are critical is the control tower's rule; finance only needs to
 * know whether money must wait.
 *
 * <p>After-commit and scope-from-the-event, for the reasons given on
 * {@link TripBillingListener}.
 */
@Component
public class SettlementHoldListener {

    private final SettlementHolds holds;

    public SettlementHoldListener(SettlementHolds holds) {
        this.holds = holds;
    }

    @TransactionalEventListener
    public void onRaised(ExceptionRaised event) {
        if (!event.suspendsSettlement()) {
            return;
        }
        TenantContext.runWith(event.tenantId(), null, () ->
                holds.place(event.exceptionId(), event.tripId(), event.ticketNo(),
                        event.raisedAt()));
    }

    @TransactionalEventListener
    public void onClosed(ExceptionClosed event) {
        if (!event.suspendsSettlement()) {
            return;
        }
        TenantContext.runWith(event.tenantId(), null, () ->
                holds.release(event.exceptionId(), event.tripId(), event.ticketNo(),
                        event.raisedAt(), event.closedAt()));
    }
}
