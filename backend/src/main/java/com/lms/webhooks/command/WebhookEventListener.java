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
package com.lms.webhooks.command;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.lms.approvals.events.ApprovalDecided;
import com.lms.approvals.events.ApprovalRequested;
import com.lms.delivery.events.ClaimRaised;
import com.lms.delivery.events.ClaimStatusChanged;
import com.lms.delivery.events.DeliveryProofRecorded;
import com.lms.execution.events.GateEntryRecorded;
import com.lms.execution.events.GateExitRecorded;
import com.lms.execution.events.TripCancelled;
import com.lms.execution.events.TripCompleted;
import com.lms.execution.events.TripDispatched;
import com.lms.execution.events.TripStopArrived;
import com.lms.execution.events.TripStopDeparted;
import com.lms.order.events.OrderAmended;
import com.lms.order.events.OrderCancelled;
import com.lms.order.events.OrderClosed;
import com.lms.order.events.OrderFulfilled;
import com.lms.order.events.OrderRaised;
import com.lms.order.events.OrderValidated;
import com.lms.planning.events.ConsignmentCancelled;
import com.lms.planning.events.ConsignmentSplit;
import com.lms.planning.events.ConsignmentsDelivered;
import com.lms.planning.events.ConsignmentsGenerated;
import com.lms.planning.events.ConsignmentsInTransit;
import com.lms.shared.tenant.TenantContext;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Records each subscribed event in the webhook outbox, and does nothing else.
 *
 * <p>After commit, scope first and transaction second, as every listener here
 * does (see TripBillingListener): the tenant scope must be bound before the
 * transaction starts or row-level security sees no tenant. Posting is the
 * dispatcher's job, on its own timer, so a receiver that is down never reaches
 * back into the change that raised the event. The publication registry tracks
 * this listener like any other, and a republished event is recorded once,
 * because its event id is derived from the event.
 *
 * <p>Each payload lists its fields explicitly: what an outside system receives
 * is a contract, and it should not grow because a record gained a field.
 */
@Component
public class WebhookEventListener {

    private final WebhookOutbox outbox;
    private final TransactionTemplate transactions;

