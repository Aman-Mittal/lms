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
package com.lms.clientbilling.command;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Locale;
import java.util.UUID;

import com.lms.clientbilling.command.domain.ClientPayment;
import com.lms.clientbilling.events.ClientPaymentReceived;
import com.lms.shared.audit.AuditTrail;
import com.lms.shared.config.Json;
import com.lms.shared.error.BusinessRuleViolationException;
import com.lms.shared.tenant.TenantContext;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Recording money received from a client.
 *
 * <p>A receipt is held to the same authority as running the bill, in the
 * caller's own organisational subtree, and is recorded in the currency the
 * client is invoiced in: a client with no invoice has nothing to pay, and an
 * unrecognised receipt is better refused than guessed at. The remittance
 * reference is unique per client, so a receipt entered twice is refused
 * rather than credited twice.
 */
@Service
public class ClientPaymentService {

    private final ClientPaymentRepository payments;
    private final JdbcClient jdbc;
    private final AuditTrail audit;
    private final ApplicationEventPublisher events;

    public ClientPaymentService(ClientPaymentRepository payments, JdbcClient jdbc,
                                AuditTrail audit, ApplicationEventPublisher events) {
        this.payments = payments;
        this.jdbc = jdbc;
        this.audit = audit;
        this.events = events;
    }

    /** @return the payment */
    @Transactional
    @PreAuthorize("hasAuthority('CLIENT_BILLING_RUN')"
            + " and @abac.permits('CLIENT_BILLING_RUN', 'BUSINESS_PARTNER', #clientPartnerId)")
    public UUID record(UUID clientPartnerId, BigDecimal amount, LocalDate receivedOn,
                       String reference) {
        UUID tenantId = TenantContext.requireTenantId();
        String currency = jdbc.sql("""
                        SELECT currency FROM client_invoice
                         WHERE client_partner_id = :client
                         ORDER BY issued_at DESC, id DESC
                         LIMIT 1
                        """)
                .param("client", clientPartnerId)
                .query(String.class)
                .optional()
                .orElseThrow(() -> new BusinessRuleViolationException("client-has-no-invoice",
                        "The client has not been invoiced, so there is nothing a payment could"
                                + " settle"));
        if (reference != null && payments.existsByClientPartnerIdAndReference(clientPartnerId,
                reference.strip())) {
            throw new BusinessRuleViolationException("client-payment-duplicate",
                    "Remittance " + reference.strip() + " was already recorded for this client");
        }
        Instant now = Instant.now();
        ClientPayment payment = payments.save(ClientPayment.record(UUID.randomUUID(), tenantId,
                clientPartnerId, newPaymentNo(), currency, amount, receivedOn, reference,
                currentActor(), now));
        audit.recordAtomically("CLIENT_PAYMENT_RECORDED", "CLIENT_PAYMENT", payment.id(), null,
                Json.object("client", clientPartnerId.toString(),
                        "amount", payment.amount().toPlainString(),
                        "currency", currency,
                        "receivedOn", receivedOn.toString(),
                        "reference", payment.reference()));
        events.publishEvent(new ClientPaymentReceived(tenantId, payment.id(), payment.paymentNo(),
                clientPartnerId, currency, payment.amount(), receivedOn, payment.reference(),
                now));
        return payment.id();
    }

    private static String newPaymentNo() {
        return "RCPT-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
    }

    private static String currentActor() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication == null || authentication.getName() == null
                ? "system"
                : authentication.getName();
    }
}
