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
package com.lms.masterdata.command.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import com.lms.shared.error.BusinessRuleViolationException;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.relational.core.mapping.Table;

/**
 * A request to change a customer's credit limits, and once decided, its entry
 * in the change log.
 *
 * <p>Maker and checker are different people: whoever asked cannot approve.
 * The previous limits are copied in when the request is made, so the log
 * reads as before and after on its own.
 */
@Table("credit_limit_change")
public record CreditLimitChange(
        @Id UUID id,
        UUID tenantId,
        UUID partnerId,
        Status status,
        BigDecimal previousLimit,
        BigDecimal previousSoftLimit,
        BigDecimal requestedLimit,
        BigDecimal requestedSoftLimit,
        String reason,
        String requestedBy,
        Instant requestedAt,
        String decidedBy,
        Instant decidedAt,
        String decisionNote,
        @Version Long version) {

    public enum Status {
        PENDING, APPROVED, REJECTED
    }

    /**
     * Opens a request.
     *
     * <p>The soft limit may equal the hard limit -- a warning at the moment of
     * refusal is pointless but harmless -- and may not exceed it, where it
     * would never be reached.
     */
    public static CreditLimitChange request(UUID id, UUID tenantId, BusinessPartner partner,
                                            BigDecimal limit, BigDecimal softLimit,
                                            String reason, String requestedBy, Instant at) {
        if (limit == null || limit.signum() < 0) {
            throw new IllegalArgumentException("A credit limit must be zero or more");
        }
        if (softLimit != null && softLimit.signum() < 0) {
            throw new IllegalArgumentException("A soft limit must be zero or more");
        }
        if (softLimit != null && softLimit.compareTo(limit) > 0) {
            throw new BusinessRuleViolationException("credit-soft-limit-above-limit",
                    "The soft limit " + softLimit.toPlainString() + " is above the credit limit "
                            + limit.toPlainString() + "; it may be at most equal to it");
        }
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("A credit limit change needs a reason");
        }
        return new CreditLimitChange(id, tenantId, partner.id(), Status.PENDING,
                partner.creditLimit(), partner.creditSoftLimit(), limit, softLimit,
                reason.strip(), requestedBy, at, null, null, null, null);
    }

    /** Approves the request; refused for the person who made it. */
    public CreditLimitChange approve(String approver, String note, Instant at) {
        requirePending();
        if (Objects.equals(approver, requestedBy)) {
            throw new BusinessRuleViolationException("credit-limit-change-self-approval",
                    "A credit limit change must be approved by someone other than the person who"
                            + " asked for it");
        }
        return decided(Status.APPROVED, approver, note, at);
    }

    /** Rejects (or, by its requester, withdraws) the request. */
    public CreditLimitChange reject(String decider, String note, Instant at) {
        requirePending();
        return decided(Status.REJECTED, decider, note, at);
    }

    private void requirePending() {
        if (status != Status.PENDING) {
            throw new BusinessRuleViolationException("credit-limit-change-not-pending",
                    "This credit limit change was already " + status);
        }
    }

    private CreditLimitChange decided(Status outcome, String decider, String note, Instant at) {
        return new CreditLimitChange(id, tenantId, partnerId, outcome, previousLimit,
                previousSoftLimit, requestedLimit, requestedSoftLimit, reason, requestedBy,
                requestedAt, decider, at, note == null || note.isBlank() ? null : note.strip(),
                version);
    }
}