    public WebhookEventListener(WebhookOutbox outbox, PlatformTransactionManager transactionManager) {
        this.outbox = outbox;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    @TransactionalEventListener
    public void on(OrderValidated e) {
        record(e.tenantId(), WebhookEventType.ORDER_VALIDATED, e.orderId().toString(), e.occurredAt(),
                fields("orderId", e.orderId(), "orderNo", e.orderNo(), "lineCount", e.lineCount(),
                        "totalChargeableWeightKg", e.totalChargeableWeightKg(),
                        "containsHazmat", e.containsHazmat(), "freightMode", e.freightMode()));
    }

    @TransactionalEventListener
    public void on(OrderRaised e) {
        record(e.tenantId(), WebhookEventType.ORDER_RAISED, e.orderId().toString(), e.occurredAt(),
                fields("orderId", e.orderId(), "orderNo", e.orderNo(),
                        "customerPartnerId", e.customerPartnerId(), "freightMode", e.freightMode(),
                        "source", e.source(), "numberAssigned", e.numberAssigned()));
    }

    /** Keyed by version: each amendment is its own delivery, a redelivery of one is not. */
    @TransactionalEventListener
    public void on(OrderAmended e) {
        record(e.tenantId(), WebhookEventType.ORDER_AMENDED, e.orderId() + ":" + e.version(),
                e.occurredAt(), fields("orderId", e.orderId(), "orderNo", e.orderNo(),
                        "version", e.version(), "lineCount", e.lineCount(),
                        "totalChargeableWeightKg", e.totalChargeableWeightKg()));
    }

    @TransactionalEventListener
    public void on(OrderCancelled e) {
        record(e.tenantId(), WebhookEventType.ORDER_CANCELLED, e.orderId().toString(), e.occurredAt(),
                fields("orderId", e.orderId(), "orderNo", e.orderNo(),
                        "previousStatus", e.previousStatus(), "reason", e.reason()));
    }

    @TransactionalEventListener
    public void on(OrderFulfilled e) {
        record(e.tenantId(), WebhookEventType.ORDER_FULFILLED, e.orderId().toString(), e.occurredAt(),
                fields("orderId", e.orderId(), "orderNo", e.orderNo()));
    }

    @TransactionalEventListener
    public void on(OrderClosed e) {
        record(e.tenantId(), WebhookEventType.ORDER_CLOSED, e.orderId().toString(), e.occurredAt(),
                fields("orderId", e.orderId(), "orderNo", e.orderNo(), "previousStatus", e.previousStatus(),
                        "closeKind", e.closeKind(), "reason", e.reason(), "linesClosed", e.linesClosed()));
    }

    @TransactionalEventListener
    public void on(ConsignmentsGenerated e) {
        record(e.tenantId(), WebhookEventType.CONSIGNMENTS_GENERATED, e.orderId().toString(), e.occurredAt(),
                fields("orderId", e.orderId(), "consignmentIds", e.consignmentIds().stream().map(UUID::toString).toList(),
                        "trackingRefs", e.trackingRefs(), "freightMode", e.freightMode()));
    }

    @TransactionalEventListener
    public void on(ConsignmentSplit e) {
        record(e.tenantId(), WebhookEventType.CONSIGNMENT_SPLIT, e.consignmentId().toString(), e.occurredAt(),
                fields("consignmentId", e.consignmentId(), "trackingRef", e.trackingRef(),
                        "orderId", e.orderId(),
                        "partIds", e.partIds().stream().map(UUID::toString).toList(),
                        "partTrackingRefs", e.partTrackingRefs()));
    }

    /** One consignment cancelled on its own; one cancelled with its order is ORDER_CANCELLED. */
    @TransactionalEventListener
    public void on(ConsignmentCancelled e) {
        record(e.tenantId(), WebhookEventType.CONSIGNMENT_CANCELLED, e.consignmentId().toString(),
                e.cancelledAt(), fields("consignmentId", e.consignmentId(), "trackingRef", e.trackingRef(),
                        "orderId", e.orderId(), "loadId", e.loadId(), "reason", e.reason()));
    }

    @TransactionalEventListener
    public void on(TripDispatched e) {
        record(e.tenantId(), WebhookEventType.TRIP_DISPATCHED, e.tripId().toString(), e.occurredAt(),
                fields("tripId", e.tripId(), "tripNo", e.tripNo(), "loadId", e.loadId(),
                        "originTerminalId", e.originTerminalId(),
                        "destinationTerminalId", e.destinationTerminalId(),
                        "payloadWeightKg", e.payloadWeightKg(), "dispatchedAt", e.dispatchedAt()));
    }

    @TransactionalEventListener
    public void on(TripCompleted e) {
        record(e.tenantId(), WebhookEventType.TRIP_COMPLETED, e.tripId().toString(), e.occurredAt(),
                fields("tripId", e.tripId(), "tripNo", e.tripNo(), "loadId", e.loadId(),
                        "dispatchedAt", e.dispatchedAt(), "completedAt", e.completedAt(),
                        "payloadWeightKg", e.payloadWeightKg()));
    }

    @TransactionalEventListener
    public void on(TripCancelled e) {
        record(e.tenantId(), WebhookEventType.TRIP_CANCELLED, e.tripId().toString(), e.occurredAt(),
                fields("tripId", e.tripId(), "tripNo", e.tripNo(), "loadId", e.loadId(),
                        "reason", e.reason(), "cancelledAt", e.cancelledAt()));
    }

    @TransactionalEventListener
    public void on(GateEntryRecorded e) {
        record(e.tenantId(), WebhookEventType.GATE_ENTRY_RECORDED, e.visitId().toString(), e.occurredAt(),
                fields("visitId", e.visitId(), "orgUnitId", e.orgUnitId(), "terminalId", e.terminalId(),
                        "vehicleId", e.vehicleId(), "registrationNo", e.registrationNo(), "purpose", e.purpose(),
                        "tripId", e.tripId(), "gatePassNo", e.gatePassNo(), "enteredAt", e.enteredAt()));
    }

    @TransactionalEventListener
    public void on(GateExitRecorded e) {
        record(e.tenantId(), WebhookEventType.GATE_EXIT_RECORDED, e.visitId().toString(), e.occurredAt(),
                fields("visitId", e.visitId(), "orgUnitId", e.orgUnitId(), "terminalId", e.terminalId(),
                        "vehicleId", e.vehicleId(), "registrationNo", e.registrationNo(), "purpose", e.purpose(),
                        "tripId", e.tripId(), "enteredAt", e.enteredAt(), "exitedAt", e.exitedAt(),
                        "dwellMinutes", e.dwellMinutes()));
    }

    @TransactionalEventListener
    public void on(TripStopArrived e) {
        record(e.tenantId(), WebhookEventType.TRIP_STOP_ARRIVED, e.stopId().toString(), e.occurredAt(),
                fields("tripId", e.tripId(), "tripNo", e.tripNo(), "stopId", e.stopId(), "seq", e.seq(),
                        "kind", e.kind(), "terminalId", e.terminalId(), "plannedArrivalAt", e.plannedArrivalAt(),
                        "arrivedAt", e.arrivedAt(), "outOfOrder", e.outOfOrder(), "source", e.source()));
    }

    @TransactionalEventListener
    public void on(TripStopDeparted e) {
        record(e.tenantId(), WebhookEventType.TRIP_STOP_DEPARTED, e.stopId().toString(), e.occurredAt(),
                fields("tripId", e.tripId(), "tripNo", e.tripNo(), "stopId", e.stopId(), "seq", e.seq(),
                        "kind", e.kind(), "terminalId", e.terminalId(), "arrivedAt", e.arrivedAt(),
                        "departedAt", e.departedAt(), "completed", e.completed(), "source", e.source()));
    }

    @TransactionalEventListener
    public void on(DeliveryProofRecorded e) {
        record(e.tenantId(), WebhookEventType.DELIVERY_PROOF_RECORDED, e.consignmentId().toString(),
                e.occurredAt(), fields("consignmentId", e.consignmentId(), "deliveredAt", e.deliveredAt(),
                        "goodsCondition", e.goodsCondition()));
    }

    @TransactionalEventListener
    public void on(ClaimRaised e) {
        record(e.tenantId(), WebhookEventType.CLAIM_RAISED, e.claimId().toString(), e.occurredAt(),
                fields("claimId", e.claimId(), "claimNo", e.claimNo(), "consignmentId", e.consignmentId(),
                        "claimType", e.claimType(), "amount", e.amount(), "currency", e.currency()));
    }

    @TransactionalEventListener
    public void on(ClaimStatusChanged e) {
        record(e.tenantId(), WebhookEventType.CLAIM_STATUS_CHANGED, e.claimId() + ":" + e.toStatus(),
                e.occurredAt(), fields("claimId", e.claimId(), "claimNo", e.claimNo(),
                        "fromStatus", e.fromStatus(), "toStatus", e.toStatus(),
                        "approvedAmount", e.approvedAmount()));
    }

    /**
     * One delivery per consignment, keyed on it: a consignment leaves once. A
     * whole load's worth is recorded in one statement.
     */
    @TransactionalEventListener
    public void on(ConsignmentsInTransit e) {
        List<WebhookOutbox.Event> events = new ArrayList<>(e.consignmentIds().size());
        for (int i = 0; i < e.consignmentIds().size(); i++) {
            UUID consignmentId = e.consignmentIds().get(i);
            events.add(new WebhookOutbox.Event(consignmentId + ":" + e.loadId(), e.occurredAt(),
                    fields("consignmentId", consignmentId, "trackingRef", e.trackingRefs().get(i),
                            "loadId", e.loadId())));
        }
        recordAll(e.tenantId(), WebhookEventType.CONSIGNMENT_IN_TRANSIT, events);
    }

    /**
     * One delivery per consignment, keyed on it: a consignment is delivered
     * once. A whole load's worth is recorded in one statement.
     */
    @TransactionalEventListener
    public void on(ConsignmentsDelivered e) {
        List<WebhookOutbox.Event> events = new ArrayList<>(e.consignmentIds().size());
        for (int i = 0; i < e.consignmentIds().size(); i++) {
            UUID consignmentId = e.consignmentIds().get(i);
            events.add(new WebhookOutbox.Event(consignmentId.toString(), e.deliveredAt(),
                    fields("consignmentId", consignmentId,
                            "trackingRef", e.trackingRefs().get(i), "loadId", e.loadId(),
                            "trigger", e.trigger(), "deliveredAt", e.deliveredAt())));
        }
        recordAll(e.tenantId(), WebhookEventType.CONSIGNMENT_DELIVERED, events);
    }

    /** Keyed on the request: it is raised once. */
    @TransactionalEventListener
    public void on(ApprovalRequested e) {
        record(e.tenantId(), WebhookEventType.APPROVAL_REQUESTED, e.requestId().toString(), e.occurredAt(),
                fields("approvalId", e.requestId(), "subjectType", e.subjectType(), "subjectId", e.subjectId(),
                        "action", e.action(), "summary", e.summary(), "dueAt", e.dueAt()));
    }

    /** Keyed on the request: it closes once. The reason is free text and is not sent. */
    @TransactionalEventListener
    public void on(ApprovalDecided e) {
        record(e.tenantId(), WebhookEventType.APPROVAL_DECIDED, e.requestId().toString(), e.occurredAt(),
                fields("approvalId", e.requestId(), "subjectType", e.subjectType(), "subjectId", e.subjectId(),
                        "action", e.action(), "status", e.status()));
    }

    private void record(UUID tenantId, WebhookEventType type, String key, Instant occurredAt,
                        Map<String, Object> data) {
        TenantContext.runWith(tenantId, null, () ->
                transactions.executeWithoutResult(status ->
                        outbox.record(tenantId, type, key, occurredAt, data)));
    }

    private void recordAll(UUID tenantId, WebhookEventType type, List<WebhookOutbox.Event> events) {
        TenantContext.runWith(tenantId, null, () ->
                transactions.executeWithoutResult(status -> outbox.recordAll(tenantId, type, events)));
    }

    /** Alternating names and values; ids and times as strings, amounts as numbers. */
    private static Map<String, Object> fields(Object... namesAndValues) {
        Map<String, Object> data = new LinkedHashMap<>();
        for (int i = 0; i < namesAndValues.length; i += 2) {
            Object value = namesAndValues[i + 1];
            if (value instanceof UUID || value instanceof Instant) {
                value = value.toString();
            }
            data.put((String) namesAndValues[i], value);
        }
        return data;
    }
}
