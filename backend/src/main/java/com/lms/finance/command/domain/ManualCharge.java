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

package com.lms.finance.command.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.lms.shared.error.BusinessRuleViolationException;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.relational.core.mapping.Table;

/**
 * A charge raised by hand against a trip, a consignment or an order: from a
 * catalogue entry, a quantity and a rate (the entry's unless overridden), with
 * the reason and a reference to the evidence.
 *
 * <p>Money that moves is never edited. A charge is raised, approved when its
 * amount is above the tenant's threshold, and cancelled only before it is
 * billed: the receivable before its client charge is on an invoice, the
 * payable before the freight bill it is a line of leaves the draft state. What
 * it did elsewhere (the client charge, the bill line) is undone by the same
 * transaction as the cancellation, never by editing the other record.
 *
 * <p>{@code PENDING_APPROVAL} is the only state in which the charge has done
 * nothing yet.
 */
@Table("manual_charge")
public record ManualCharge(
        @Id UUID id,
        UUID tenantId,
        String chargeNo,
        UUID orgUnitId,
        UUID catalogueId,
        String chargeCode,
        String chargeName,
        ChargeBasis basis,
        ChargeDirection.Side direction,
        SubjectType subjectType,
        UUID subjectId,
        String subjectRef,
        UUID tripId,
        UUID customerPartnerId,
        UUID vendorPartnerId,
        BigDecimal quantity,
        BigDecimal rate,
        BigDecimal amount,
        String currency,
        String reason,
        String evidenceRef,
        Status status,
        UUID approvalId,
        UUID clientChargeId,
        UUID freightBillId,
        Instant attachedAt,
        UUID requestedBy,
        Instant decidedAt,
        Instant cancelledAt,
        String cancelReason,
        @Version Long version,
        Instant createdAt,
        Instant updatedAt) {

    /** What a charge is raised against. */
    public enum SubjectType {
        TRIP,
        CONSIGNMENT,
        ORDER
    }

    public enum Status {
        /** Above the threshold and waiting for a decision; it has done nothing yet. */
        PENDING_APPROVAL,
        /** In force: a receivable has a client charge (where the customer has a contract), a payable a bill line or a place in the queue. */
        APPROVED,
        REJECTED,
        CANCELLED
    }

    private static final Map<Status, Set<Status>> TRANSITIONS = new EnumMap<>(Status.class);

    static {
        TRANSITIONS.put(Status.PENDING_APPROVAL, EnumSet.of(Status.APPROVED, Status.REJECTED, Status.CANCELLED));
        TRANSITIONS.put(Status.APPROVED, EnumSet.of(Status.CANCELLED));
        TRANSITIONS.put(Status.REJECTED, EnumSet.noneOf(Status.class));
        TRANSITIONS.put(Status.CANCELLED, EnumSet.noneOf(Status.class));
    }

    /** What a charge is about, found by the service before it is raised. */
    public record Subject(SubjectType type, UUID id, String ref, UUID orgUnitId, UUID tripId, UUID customerPartnerId,
                          UUID vendorPartnerId) {
    }

    /**
     * A new charge: pending when {@code needsApproval}, in force otherwise.
     *
     * @param party the customer for a receivable, the vendor for a payable
     */
    public static ManualCharge raise(UUID id, UUID tenantId, String chargeNo, ChargeCatalogueItem entry,
                                     ChargeDirection.Side side, Subject subject, UUID party, BigDecimal quantity,
                                     BigDecimal rate, String reason, String evidenceRef, boolean needsApproval,
                                     UUID requestedBy) {
        if (quantity == null || quantity.signum() <= 0) {
            throw new BusinessRuleViolationException("manual-charge-quantity-invalid",
                    "A charge needs a quantity above zero");
        }
        if (rate == null || rate.signum() < 0) {
            throw new BusinessRuleViolationException("manual-charge-rate-invalid", "A rate is zero or more");
        }
        if (reason == null || reason.isBlank() || reason.strip().length() > 500) {
            throw new BusinessRuleViolationException("manual-charge-reason-required",
                    "Say why the charge is raised, in 1 to 500 characters");
        }
        BigDecimal charged = entry.basis().chargedQuantity(quantity);
        BigDecimal amount = entry.basis().amount(quantity, rate);
        if (amount.signum() <= 0) {
            throw new BusinessRuleViolationException("manual-charge-amount-zero",
                    "A charge of nothing is not raised: the amount is quantity times rate, rounded to two decimals");
        }
        Instant now = Instant.now();
        boolean receivable = side == ChargeDirection.Side.RECEIVABLE;
        return new ManualCharge(id, tenantId, chargeNo, subject.orgUnitId(), entry.id(), entry.code(), entry.name(),
                entry.basis(), side, subject.type(), subject.id(), subject.ref(), subject.tripId(),
                receivable ? party : null, receivable ? null : party, charged, rate, amount, entry.currency(),
                reason.strip(), evidenceRef == null || evidenceRef.isBlank() ? null : evidenceRef.strip(),
                needsApproval ? Status.PENDING_APPROVAL : Status.APPROVED, null, null, null, null, requestedBy,
                needsApproval ? null : now, null, null, null, now, now);
    }

    public boolean isReceivable() {
        return direction == ChargeDirection.Side.RECEIVABLE;
    }

    /** The decision came: approved. */
    public ManualCharge approved(Instant at) {
        return moveTo(Status.APPROVED, at);
    }

    /** The approval was rejected, withdrawn or lapsed. */
    public ManualCharge closedWithoutApproval(Instant at) {
        return moveTo(Status.REJECTED, at);
    }

    /** Withdrawn before it was billed, with the reason. */
    public ManualCharge cancelled(String why, Instant at) {
        if (why == null || why.isBlank() || why.strip().length() > 500) {
            throw new BusinessRuleViolationException("manual-charge-cancel-reason-required",
                    "Say why the charge is cancelled, in 1 to 500 characters");
        }
        requireMove(Status.CANCELLED);
        return copy(Status.CANCELLED, approvalId, clientChargeId, freightBillId, attachedAt, decidedAt, at,
                why.strip(), Instant.now());
    }

    public ManualCharge withApproval(UUID approval) {
        return copy(status, approval, clientChargeId, freightBillId, attachedAt, decidedAt, cancelledAt, cancelReason,
                Instant.now());
    }

    public ManualCharge withClientCharge(UUID clientCharge) {
        return copy(status, approvalId, clientCharge, freightBillId, attachedAt, decidedAt, cancelledAt, cancelReason,
                Instant.now());
    }

    public ManualCharge attachedTo(UUID billId, Instant at) {
        return copy(status, approvalId, clientChargeId, billId, at, decidedAt, cancelledAt, cancelReason, Instant.now());
    }

    public ManualCharge detached() {
        return copy(status, approvalId, clientChargeId, null, null, decidedAt, cancelledAt, cancelReason, Instant.now());
    }

    private ManualCharge moveTo(Status target, Instant at) {
        requireMove(target);
        return copy(target, approvalId, clientChargeId, freightBillId, attachedAt, at, cancelledAt, cancelReason,
                Instant.now());
    }

    private void requireMove(Status target) {
        if (!TRANSITIONS.getOrDefault(status, Set.of()).contains(target)) {
            throw new BusinessRuleViolationException("manual-charge-illegal-transition",
                    "Charge " + chargeNo + " cannot move from " + status + " to " + target);
        }
    }

    private ManualCharge copy(Status newStatus, UUID approval, UUID clientCharge, UUID bill, Instant attached,
                              Instant decided, Instant cancelled, String why, Instant updated) {
        return new ManualCharge(id, tenantId, chargeNo, orgUnitId, catalogueId, chargeCode, chargeName, basis,
                direction, subjectType, subjectId, subjectRef, tripId, customerPartnerId, vendorPartnerId, quantity,
                rate, amount, currency, reason, evidenceRef, newStatus, approval, clientCharge, bill, attached,
                requestedBy, decided, cancelled, why, version, createdAt, updated);
    }
}
