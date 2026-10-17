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
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.lms.clientbilling.command.domain.ChargePricing;
import com.lms.clientbilling.command.domain.ClientCharge;
import com.lms.clientbilling.command.domain.ClientContract;
import com.lms.clientbilling.command.domain.ClientInvoice;
import com.lms.clientbilling.command.domain.ClientInvoiceLine;
import com.lms.clientbilling.command.domain.ContractServiceRate;
import com.lms.clientbilling.events.ClientInvoiceIssued;
import com.lms.clientbilling.query.ClientAccess;
import com.lms.shared.audit.AuditTrail;
import com.lms.shared.config.Json;
import com.lms.shared.error.BusinessRuleViolationException;
import com.lms.shared.tenant.TenantContext;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The monthly billing run: one client, one calendar month, one invoice.
 *
 * <p>Idempotent per client and month. A second run for a month already billed
 * returns the invoice the first run issued; it neither re-prices nor adds a
 * line. The unique constraint on {@code (tenant, client, period_start)} is the
 * guarantee, the advisory lock is what makes a concurrent second run wait and
 * then find the first one's invoice instead of failing on the constraint.
 *
 * <p>Issuing the invoice closes the month. Charges that turn up afterwards are
 * filed under the next open month by {@link ClientPeriods}, so an invoice that
 * has been sent never changes underneath the client.
 */
@Service
public class ClientBillingRunService {

    private static final DateTimeFormatter PERIOD = DateTimeFormatter.ofPattern("yyyyMM");

    private final ClientChargeRepository charges;
    private final ClientContractRepository contracts;
    private final ContractServiceRateRepository serviceRates;
    private final ClientInvoiceRepository invoices;
    private final ClientInvoiceLineRepository lines;
    private final ClientPeriods periods;
    private final ClientAccess access;
    private final JdbcClient jdbc;
    private final AuditTrail audit;
    private final ApplicationEventPublisher events;

    public ClientBillingRunService(ClientChargeRepository charges,
                                   ClientContractRepository contracts,
                                   ContractServiceRateRepository serviceRates,
                                   ClientInvoiceRepository invoices,
                                   ClientInvoiceLineRepository lines, ClientPeriods periods,
                                   ClientAccess access, JdbcClient jdbc, AuditTrail audit,
                                   ApplicationEventPublisher events) {
        this.charges = charges;
        this.contracts = contracts;
        this.serviceRates = serviceRates;
        this.invoices = invoices;
        this.lines = lines;
        this.periods = periods;
        this.access = access;
        this.jdbc = jdbc;
        this.audit = audit;
        this.events = events;
    }

