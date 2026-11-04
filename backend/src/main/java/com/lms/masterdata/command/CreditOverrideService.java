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
import java.time.LocalDate;
import java.util.UUID;

import com.lms.masterdata.api.CustomerCreditPort.CreditCheck;
import com.lms.masterdata.command.domain.BusinessPartner;
import com.lms.masterdata.command.domain.CreditOverride;
import com.lms.shared.audit.AuditTrail;
import com.lms.shared.config.Json;
import com.lms.shared.error.BusinessRuleViolationException;
import com.lms.shared.error.ResourceNotFoundException;
import com.lms.shared.tenant.TenantContext;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Granting a one-time pass through a customer's hard credit limit.
 *
 * <p>Needs {@code CREDIT_OVERRIDE}, and an approval limit at least the
 * exposure the pass lets through (policy {@code credit-override-approval-limit}).
 * The grant is audited with its reason; using it is audited again and
 * published as {@code CreditLimitOverridden}.
 */
@Service
public class CreditOverrideService {

    private final BusinessPartnerRepository partners;
    private final CreditOverrideRepository overrides;
    private final AuditTrail audit;

    public CreditOverrideService(BusinessPartnerRepository partners,
                                 CreditOverrideRepository overrides, AuditTrail audit) {
        this.partners = partners;
        this.overrides = overrides;
        this.audit = audit;
    }

    /**
     * @param orderId for an ORDER override, the order it covers
     * @param period  for a CLIENT_INVOICE override, any day of the month billed
     * @param upTo    the highest exposure the override lets through
     */
    @Transactional
    @PreAuthorize("hasAuthority('CREDIT_OVERRIDE')"
            + " and @abac.permits('CREDIT_OVERRIDE', 'BUSINESS_PARTNER', #partnerId, {amount: #upTo})")
    public UUID grant(UUID partnerId, CreditOverride.SubjectType subjectType, UUID orderId,
                      LocalDate period, BigDecimal upTo, String reason) {
        UUID tenantId = TenantContext.requireTenantId();
        BusinessPartner partner = partners.findById(partnerId)
                .orElseThrow(() -> new ResourceNotFoundException("BusinessPartner", partnerId));
        if (partner.creditLimit() == null) {
            throw new BusinessRuleViolationException("credit-limit-not-set",
                    "Partner " + partner.code() + " has no credit limit to override");
        }
        String subjectKey = switch (subjectType) {
            case ORDER -> {
                if (orderId == null) {
                    throw new BusinessRuleViolationException("credit-override-subject-missing",
                            "An order override must name the order it covers");
                }
                yield CreditCheck.subjectKeyOfOrder(orderId);
            }
            case CLIENT_INVOICE -> {
                if (period == null) {
                    throw new BusinessRuleViolationException("credit-override-subject-missing",
                            "A billing override must name the month it covers");
                }
                yield CreditCheck.subjectKeyOfInvoice(period);
            }
        };
        CreditOverride granted = overrides.save(CreditOverride.grant(UUID.randomUUID(), tenantId,
                partnerId, subjectType, subjectKey, upTo, reason,
                CreditLimitChangeService.currentUser(), Instant.now()));
        audit.recordAtomically("CREDIT_OVERRIDE_GRANTED", CreditGateService.AUDIT_TYPE, partnerId,
                null, Json.object("override", granted.id().toString(), "subject", subjectKey,
                        "upTo", granted.upTo().toPlainString(),
                        "creditLimit", partner.creditLimit().toPlainString(),
                        "reason", granted.reason(), "grantedBy", granted.grantedBy(),
                        "expiresAt", granted.expiresAt().toString()));
        return granted.id();
    }
}
