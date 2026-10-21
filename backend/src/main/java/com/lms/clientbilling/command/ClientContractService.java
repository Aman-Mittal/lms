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
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.lms.clientbilling.command.domain.ClientContract;
import com.lms.clientbilling.command.domain.ContractServiceRate;
import com.lms.clientbilling.query.ClientDirectory;
import com.lms.shared.audit.AuditTrail;
import com.lms.shared.config.Json;
import com.lms.shared.error.BusinessRuleViolationException;
import com.lms.shared.tenant.TenantContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Publishing client contracts, and binding client portal users.
 */
@Service
public class ClientContractService {

    /** One value-added service and its price, as published. */
    public record ServiceRateTerm(String serviceCode, String unit, BigDecimal rate) {
    }

    private final ClientContractRepository contracts;
    private final ContractServiceRateRepository serviceRates;
    private final ClientDirectory directory;
    private final JdbcClient jdbc;
    private final AuditTrail audit;

    public ClientContractService(ClientContractRepository contracts,
                                 ContractServiceRateRepository serviceRates,
                                 ClientDirectory directory, JdbcClient jdbc, AuditTrail audit) {
        this.contracts = contracts;
        this.serviceRates = serviceRates;
        this.directory = directory;
        this.jdbc = jdbc;
        this.audit = audit;
    }

    /**
     * Publishes a version of a client's contract, closing the incumbent the day
     * before.
     *
     * <p>The value-added service rates are published with the version, in the
     * same transaction, and cannot be added later: a rate list that could grow
     * after charges had been priced against it would be an edit by another
     * name.
     */
    @Transactional
    @PreAuthorize("hasAuthority('CLIENT_CONTRACT_MANAGE')"
            + " and @abac.permitsIn('CLIENT_CONTRACT_MANAGE', #orgUnitId)")
    public UUID publishContract(UUID orgUnitId, String code, UUID clientPartnerId,
                                String currency, LocalDate effectiveFrom,
                                ClientContract.Rates rates, List<ServiceRateTerm> valueAdded) {
        UUID tenantId = TenantContext.requireTenantId();
        requireCustomer(clientPartnerId);

        Set<String> seen = new HashSet<>();
        for (ServiceRateTerm term : valueAdded == null ? List.<ServiceRateTerm>of() : valueAdded) {
            if (!seen.add(ContractServiceRate.normalise(term.serviceCode()))) {
                throw new BusinessRuleViolationException("client-service-rate-duplicated",
                        "Value-added service " + term.serviceCode()
                                + " is priced twice in one contract");
            }
        }

        contracts.findOpenVersion(tenantId, clientPartnerId).ifPresent(incumbent -> {
            if (!effectiveFrom.isAfter(incumbent.effectiveFrom())) {
                throw new BusinessRuleViolationException("client-contract-backdated",
                        "Client contract " + incumbent.code() + " has been in force since "
                                + incumbent.effectiveFrom() + "; a successor cannot start on "
                                + effectiveFrom);
            }
            contracts.save(incumbent.closeOn(effectiveFrom.minusDays(1)));
        });

        ClientContract published = contracts.save(ClientContract.publish(UUID.randomUUID(),
                tenantId, orgUnitId, code, clientPartnerId, currency, effectiveFrom, rates));

        for (ServiceRateTerm term : valueAdded == null ? List.<ServiceRateTerm>of() : valueAdded) {
            serviceRates.save(ContractServiceRate.of(UUID.randomUUID(), tenantId,
                    published.id(), term.serviceCode(), term.unit(), term.rate()));
        }

        audit.record("CLIENT_CONTRACT_PUBLISHED", "CLIENT_CONTRACT", published.id(), null,
                Json.object("code", code, "client", clientPartnerId.toString(),
                        "effectiveFrom", effectiveFrom.toString()));
        return published.id();
    }

    /**
     * Confines a user to one client's records.
     *
     * <p>Rebinding a user to a different client replaces the binding rather
     * than adding one: a portal user belongs to exactly one client.
     */
    @Transactional
    @PreAuthorize("hasAuthority('CLIENT_CONTRACT_MANAGE')")
    public void grantPortalAccess(UUID clientPartnerId, UUID userId) {
        UUID tenantId = TenantContext.requireTenantId();
        requireCustomer(clientPartnerId);

        jdbc.sql("""
                        INSERT INTO client_portal_user (tenant_id, user_id, client_partner_id,
                                                        granted_at)
                        VALUES (:tenantId, :userId, :clientPartnerId, :grantedAt)
                        ON CONFLICT (tenant_id, user_id)
                        DO UPDATE SET client_partner_id = EXCLUDED.client_partner_id,
                                      granted_at = EXCLUDED.granted_at
                        """)
                .param("tenantId", tenantId)
                .param("userId", userId)
                .param("clientPartnerId", clientPartnerId)
                .param("grantedAt", Instant.now().atOffset(ZoneOffset.UTC))
                .update();

        audit.record("CLIENT_PORTAL_ACCESS_GRANTED", "APP_USER", userId, null,
                Json.object("client", clientPartnerId.toString()));
    }

    private void requireCustomer(UUID partnerId) {
        if (!directory.isCustomer(partnerId)) {
            throw new BusinessRuleViolationException("client-not-a-customer",
                    "Partner " + partnerId + " is not a customer of this tenant, so it cannot "
                            + "be a 3PL client");
        }
    }
}