    /**
     * Bills a client's month and closes it.
     *
     * @param anyDayInPeriod any day of the month to bill; normalised to the first
     * @return the invoice, whether issued now or by an earlier run
     */
    @Transactional
    @PreAuthorize("hasAuthority('CLIENT_BILLING_RUN')")
    public UUID run(UUID clientPartnerId, LocalDate anyDayInPeriod) {
        UUID tenantId = TenantContext.requireTenantId();
        access.requireAccessTo(clientPartnerId);

        LocalDate periodStart = ClientCharge.periodOf(anyDayInPeriod);
        LocalDate periodEnd = ClientInvoice.periodEndOf(periodStart);
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        if (!periodEnd.isBefore(today)) {
            // Closing a month that is still running would push the rest of its
            // charges into next month's invoice, which is not what the client
            // agreed to be billed for.
            throw new BusinessRuleViolationException("client-period-not-ended",
                    "The period " + periodStart + " to " + periodEnd
                            + " has not ended yet and cannot be billed");
        }

        periods.lock(tenantId, clientPartnerId);

        Optional<ClientInvoice> already = invoices.findForPeriod(tenantId, clientPartnerId,
                periodStart);
        if (already.isPresent()) {
            return already.get().id();
        }

        List<ClientCharge> unbilled = charges.findUnbilled(tenantId, clientPartnerId, periodStart);
        if (unbilled.isEmpty()) {
            throw new BusinessRuleViolationException("client-period-empty",
                    "Nothing was charged to this client for " + periodStart + " to " + periodEnd
                            + "; no invoice was issued and the period stays open");
        }

        List<Priced> priced = priceAll(tenantId, clientPartnerId, unbilled);
        BigDecimal total = priced.stream()
                .map(p -> p.line().amount())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        String currency = priced.get(0).contract().currency();

        Instant now = Instant.now();
        ClientInvoice invoice = invoices.save(ClientInvoice.issue(UUID.randomUUID(), tenantId,
                clientPartnerId, newInvoiceNo(periodStart), periodStart, currency, total,
                priced.size(), now));

        int lineNo = 1;
        for (Priced p : priced) {
            lines.save(ClientInvoiceLine.of(UUID.randomUUID(), tenantId, invoice.id(), lineNo++,
                    p.charge().id(), p.contract().id(), p.line(), p.charge()));
        }

        // Exactly the charges just priced: the lock keeps any new ones out of
        // this month until the transaction commits, and after that they see
        // the invoice and go to the next month.
        jdbc.sql("""
                        UPDATE client_charge
                           SET invoice_id = :invoiceId, version = version + 1
                         WHERE tenant_id = :tenantId
                           AND client_partner_id = :clientPartnerId
                           AND billing_period = :period
                           AND invoice_id IS NULL
                        """)
                .param("invoiceId", invoice.id())
                .param("tenantId", tenantId)
                .param("clientPartnerId", clientPartnerId)
                .param("period", periodStart)
                .update();

        audit.record("CLIENT_INVOICE_ISSUED", "CLIENT_INVOICE", invoice.id(), null,
                Json.object("client", clientPartnerId.toString(),
                        "period", periodStart.toString(), "total", total.toPlainString(),
                        "lines", String.valueOf(priced.size())));
        events.publishEvent(new ClientInvoiceIssued(tenantId, invoice.id(), invoice.invoiceNo(),
                clientPartnerId, periodStart, periodEnd, currency, total, priced.size(), now));

        return invoice.id();
    }

    private record Priced(ClientCharge charge, ClientContract contract,
                          ChargePricing.Priced line) {
    }

    /**
     * Prices every charge against the contract in force on the day it
     * occurred. One missing contract refuses the whole run: an invoice with a
     * hole in it is worse than an invoice a day late.
     */
    private List<Priced> priceAll(UUID tenantId, UUID clientPartnerId,
                                  List<ClientCharge> unbilled) {
        Map<LocalDate, ClientContract> contractOn = new HashMap<>();
        Map<UUID, Map<String, ContractServiceRate>> ratesOf = new HashMap<>();
        List<Priced> priced = new ArrayList<>(unbilled.size());

        for (ClientCharge charge : unbilled) {
            ClientContract contract = contractOn.computeIfAbsent(charge.occurredOn(), day ->
                    contracts.findInForceOn(tenantId, clientPartnerId, day).orElseThrow(() ->
                            new BusinessRuleViolationException("client-contract-missing",
                                    "No client contract was in force on " + day + " to price "
                                            + charge.serviceType() + " charge "
                                            + charge.sourceKey())));
            Map<String, ContractServiceRate> rates = ratesOf.computeIfAbsent(contract.id(), id ->
                    serviceRates.findForContract(tenantId, id).stream()
                            .collect(Collectors.toMap(ContractServiceRate::serviceCode,
                                    Function.identity())));
            priced.add(new Priced(charge, contract, ChargePricing.price(charge, contract, rates)));
        }
        return priced;
    }

    /**
     * Readable and collision-resistant without a per-tenant counter, which
     * would need a lock across every client's run.
     */
    private static String newInvoiceNo(LocalDate periodStart) {
        return "CI-" + PERIOD.format(periodStart) + "-"
                + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
    }
}
