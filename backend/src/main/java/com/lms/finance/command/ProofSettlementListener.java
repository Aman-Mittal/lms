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

import java.time.Instant;

import com.lms.delivery.events.DeliveryProofRecorded;
import com.lms.delivery.events.DeliveryProofReversed;
import com.lms.execution.events.TripCompleted;
import com.lms.shared.tenant.TenantContext;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Holds a trip's settlement until its proofs of delivery are clean.
 *
 * <p>Completing a trip does not wait for a proof; paying the carrier does. On
 * completion, a trip with any consignment not yet proved clean is held. Each
 * proof that arrives afterwards re-asks the question for the trips that
 * carried its consignment, and releases a hold whose trip is now clean
 * throughout. A proof that records a shortage or damage keeps the hold: a
 * person has to look at it and release it.
 *
 * <p>After-commit and scope-from-the-event, for the reasons given on
 * {@link TripBillingListener}. Both handlers are safe to repeat: placing is
 * keyed on the trip, and releasing an already released hold changes nothing.
 */
@Component
public class ProofSettlementListener {

    private final SettlementHolds holds;

    public ProofSettlementListener(SettlementHolds holds) {
        this.holds = holds;
    }

    @TransactionalEventListener
    public void onTripCompleted(TripCompleted event) {
        Instant at = event.completedAt() == null ? event.occurredAt() : event.completedAt();
        TenantContext.runWith(event.tenantId(), null, () ->
                holds.holdForProofs(event.tripId(), at == null ? Instant.now() : at));
    }

    /** A reversed proof holds again the completed trips it had released. */
    @TransactionalEventListener
    public void onProofReversed(DeliveryProofReversed event) {
        TenantContext.runWith(event.tenantId(), null, () ->
                holds.reholdWhereReversed(event.consignmentId(), Instant.now()));
    }

    @TransactionalEventListener
    public void onProofRecorded(DeliveryProofRecorded event) {
        if (!event.clean()) {
            return;
        }
        TenantContext.runWith(event.tenantId(), null, () ->
                holds.releaseWhereProved(event.consignmentId(), Instant.now()));
    }
}
