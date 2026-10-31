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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import com.lms.delivery.events.ClaimRaised;
import com.lms.delivery.events.ClaimStatusChanged;
import com.lms.delivery.events.DeliveryProofRecorded;
import com.lms.execution.events.TripCancelled;
import com.lms.execution.events.TripCompleted;
import com.lms.execution.events.TripDispatched;
import com.lms.order.events.OrderValidated;
import com.lms.planning.events.ConsignmentsGenerated;
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
    public void on(ConsignmentsGenerated e) {
        record(e.tenantId(), WebhookEventType.CONSIGNMENTS_GENERATED, e.orderId().toString(), e.occurredAt(),
                fields("orderId", e.orderId(), "consignmentIds", e.consignmentIds().stream().map(UUID::toString).toList(),
                        "trackingRefs", e.trackingRefs(), "freightMode", e.freightMode()));
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
    public void on(DeliveryProofRecorded e) {
        record(e.tenantId(), WebhookEventType.DELIVERY_PROOF_RECORDED, e.consignmentId().toString(),
                e.occurredAt(), fields("consignmentId", e.consignmentId(), "deliveredAt", e.deliveredAt()));
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

    private void record(UUID tenantId, WebhookEventType type, String key, Instant occurredAt,
                        Map<String, Object> data) {
        TenantContext.runWith(tenantId, null, () ->
                transactions.executeWithoutResult(status ->
                        outbox.record(tenantId, type, key, occurredAt, data)));
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
