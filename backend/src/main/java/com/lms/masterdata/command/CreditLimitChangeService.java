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
package com.lms.masterdata.command;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import com.lms.identity.access.AccessSubject;
import com.lms.masterdata.command.domain.BusinessPartner;
import com.lms.masterdata.command.domain.CreditLimitChange;
import com.lms.masterdata.events.CreditLimitChanged;
import com.lms.shared.audit.AuditTrail;
import com.lms.shared.config.Json;
import com.lms.shared.error.BusinessRuleViolationException;
import com.lms.shared.error.ResourceNotFoundException;
import com.lms.shared.tenant.TenantContext;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Changing a customer's credit limits, maker and checker.
 *
 * <p>A limit never changes on one person's word. Someone holding
 * {@code CREDIT_LIMIT_REQUEST} asks, with a reason; someone else holding
 * {@code CREDIT_LIMIT_APPROVE}, whose approval limit covers the new limit,
 * approves -- and only then does the partner's limit move. Every request
 * stays as an entry in the change log whatever its outcome.
 */
@Service
public class CreditLimitChangeService {

    private final BusinessPartnerRepository partners;
    private final CreditLimitChangeRepository changes;
    private final AuditTrail audit;
    private final ApplicationEventPublisher events;

    public CreditLimitChangeService(BusinessPartnerRepository partners,
                                    CreditLimitChangeRepository changes, AuditTrail audit,
                                    ApplicationEventPublisher events) {
        this.partners = partners;
        this.changes = changes;
        this.audit = audit;
        this.events = events;
    }

    @Transactional
    @PreAuthorize("hasAuthority('CREDIT_LIMIT_REQUEST')")
    public UUID request(UUID partnerId, BigDecimal limit, BigDecimal softLimit, String reason) {
        UUID tenantId = TenantContext.requireTenantId();
        BusinessPartner partner = partners.findById(partnerId)
                .orElseThrow(() -> new ResourceNotFoundException("BusinessPartner", partnerId));
        if (partner.partnerType() != BusinessPartner.PartnerType.CUSTOMER) {
            throw new BusinessRuleViolationException("credit-limit-customer-only",
                    "Partner " + partner.code() + " is a " + partner.partnerType()
                            + "; credit limits are kept for customers");
        }
        changes.findPending(tenantId, partnerId).ifPresent(open -> {
            throw new BusinessRuleViolationException("credit-limit-change-pending",
                    "Partner " + partner.code() + " already has a credit limit change waiting"
                            + " for approval; decide that one first");
        });
        CreditLimitChange change = changes.save(CreditLimitChange.request(UUID.randomUUID(),
                tenantId, partner, limit, softLimit, reason, currentUser(), Instant.now()));
        audit.recordAtomically("CREDIT_LIMIT_CHANGE_REQUESTED", CreditGateService.AUDIT_TYPE,
                partnerId, limits(partner.creditLimit(), partner.creditSoftLimit()),
                Json.object("change", change.id().toString(),
                        "creditLimit", plain(change.requestedLimit()),
                        "softLimit", plain(change.requestedSoftLimit()),
                        "reason", change.reason()));
        return change.id();
    }

    /** Approves and applies a pending change. */
    @Transactional
    @PreAuthorize("hasAuthority('CREDIT_LIMIT_APPROVE')"
            + " and @abac.permits('CREDIT_LIMIT_APPROVE', 'CREDIT_LIMIT_CHANGE', #changeId)")
    public void approve(UUID changeId, String note) {
        UUID tenantId = TenantContext.requireTenantId();
        CreditLimitChange change = require(changeId);
        Instant now = Instant.now();
        CreditLimitChange approved = changes.save(change.approve(currentUser(), note, now));

        BusinessPartner partner = partners.findById(change.partnerId())
                .orElseThrow(() -> new ResourceNotFoundException("BusinessPartner",
                        change.partnerId()));
        BusinessPartner updated = partners.save(partner.withCreditLimits(
                approved.requestedLimit(), approved.requestedSoftLimit()));

        audit.recordAtomically("CREDIT_LIMIT_CHANGED", CreditGateService.AUDIT_TYPE, partner.id(),
                limits(partner.creditLimit(), partner.creditSoftLimit()),
                Json.object("change", approved.id().toString(),
                        "creditLimit", plain(updated.creditLimit()),
                        "softLimit", plain(updated.creditSoftLimit()),
                        "requestedBy", approved.requestedBy(), "approvedBy", approved.decidedBy()));
        events.publishEvent(new CreditLimitChanged(tenantId, partner.id(), approved.id(),
                partner.creditLimit(), partner.creditSoftLimit(), updated.creditLimit(),
                updated.creditSoftLimit(), approved.requestedBy(), approved.decidedBy(), now));
    }

    /**
     * Rejects a pending change, leaving the limits as they are. The requester
     * may also use this to withdraw their own request.
     */
    @Transactional
    @PreAuthorize("hasAuthority('CREDIT_LIMIT_APPROVE')")
    public void reject(UUID changeId, String note) {
        CreditLimitChange change = require(changeId);
        CreditLimitChange rejected = changes.save(change.reject(currentUser(), note, Instant.now()));
        audit.recordAtomically("CREDIT_LIMIT_CHANGE_REJECTED", CreditGateService.AUDIT_TYPE,
                change.partnerId(), null,
                Json.object("change", rejected.id().toString(), "rejectedBy", rejected.decidedBy(),
                        "note", rejected.decisionNote()));
    }

    private CreditLimitChange require(UUID changeId) {
        return changes.findById(changeId)
                .orElseThrow(() -> new ResourceNotFoundException("CreditLimitChange", changeId));
    }

    /** Who is acting, as the token names them; what maker and checker are compared on. */
    static String currentUser() {
        String user = AccessSubject.current().userId();
        if (user == null || user.isBlank()) {
            throw new BusinessRuleViolationException("credit-actor-unknown",
                    "A credit decision needs a signed-in person to attribute it to");
        }
        return user;
    }

    private static Json limits(BigDecimal limit, BigDecimal soft) {
        return Json.object("creditLimit", plain(limit), "softLimit", plain(soft));
    }

    private static String plain(BigDecimal value) {
        return value == null ? null : value.toPlainString();
    }
}
