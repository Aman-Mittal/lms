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

import com.lms.planning.events.ConsignmentsCancelled;
import com.lms.shared.tenant.TenantContext;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Cancels the receivable of every docket that was cancelled.
 *
 * <p>After-commit with the scope taken from the event, for the reasons given
 * on {@link TripBillingListener} and {@link DocketRatingListener}. Safe to run
 * twice because {@link DocketCancellationService#cancelReceivables} skips a
 * receivable that is already cancelled, and a full-truckload cancellation
 * (no dockets) returns before any scope or transaction is opened.
 */
@Component
public class DocketCancellationListener {

    private final DocketCancellationService cancellation;

    public DocketCancellationListener(DocketCancellationService cancellation) {
        this.cancellation = cancellation;
    }

    @TransactionalEventListener
    public void onConsignmentsCancelled(ConsignmentsCancelled event) {
        if (event.docketIds().isEmpty()) {
            return;
        }
        TenantContext.runWith(event.tenantId(), null,
                () -> cancellation.cancelReceivables(event.docketIds()));
    }
}
