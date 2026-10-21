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
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.lms.clientbilling.command.domain.ClientCharge;
import com.lms.clientbilling.command.domain.ServiceType;
import com.lms.clientbilling.query.ClientAccess;
import com.lms.clientbilling.query.ClientDirectory;
import com.lms.shared.error.BusinessRuleViolationException;
import com.lms.shared.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Capturing what was done for a client, exactly once.
 *
 * <p>Two sources, one rule. Completed trips become transport charges; recorded
 * service usage -- storage, handling, value-added services -- becomes
 * warehousing charges. Each carries a source key that is unique per tenant, so
 * a redelivered event or a retried request finds the charge it already made
 * and returns it instead of making a second one.
 */
@Service
public class ClientChargeService {

    private static final Logger log = LoggerFactory.getLogger(ClientChargeService.class);

    private final ClientChargeRepository charges;
    private final ClientContractRepository contracts;
    private final ClientDirectory directory;
    private final ClientAccess access;
    private final ClientPeriods periods;
    private final JdbcClient jdbc;

    public ClientChargeService(ClientChargeRepository charges, ClientContractRepository contracts,
                               ClientDirectory directory, ClientAccess access,
                               ClientPeriods periods, JdbcClient jdbc) {
        this.charges = charges;
        this.contracts = contracts;
        this.directory = directory;
        this.access = access;
        this.periods = periods;
        this.jdbc = jdbc;
    }

    /**
     * Records warehouse or value-added service usage for a client.
     *
     * <p>The entry point a warehouse system feeds, by hand today and from the
     * warehouse ledger later. {@code sourceKey} is the caller's own identifier
     * for the usage -- a ledger entry, a monthly storage snapshot -- and a
     * second call with it returns the first charge rather than adding another.
     */
    @Transactional
    @PreAuthorize("hasAuthority('CLIENT_BILLING_RUN')"
            + " and @abac.permits('CLIENT_BILLING_RUN', 'BUSINESS_PARTNER', #clientPartnerId)")
    public UUID recordServiceUsage(UUID clientPartnerId, ServiceType serviceType,
                                   String serviceCode, BigDecimal quantity, LocalDate occurredOn,
                                   String reference, String sourceKey) {
        UUID tenantId = TenantContext.requireTenantId();
        access.requireAccessTo(clientPartnerId);

        if (serviceType == null || !serviceType.isWarehouseService()) {
            throw new BusinessRuleViolationException("client-charge-not-recordable",
                    "Transport is charged from completed trips, not recorded by hand");
        }
        if (sourceKey == null || sourceKey.isBlank()) {
            throw new IllegalArgumentException(
                    "A source key is required, or a retry would bill the same work twice");
        }
        requireClient(tenantId, clientPartnerId);

        String key = "USAGE:" + sourceKey.trim();
        Optional<ClientCharge> already = charges.findBySourceKey(tenantId, key);
        if (already.isPresent()) {
            return sameClientOrRefuse(already.get(), clientPartnerId);
        }

        periods.lock(tenantId, clientPartnerId);
        // Read again under the lock: a concurrent retry may have won the race.
        already = charges.findBySourceKey(tenantId, key);
        if (already.isPresent()) {
            return sameClientOrRefuse(already.get(), clientPartnerId);
        }

        if (serviceType == ServiceType.STORAGE && storageFromLedger(clientPartnerId, occurredOn)) {
            throw new BusinessRuleViolationException("client-storage-from-ledger",
                    "Storage on " + occurredOn + " was already computed from the warehouse "
                            + "ledger for this client; recording it by hand as well would bill "
                            + "the same pallets twice");
        }

        LocalDate period = periods.openPeriodFor(tenantId, clientPartnerId, occurredOn);
        String unit = serviceType.unit() == null ? "UNIT" : serviceType.unit();
        return charges.save(ClientCharge.capture(UUID.randomUUID(), tenantId, clientPartnerId,
                serviceType, serviceCode, quantity, unit, occurredOn, period, key, reference,
                null, null)).id();
    }

