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
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.lms.shared.error.BusinessRuleViolationException;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.relational.core.mapping.Table;

/**
 * Money paid to a vendor against a trip before its freight bill is settled:
 * requested, approved, paid with a reference, and finally deducted from what
 * the vendor is invoiced.
 *
 * <p>One advance per mode (bank, cash, fuel slip, fuel card) per trip while it
 * is alive; the total of live advances never exceeds the tenant's percentage
 * of the freight. It can be cancelled only before it is paid: after that the
 * money has left, and what is left to do is deduct it.
 */
@Table("vendor_advance")
public record VendorAdvance(
        @Id UUID id,
        UUID tenantId,
        String advanceNo,
        UUID orgUnitId,
        UUID tripId,
        UUID vendorPartnerId,
        Mode mode,
        BigDecimal amount,
        String currency,
        String reason,
        BigDecimal freightReference,
        String freightSource,
        BigDecimal maxPercent,
        Status status,
        UUID approvalId,
        UUID requestedBy,
        Instant approvedAt,
        String paidReference,
        LocalDate paidOn,
        UUID paidBy,
        UUID deductedInvoiceId,
        Instant deductedAt,
        Instant cancelledAt,
        String cancelReason,
        @Version Long version,
        Instant createdAt,
        Instant updatedAt) {

    public enum Mode {
        BANK,
        CASH,
        FUEL_SLIP,
        FUEL_CARD
    }

    public enum Status {
        PENDING_APPROVAL,
        APPROVED,
        PAID,
        DEDUCTED,
        REJECTED,
        CANCELLED;

        /** Whether an advance in this state still counts against the cap and holds its mode. */
        public boolean live() {
            return this != REJECTED && this != CANCELLED;
        }

        /** The states the database's partial index and the cap sum read. */
        public static List<String> liveNames() {
            return List.of(PENDING_APPROVAL.name(), APPROVED.name(), PAID.name(), DEDUCTED.name());
        }
    }

    private static final Map<Status, Set<Status>> TRANSITIONS = new EnumMap<>(Status.class);

    static {
        TRANSITIONS.put(Status.PENDING_APPROVAL, EnumSet.of(Status.APPROVED, Status.REJECTED, Status.CANCELLED));
        TRANSITIONS.put(Status.APPROVED, EnumSet.of(Status.PAID, Status.CANCELLED));
        TRANSITIONS.put(Status.PAID, EnumSet.of(Status.DEDUCTED));
        TRANSITIONS.put(Status.DEDUCTED, EnumSet.noneOf(Status.class));
        TRANSITIONS.put(Status.REJECTED, EnumSet.noneOf(Status.class));
        TRANSITIONS.put(Status.CANCELLED, EnumSet.noneOf(Status.class));
    }

    public static VendorAdvance request(UUID id, UUID tenantId, String advanceNo, UUID orgUnitId, UUID tripId,
                                        UUID vendorPartnerId, Mode mode, BigDecimal amount, String currency,
                                        String reason, BigDecimal freightReference, String freightSource,
                                        BigDecimal maxPercent, boolean needsApproval, UUID requestedBy) {
        if (mode == null) {
            throw new BusinessRuleViolationException("advance-mode-required",
                    "Say how the advance is paid: bank, cash, fuel slip or fuel card");
        }
        if (amount == null || amount.signum() <= 0) {
            throw new BusinessRuleViolationException("advance-amount-invalid", "An advance is an amount above zero");
        }
        if (reason == null || reason.isBlank() || reason.strip().length() > 500) {
            throw new BusinessRuleViolationException("advance-reason-required",
                    "Say why the advance is needed, in 1 to 500 characters");
        }
        Instant now = Instant.now();
        return new VendorAdvance(id, tenantId, advanceNo, orgUnitId, tripId, vendorPartnerId, mode, amount, currency,
                reason.strip(), freightReference, freightSource, maxPercent,
                needsApproval ? Status.PENDING_APPROVAL : Status.APPROVED, null, requestedBy,
                needsApproval ? null : now, null, null, null, null, null, null, null, null, now, now);
    }

    public VendorAdvance withApproval(UUID approval) {
        return copy(status, approval, approvedAt, paidReference, paidOn, paidBy, deductedInvoiceId, deductedAt,
                cancelledAt, cancelReason);
    }

    public VendorAdvance approved(Instant at) {
        requireMove(Status.APPROVED);
        return copy(Status.APPROVED, approvalId, at, paidReference, paidOn, paidBy, deductedInvoiceId, deductedAt,
                cancelledAt, cancelReason);
    }

    public VendorAdvance closedWithoutApproval() {
        requireMove(Status.REJECTED);
        return copy(Status.REJECTED, approvalId, approvedAt, paidReference, paidOn, paidBy, deductedInvoiceId,
                deductedAt, cancelledAt, cancelReason);
    }

    /** Paid out, with the reference of the transfer, slip or card top-up. */
    public VendorAdvance paid(String reference, LocalDate on, UUID by) {
        if (reference == null || reference.isBlank() || reference.strip().length() > 80) {
            throw new BusinessRuleViolationException("advance-reference-required",
                    "A paid advance carries its reference, 1 to 80 characters");
        }
        if (on == null) {
            throw new BusinessRuleViolationException("advance-paid-date-required", "Say the day the advance was paid");
        }
        requireMove(Status.PAID);
        return copy(Status.PAID, approvalId, approvedAt, reference.strip(), on, by, deductedInvoiceId, deductedAt,
                cancelledAt, cancelReason);
    }

    /** Taken off the invoice that settles the trip. */
    public VendorAdvance deducted(UUID invoiceId, Instant at) {
        requireMove(Status.DEDUCTED);
        return copy(Status.DEDUCTED, approvalId, approvedAt, paidReference, paidOn, paidBy, invoiceId, at,
                cancelledAt, cancelReason);
    }

    /** Withdrawn before it was paid. */
    public VendorAdvance cancelled(String why, Instant at) {
        if (why == null || why.isBlank() || why.strip().length() > 500) {
            throw new BusinessRuleViolationException("advance-cancel-reason-required",
                    "Say why the advance is cancelled, in 1 to 500 characters");
        }
        if (status == Status.PAID || status == Status.DEDUCTED) {
            throw new BusinessRuleViolationException("advance-already-paid",
                    "Advance " + advanceNo + " has been paid and can no longer be cancelled");
        }
        requireMove(Status.CANCELLED);
        return copy(Status.CANCELLED, approvalId, approvedAt, paidReference, paidOn, paidBy, deductedInvoiceId,
                deductedAt, at, why.strip());
    }

    private void requireMove(Status target) {
        if (!TRANSITIONS.getOrDefault(status, Set.of()).contains(target)) {
            throw new BusinessRuleViolationException("advance-illegal-transition",
                    "Advance " + advanceNo + " cannot move from " + status + " to " + target);
        }
    }

    private VendorAdvance copy(Status newStatus, UUID approval, Instant approved, String reference, LocalDate on,
                               UUID by, UUID invoice, Instant deductedAt, Instant cancelledAt, String why) {
        return new VendorAdvance(id, tenantId, advanceNo, orgUnitId, tripId, vendorPartnerId, mode, amount, currency,
                reason, freightReference, freightSource, maxPercent, newStatus, approval, requestedBy, approved,
                reference, on, by, invoice, deductedAt, cancelledAt, why, version, createdAt, Instant.now());
    }
}
