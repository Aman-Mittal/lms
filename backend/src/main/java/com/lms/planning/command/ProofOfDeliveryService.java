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

import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.lms.order.api.OrderPlanningPort;
import com.lms.planning.command.domain.Consignment;
import com.lms.planning.command.domain.Consignment.ConsignmentStatus;
import com.lms.planning.events.ConsignmentsDelivered;
import com.lms.shared.audit.TransitionAudit;
import com.lms.shared.tenant.TenantContext;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A confirmed proof of delivery delivers its consignment.
 *
 * <p>Trip completion does not wait for a proof, and a proof does not wait for
 * the trip: whichever comes first moves the consignment to DELIVERED, and the
 * other finds it already there and changes nothing.
 *
 * <p>The part-load state machine is respected. A docket is delivered only by a
 * proof taken on its last leg, out for delivery from its destination hub; a
 * proof that somehow arrived while the docket was on its line haul or waiting
 * at a hub moves nothing, because the docket has not reached its consignee.
 */
@Service
public class ProofOfDeliveryService {

    /** A full truckload that has left, by its own status. */
    private static final Set<ConsignmentStatus> FTL_ON_ITS_WAY = EnumSet.of(
            ConsignmentStatus.ASSIGNED_TO_LOAD, ConsignmentStatus.IN_TRANSIT);

    private final ConsignmentRepository consignments;
    private final PlanningLinks links;
    private final OrderPlanningPort orderPlanning;
    private final TransitionAudit audit;
    private final ApplicationEventPublisher events;

    public ProofOfDeliveryService(ConsignmentRepository consignments, PlanningLinks links,
                                  OrderPlanningPort orderPlanning, TransitionAudit audit,
                                  ApplicationEventPublisher events) {
        this.consignments = consignments;
        this.links = links;
        this.orderPlanning = orderPlanning;
        this.audit = audit;
        this.events = events;
    }

    /**
     * Delivers the consignment a proof was confirmed for, if it is not already.
     *
     * <p>A full truckload still reading ASSIGNED_TO_LOAD was dispatched before
     * dispatch moved it, and the delivery module only confirms a proof for one
     * whose trip has left; it walks through IN_TRANSIT in memory and is written
     * once. Unguarded: the actor is the platform reacting to a recorded proof.
     *
     * @return whether this call moved the consignment; false on a repeat, for a
     *         consignment that is not this tenant's, and for a docket not yet on
     *         its last leg
     */
    @Transactional
    public boolean deliverOnProof(UUID consignmentId, Instant deliveredAt) {
        UUID tenantId = TenantContext.requireTenantId();
        Consignment before = consignments.findById(consignmentId).orElse(null);
        if (before == null || !deliverable(before)) {
            return false;
        }
        Consignment moving = before.status() == ConsignmentStatus.ASSIGNED_TO_LOAD
                ? before.transitionTo(ConsignmentStatus.IN_TRANSIT) : before;
        Consignment delivered = consignments.save(moving.transitionTo(ConsignmentStatus.DELIVERED));
        audit.recordAll("CONSIGNMENT_DELIVERED", PlanningAudit.CONSIGNMENT,
                List.of(PlanningAudit.entry(before, delivered)));
        events.publishEvent(new ConsignmentsDelivered(tenantId, null, List.of(delivered.id()),
                List.of(delivered.trackingRef()), ConsignmentsDelivered.PROOF_OF_DELIVERY,
                deliveredAt == null ? Instant.now() : deliveredAt));
        if (delivered.orderId() != null) {
            if (links.orderIdsWithNothingLeftToCarry(tenantId, List.of(delivered.orderId())).isEmpty()) {
                orderPlanning.recordPartlyDelivered(delivered.orderId());
            } else {
                orderPlanning.recordFulfilled(delivered.orderId());
            }
        }
        return true;
    }

    /**
     * A reversed proof takes back the delivery it made.
     *
     * <p>Only a delivery the proof made: if a completed trip carries the
     * consignment, completion delivers it whatever the proof says, so it stays
     * DELIVERED. Otherwise it goes back to IN_TRANSIT (a full truckload) or
     * OUT_FOR_DELIVERY (a docket) to await a new proof. The order's coverage
     * is not rewound: a fulfilled order stays fulfilled, and is closed or
     * reopened by a person. Unguarded and safe to repeat -- a consignment not
     * DELIVERED is left alone.
     *
     * @return whether this call moved the consignment
     */
    @Transactional
    public boolean undeliverOnReversal(UUID consignmentId) {
        UUID tenantId = TenantContext.requireTenantId();
        Consignment before = consignments.findById(consignmentId).orElse(null);
        if (before == null || before.status() != ConsignmentStatus.DELIVERED
                || links.carriedByCompletedTrip(tenantId, consignmentId)) {
            return false;
        }
        Consignment restored = consignments.save(before.proofReversed());
        audit.recordAll("CONSIGNMENT_DELIVERY_REVERSED", PlanningAudit.CONSIGNMENT,
                List.of(PlanningAudit.entry(before, restored)));
        return true;
    }

    /** Whether a proof can deliver this consignment now; see the class comment. */
    static boolean deliverable(Consignment consignment) {
        if (consignment.isDocket()) {
            return consignment.status() == ConsignmentStatus.OUT_FOR_DELIVERY;
        }
        return FTL_ON_ITS_WAY.contains(consignment.status());
    }
}
