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
package com.lms.controltower.command;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import java.util.function.Supplier;

import com.lms.controltower.command.domain.ExceptionCategory;
import com.lms.controltower.command.domain.ExceptionTicket.Source;
import com.lms.controltower.command.domain.ExceptionTicket.Subject;
import com.lms.controltower.command.domain.ExceptionTicket.SubjectType;
import com.lms.milkrun.events.CircuitStopLate;
import com.lms.milkrun.events.CircuitStopSkipped;
import com.lms.multimodal.events.ConnectionMissed;
import com.lms.shared.tenant.TenantContext;
import com.lms.warehouse.events.PickShortfallRecorded;
import com.lms.warehouse.events.ReceiptVarianceRecorded;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Raises tickets from what multimodal, milk-run and warehousing report going
 * wrong.
 *
 * <p>Shaped like {@link RouteDeviationListener}: a plain after-commit
 * listener, with the tenant scope taken from the event and bound <em>before</em>
 * a transaction is opened, because {@code TenantAwareTransactionManager} scopes
 * the connection as the transaction starts and the outbox's republication path
 * after a restart has bound nothing. The transaction is a new one, opened here
 * through a template so the ordering cannot be got wrong: after commit, the
 * publisher's transaction is finished and must not be joined.
 *
 * <p>Every handler is idempotent. Each occurrence has a source key, and the
 * service returns the existing ticket for a key it has seen. Two deliveries
 * racing past that check meet the unique index instead; the loser's
 * transaction rolls back and the ticket the winner raised stands.
 */
@Component
public class FeedExceptionListener {

    private static final Logger log = LoggerFactory.getLogger(FeedExceptionListener.class);

    private static final DateTimeFormatter AT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'").withZone(ZoneOffset.UTC);

    private final ExceptionCommandService exceptions;
    private final TransactionTemplate requiresNew;

    public FeedExceptionListener(ExceptionCommandService exceptions,
                                 PlatformTransactionManager transactionManager) {
        this.exceptions = exceptions;
        this.requiresNew = new TransactionTemplate(transactionManager);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** P2. Carries the late leg's trip when it was a road leg; a rail leg has none. */
    @TransactionalEventListener
    public void onConnectionMissed(ConnectionMissed event) {
        raise(event.tenantId(), () -> new DetectedException(
                "missed-connection:" + event.legId() + ":"
                        + event.plannedDepartureAt().toEpochMilli(),
                ExceptionCategory.MISSED_CONNECTION, Source.MISSED_CONNECTION,
                event.predecessorTripId(),
                new Subject(SubjectType.ITINERARY, event.itineraryId(), event.itineraryNo()),
                "Leg " + event.legSeq() + " (" + event.legMode() + ") of " + event.itineraryNo()
                        + " was due to leave at " + AT.format(event.plannedDepartureAt())
                        + "; the leg before it arrived too late, or has not arrived and no"
                        + " longer can in time, so the earliest it can start is "
                        + AT.format(event.earliestStartAt())));
    }

    @TransactionalEventListener
    public void onStopSkipped(CircuitStopSkipped event) {
        String reason = event.reason() == null || event.reason().isBlank()
                ? ""
                : ": " + event.reason().strip();
        raise(event.tenantId(), () -> new DetectedException(
                "milk-run-skipped:" + event.runId() + ":" + event.sequence(),
                ExceptionCategory.MILK_RUN_STOP_SKIPPED, Source.MILK_RUN_STOP_SKIPPED,
                event.tripId(), circuit(event.runId(), event.routeCode(), event.runDate()),
                "The supplier at stop " + event.sequence() + " of " + event.routeCode()
                        + " on " + event.runDate() + " was not collected from" + reason));
    }

    @TransactionalEventListener
    public void onStopLate(CircuitStopLate event) {
        raise(event.tenantId(), () -> new DetectedException(
                "milk-run-late:" + event.runId() + ":" + event.sequence(),
                ExceptionCategory.MILK_RUN_STOP_LATE, Source.MILK_RUN_STOP_LATE,
                event.tripId(), circuit(event.runId(), event.routeCode(), event.runDate()),
                "Stop " + event.sequence() + " of " + event.routeCode() + " on "
                        + event.runDate() + " was reached " + event.minutesLate()
                        + " minutes after its window closed at "
                        + AT.format(event.windowClosesAt())));
    }

    @TransactionalEventListener
    public void onReceiptVariance(ReceiptVarianceRecorded event) {
        raise(event.tenantId(), () -> new DetectedException(
                "receipt-variance:" + event.asnId() + ":" + event.lineNo(),
                ExceptionCategory.RECEIPT_VARIANCE, Source.RECEIPT_VARIANCE, null,
                new Subject(SubjectType.ASN, event.asnId(), event.asnNo()),
                "Line " + event.lineNo() + " of ASN " + event.asnNo() + ": "
                        + event.expectedQty() + " expected, " + event.receivedQty()
                        + " received (" + event.shortQty() + " short, " + event.excessQty()
                        + " excess, " + event.damagedQty() + " damaged)"));
    }

    @TransactionalEventListener
    public void onPickShortfall(PickShortfallRecorded event) {
        raise(event.tenantId(), () -> new DetectedException(
                "pick-shortfall:" + event.orderId() + ":" + event.lineNo(),
                ExceptionCategory.PICK_SHORTFALL, Source.PICK_SHORTFALL, null,
                new Subject(SubjectType.OUTBOUND_ORDER, event.orderId(), event.orderNo()),
                "Line " + event.lineNo() + " of outbound order " + event.orderNo() + ": "
                        + event.orderedQty() + " ordered, " + event.pickedQty() + " picked, "
                        + event.shortQty() + " short"));
    }

    // ------------------------------------------------------------- internals

    /** Scope first, transaction second. */
    private void raise(UUID tenantId, Supplier<DetectedException> detection) {
        DetectedException detected = detection.get();
        try {
            UUID id = TenantContext.callWith(tenantId, null, () ->
                    requiresNew.execute(status -> exceptions.raiseDetected(detected)));
            log.debug("{} is ticket {}", detected.sourceKey(), id);
        } catch (DuplicateKeyException concurrentDelivery) {
            log.debug("{} was raised by a concurrent delivery", detected.sourceKey());
        }
    }

    private static Subject circuit(UUID runId, String routeCode, LocalDate runDate) {
        return new Subject(SubjectType.CIRCUIT_RUN, runId, routeCode + " " + runDate);
    }
}
