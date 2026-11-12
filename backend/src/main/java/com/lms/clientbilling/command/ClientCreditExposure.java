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
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.lms.clientbilling.command.domain.ChargePricing;
import com.lms.clientbilling.command.domain.ClientCharge;
import com.lms.clientbilling.command.domain.ClientContract;
import com.lms.clientbilling.command.domain.ContractServiceRate;
import com.lms.clientbilling.command.domain.ServiceType;
import com.lms.clientbilling.command.domain.Tax;
import com.lms.masterdata.api.ReceivablesSource;
import com.lms.shared.error.BusinessRuleViolationException;
import com.lms.shared.tenant.TenantContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Serves {@link ReceivablesSource}: what a client owes, for its credit
 * exposure.
 *
 * <p>Uninvoiced charges are priced exactly as the billing run would price
 * them ({@link ChargePricing}, the contract in force on the day of the work,
 * tax per line), so the exposure does not jump when a month is invoiced. A
 * charge no contract can price counts as zero and is reported as unpriced:
 * the billing run would refuse it, and it is not this read's place to guess.
 */
@Component
class ClientCreditExposure implements ReceivablesSource {

    private final JdbcClient jdbc;
    private final ClientContractRepository contracts;
    private final ContractServiceRateRepository serviceRates;
    private final ContractLaneStore laneStore;

    ClientCreditExposure(JdbcClient jdbc, ClientContractRepository contracts,
                         ContractServiceRateRepository serviceRates, ContractLaneStore laneStore) {
        this.laneStore = laneStore;
        this.jdbc = jdbc;
        this.contracts = contracts;
        this.serviceRates = serviceRates;
    }

    @Override
    public Receivables receivablesOf(UUID clientPartnerId) {
        UUID tenantId = TenantContext.requireTenantId();
        List<ClientCharge> unbilled = jdbc.sql("""
                        SELECT * FROM client_charge
                         WHERE tenant_id = :tenantId AND client_partner_id = :client
                           AND invoice_id IS NULL
                        """)
                .param("tenantId", tenantId)
                .param("client", clientPartnerId)
                .query(ClientCharge.class)
                .list();

        Map<LocalDate, Optional<ClientContract>> contractOn = new HashMap<>();
        Map<UUID, Map<String, ContractServiceRate>> ratesOf = new HashMap<>();
        Map<UUID, com.lms.clientbilling.command.domain.ContractLane> laneOf = new HashMap<>();
        BigDecimal charged = BigDecimal.ZERO;
        int unpriced = 0;
        for (ClientCharge charge : unbilled) {
            Optional<ClientContract> contract = contractOn.computeIfAbsent(charge.occurredOn(),
                    day -> contracts.findInForceOn(tenantId, clientPartnerId, day));
            if (contract.isEmpty()) {
                unpriced++;
                continue;
            }
            Map<String, ContractServiceRate> rates = ratesOf.computeIfAbsent(contract.get().id(),
                    id -> serviceRates.findForContract(tenantId, id).stream()
                            .collect(Collectors.toMap(ContractServiceRate::serviceCode,
                                    Function.identity())));
            try {
                com.lms.clientbilling.command.domain.ContractLane lane = charge.contractLaneId() == null ? null
                        : laneOf.computeIfAbsent(charge.contractLaneId(), id -> laneStore.find(id).orElse(null));
                BigDecimal amount = ChargePricing.price(charge, contract.get(), rates, lane).amount();
                charged = charged.add(amount)
                        .add(Tax.on(amount, contract.get().taxRateFor(charge.serviceType())));
            } catch (BusinessRuleViolationException unpricedService) {
                unpriced++;
            }
        }

        record Owed(BigDecimal invoiced, BigDecimal credited, BigDecimal paid) {
        }
        Owed owed = jdbc.sql("""
                        SELECT (SELECT coalesce(sum(total_amount), 0) FROM client_invoice
                                 WHERE tenant_id = :tenantId AND client_partner_id = :client)
                                   AS invoiced,
                               (SELECT coalesce(sum(cn.total_amount), 0)
                                  FROM client_credit_note cn
                                  JOIN client_invoice i ON i.id = cn.invoice_id
                                 WHERE i.tenant_id = :tenantId AND i.client_partner_id = :client)
                                   AS credited,
                               (SELECT coalesce(sum(amount), 0) FROM client_payment
                                 WHERE tenant_id = :tenantId AND client_partner_id = :client)
                                   AS paid
                        """)
                .param("tenantId", tenantId)
                .param("client", clientPartnerId)
                .query(Owed.class)
                .single();
        BigDecimal outstanding = owed.invoiced().subtract(owed.credited()).subtract(owed.paid())
                .max(BigDecimal.ZERO);

        String currency = contracts.findInForceOn(tenantId, clientPartnerId,
                        LocalDate.now(ZoneOffset.UTC))
                .map(ClientContract::currency)
                .orElseGet(() -> jdbc.sql("""
                                SELECT currency FROM client_invoice
                                 WHERE tenant_id = :tenantId AND client_partner_id = :client
                                 ORDER BY issued_at DESC, id DESC LIMIT 1
                                """)
                        .param("tenantId", tenantId)
                        .param("client", clientPartnerId)
                        .query(String.class)
                        .optional()
                        .orElse(null));
        return new Receivables(currency, Tax.money(charged), unbilled.size(), unpriced,
                Tax.money(outstanding));
    }

    /**
     * A transport charge is posted per completed trip and client, for the
     * chargeable weight of that client's (non-LTL) consignments on the trip's
     * load ({@link ClientChargeService#captureTransport}). So an order's
     * charged weight is the weight of its consignments on loads whose trip
     * carries this client's charge -- found through the charge's source key,
     * exactly as the capture wrote it.
     */
    @Override
    public Map<UUID, BigDecimal> chargedWeightByOrder(UUID clientPartnerId,
                                                      Collection<UUID> orderIds) {
        if (orderIds.isEmpty()) {
            return Map.of();
        }
        record Charged(UUID orderId, BigDecimal chargedKg) {
        }
        return jdbc.sql("""
                        SELECT c.order_id, sum(c.chargeable_weight_kg) AS charged_kg
                          FROM consignment c
                          JOIN load_consignment lc ON lc.consignment_id = c.id
                          JOIN trip t ON t.tenant_id = c.tenant_id AND t.load_id = lc.load_id
                                     AND t.status <> 'CANCELLED'
                          JOIN client_charge ch ON ch.tenant_id = c.tenant_id
                                     AND ch.source_key = 'TRIP:' || t.id || ':' || CAST(:client AS text)
                         WHERE c.tenant_id = :tenantId
                           AND c.order_id IN (:orderIds)
                           AND c.freight_mode <> 'LTL'
                         GROUP BY c.order_id
                        """)
                .param("tenantId", TenantContext.requireTenantId())
                .param("client", clientPartnerId.toString())
                .param("orderIds", orderIds)
                .query(Charged.class)
                .list()
                .stream()
                .collect(Collectors.toMap(Charged::orderId, Charged::chargedKg));
    }

    @Override
    public Optional<TransportTariff> transportTariff(UUID clientPartnerId, LocalDate day) {
        return contracts.findInForceOn(TenantContext.requireTenantId(), clientPartnerId, day)
                .map(contract -> new TransportTariff(contract.currency(),
                        contract.rateFor(ServiceType.TRANSPORT),
                        contract.transportMinimumCharge(),
                        contract.taxRateFor(ServiceType.TRANSPORT)));
    }
}