    /**
     * Turns a completed trip into a transport charge for every contracted
     * client with freight aboard.
     *
     * <p>Unguarded by {@code @PreAuthorize}: the actor is the platform reacting
     * to a finished movement, as with the carrier's freight bill. Customers
     * without a client contract are not 3PL clients, and their freight is
     * left alone.
     *
     * <p>The movement is dated by its dispatch, the same day the carrier's rate
     * is chosen by, so the receivable and the payable for one trip are priced
     * on the same day's terms.
     *
     * @return how many charges this call created; zero on a redelivery
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int captureTransport(UUID tripId, String tripNo, UUID loadId, Instant dispatchedAt,
                                Instant completedAt) {
        UUID tenantId = TenantContext.requireTenantId();
        List<ClientDirectory.ClientShare> shares = directory.sharesOf(loadId);
        if (shares.isEmpty()) {
            return 0;
        }

        BigDecimal totalKg = shares.stream()
                .map(ClientDirectory.ClientShare::chargeableWeightKg)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        Instant movedAt = dispatchedAt != null ? dispatchedAt
                : completedAt != null ? completedAt : Instant.now();
        LocalDate occurredOn = movedAt.atZone(ZoneOffset.UTC).toLocalDate();

        int created = 0;
        for (ClientDirectory.ClientShare share : shares) {
            UUID client = share.clientPartnerId();
            if (contracts.countForClient(tenantId, client) == 0) {
                continue;
            }
            String key = "TRIP:" + tripId + ":" + client;
            if (charges.findBySourceKey(tenantId, key).isPresent()) {
                continue;
            }
            periods.lock(tenantId, client);
            if (charges.findBySourceKey(tenantId, key).isPresent()) {
                continue;
            }

            BigDecimal costShare = totalKg.signum() > 0
                    ? share.chargeableWeightKg().divide(totalKg, 6, RoundingMode.HALF_UP)
                    : BigDecimal.ONE.divide(BigDecimal.valueOf(shares.size()), 6,
                            RoundingMode.HALF_UP);
            BigDecimal quantity = share.chargeableWeightKg().signum() > 0
                    ? share.chargeableWeightKg() : BigDecimal.ONE;

            charges.save(ClientCharge.capture(UUID.randomUUID(), tenantId, client,
                    ServiceType.TRANSPORT, null, quantity, ServiceType.TRANSPORT.unit(),
                    occurredOn, periods.openPeriodFor(tenantId, client, occurredOn), key,
                    tripNo + " / " + share.orderNos(), tripId, costShare));
            created++;
        }
        if (created > 0) {
            log.debug("Captured {} client transport charge(s) for trip {}", created, tripNo);
        }
        return created;
    }

    private void requireClient(UUID tenantId, UUID clientPartnerId) {
        if (contracts.countForClient(tenantId, clientPartnerId) == 0) {
            throw new BusinessRuleViolationException("client-without-contract",
                    "Partner " + clientPartnerId + " holds no client contract, so there is "
                            + "nothing to charge it under");
        }
    }

    private boolean storageFromLedger(UUID clientPartnerId, LocalDate day) {
        return jdbc.sql("""
                        SELECT EXISTS (SELECT 1 FROM client_storage_day
                                        WHERE client_partner_id = :client AND held_on = :day)
                        """)
                .param("client", clientPartnerId)
                .param("day", day)
                .query(Boolean.class)
                .single();
    }

    private static UUID sameClientOrRefuse(ClientCharge existing, UUID clientPartnerId) {
        if (!existing.clientPartnerId().equals(clientPartnerId)) {
            throw new BusinessRuleViolationException("client-charge-key-reused",
                    "Source key " + existing.sourceKey() + " already recorded a charge for "
                            + "another client");
        }
        return existing.id();
    }
}
