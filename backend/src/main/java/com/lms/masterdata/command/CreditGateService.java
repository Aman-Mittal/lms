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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.lms.masterdata.api.CustomerCreditPort;
import com.lms.masterdata.command.domain.BusinessPartner;
import com.lms.masterdata.command.domain.CreditOverride;
import com.lms.masterdata.events.CreditLimitOverridden;
import com.lms.masterdata.events.CreditSoftLimitExceeded;
import com.lms.masterdata.query.CreditExposureService;
import com.lms.masterdata.query.CreditPositionView;
import com.lms.shared.audit.AuditTrail;
import com.lms.shared.config.Json;
import com.lms.shared.error.BusinessRuleViolationException;
import com.lms.shared.tenant.TenantContext;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Serves {@link CustomerCreditPort}: the standing and credit gate.
 *
 * <p>Runs in the caller's transaction, after the caller's change has been
 * written, so the exposure it reads already includes that change and a
 * refusal rolls the change back. Checks for one customer are serialised by a
 * transaction-scoped advisory lock, so two orders validated at once cannot
 * each see the headroom the other is about to use.
 */
@Service
public class CreditGateService implements CustomerCreditPort {

    static final String AUDIT_TYPE = "BusinessPartner";

    private final BusinessPartnerRepository partners;
    private final CreditOverrideRepository overrides;
    private final CreditExposureService exposure;
    private final JdbcClient jdbc;
    private final AuditTrail audit;
    private final ApplicationEventPublisher events;

    public CreditGateService(BusinessPartnerRepository partners, CreditOverrideRepository overrides,
                             CreditExposureService exposure, JdbcClient jdbc, AuditTrail audit,
                             ApplicationEventPublisher events) {
        this.partners = partners;
        this.overrides = overrides;
        this.exposure = exposure;
        this.jdbc = jdbc;
        this.audit = audit;
        this.events = events;
    }

    @Override
    @Transactional
    public void requireTrading(UUID customerPartnerId) {
        if (customerPartnerId == null) {
            return;
        }
        Optional<BusinessPartner> partner = partners.findById(customerPartnerId);
        if (partner.isPresent() && !partner.get().isTradingCustomer()) {
            throw new BusinessRuleViolationException("customer-not-trading",
                    "Customer " + partner.get().code() + " is " + partner.get().status()
                            + " and cannot be given new or changed orders");
        }
    }

    @Override
    @Transactional
    public void requireHeadroom(CreditCheck check) {
        UUID partnerId = check.customerPartnerId();
        if (partnerId == null) {
            return;
        }
        UUID tenantId = TenantContext.requireTenantId();
        jdbc.sql("SELECT 1 FROM (SELECT pg_advisory_xact_lock(hashtextextended(:key, 0))) held")
                .param("key", "credit:" + tenantId + ":" + partnerId)
                .query(Integer.class)
                .single();

        Optional<CreditPositionView> assessed = exposure.assess(partnerId, check.orderId());
        if (assessed.isEmpty() || assessed.get().creditLimit() == null) {
            return;
        }
        CreditPositionView position = assessed.get();
        BigDecimal total = position.exposure().total();
        BigDecimal limit = position.creditLimit();
        Instant now = Instant.now();

        if (total.compareTo(limit) > 0) {
            String subjectKey = check.subjectKey();
            Optional<CreditOverride> pass = overrides.findUnused(tenantId, partnerId, subjectKey)
                    .stream()
                    .filter(candidate -> candidate.covers(total, now))
                    .findFirst();
            if (pass.isEmpty()) {
                Map<String, String> figures = new LinkedHashMap<>();
                figures.put("exposure", total.toPlainString());
                figures.put("creditLimit", limit.toPlainString());
                figures.put("partnerId", partnerId.toString());
                figures.put("subjectType", check.event() == CreditEvent.CLIENT_INVOICE
                        ? CreditOverride.SubjectType.CLIENT_INVOICE.name()
                        : CreditOverride.SubjectType.ORDER.name());
                throw new BusinessRuleViolationException("credit-limit-exceeded",
                        "Customer " + position.partnerCode() + " would have an exposure of "
                                + total.toPlainString() + ", above its credit limit of "
                                + limit.toPlainString(), figures);
            }
            CreditOverride used = overrides.save(pass.get().use(total, now));
            audit.recordAtomically("CREDIT_LIMIT_OVERRIDDEN", AUDIT_TYPE, partnerId, null,
                    Json.object("override", used.id().toString(), "event", check.event().name(),
                            "subject", subjectKey, "exposure", total.toPlainString(),
                            "creditLimit", limit.toPlainString(), "grantedBy", used.grantedBy(),
                            "reason", used.reason()));
            events.publishEvent(new CreditLimitOverridden(tenantId, partnerId, used.id(),
                    check.event().name(), subjectKey, total, limit, used.grantedBy(),
                    used.reason(), now));
            return;
        }

        BigDecimal soft = position.softLimit();
        if (soft != null && total.compareTo(soft) > 0) {
            audit.recordAtomically("CREDIT_SOFT_LIMIT_EXCEEDED", AUDIT_TYPE, partnerId, null,
                    Json.object("event", check.event().name(), "subject", check.subjectKey(),
                            "exposure", total.toPlainString(), "softLimit", soft.toPlainString(),
                            "creditLimit", limit.toPlainString()));
            events.publishEvent(new CreditSoftLimitExceeded(tenantId, partnerId,
                    check.event().name(), check.subjectKey(), total, soft, limit, now));
        }
    }
}
