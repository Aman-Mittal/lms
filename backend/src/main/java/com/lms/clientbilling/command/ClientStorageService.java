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
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.lms.clientbilling.command.domain.ClientCharge;
import com.lms.clientbilling.command.domain.ClientContract;
import com.lms.clientbilling.command.domain.ClientInvoice;
import com.lms.clientbilling.command.domain.ServiceType;
import com.lms.clientbilling.command.domain.StoragePallets;
import com.lms.clientbilling.query.ClientAccess;
import com.lms.shared.audit.AuditTrail;
import com.lms.shared.config.Json;
import com.lms.shared.error.BusinessRuleViolationException;
import com.lms.shared.tenant.TenantContext;
import com.lms.warehouse.api.OwnerStockPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Storage charged from the warehouse ledger instead of a figure somebody types.
 *
 * <p>The unit, stated once: a client's storage on a day is the units of its
 * own SKUs (those whose owner is the client) held in any of the tenant's
 * warehouses at the end of that day, UTC, converted to pallet positions with
 * the contract version's units-per-pallet and rounded <em>up</em> -- a part
 * pallet still occupies a position. Stock in quarantine counts as held: it
 * is physically in the warehouse and occupies a position until it is released
 * or written off, and a client holding goods under inspection is still being
 * stored. A month's storage is the sum of its days,
 * in pallet-days, priced at the contract's storage rate like any other storage
 * charge. Mixing SKUs on a pallet is assumed; per-SKU pallet sizes are not
 * modelled.
 *
 * <p>Computed once per client and month, for a month that has ended. Every day
 * of the month is recorded in {@code client_storage_day}, which is the evidence
 * behind the charge and what makes a second computation a no-op. One charge is
 * made per contract version in force during the month -- normally one -- so a
 * mid-month price change prices each day on its own version.
 *
 * <p>Hand-recorded storage and ledger storage do not mix within a month for
 * one client: either would bill the same pallets twice.
 */
@Service
public class ClientStorageService {

    /** Source-key prefix of a ledger-derived storage charge. */
    public static final String LEDGER_KEY = "LEDGER-STORAGE:";

    private final OwnerStockPort stock;
    private final ClientContractRepository contracts;
    private final ClientChargeRepository charges;
    private final ClientPeriods periods;
    private final ClientAccess access;
    private final JdbcClient jdbc;
    private final AuditTrail audit;

    public ClientStorageService(OwnerStockPort stock, ClientContractRepository contracts,
                                ClientChargeRepository charges, ClientPeriods periods,
                                ClientAccess access, JdbcClient jdbc, AuditTrail audit) {
        this.stock = stock;
        this.contracts = contracts;
        this.charges = charges;
        this.periods = periods;
        this.access = access;
        this.jdbc = jdbc;
        this.audit = audit;
    }

    /**
     * Computes a client's storage for one ended month from the ledger and
     * charges it. A second call for the same client and month changes nothing.
     *
     * @param anyDayInPeriod any day of the month; normalised to the first
     */
    @Transactional
    @PreAuthorize("hasAuthority('CLIENT_BILLING_RUN')"
            + " and @abac.permits('CLIENT_BILLING_RUN', 'BUSINESS_PARTNER', #clientPartnerId)")
    public void computeMonth(UUID clientPartnerId, LocalDate anyDayInPeriod) {
        UUID tenantId = TenantContext.requireTenantId();
        access.requireAccessTo(clientPartnerId);
        if (contracts.countForClient(tenantId, clientPartnerId) == 0) {
            throw new BusinessRuleViolationException("client-without-contract",
                    "Partner " + clientPartnerId + " holds no client contract, so there is "
                            + "nothing to charge it under");
        }

        LocalDate periodStart = ClientCharge.periodOf(anyDayInPeriod);
        LocalDate periodEnd = ClientInvoice.periodEndOf(periodStart);
        if (!periodEnd.isBefore(LocalDate.now(ZoneOffset.UTC))) {
            // The last day's closing stock is not known until the day is over.
            throw new BusinessRuleViolationException("client-period-not-ended",
                    "Storage for " + periodStart + " to " + periodEnd
                            + " cannot be computed before the month has ended");
        }

        periods.lock(tenantId, clientPartnerId);

        if (alreadyComputed(clientPartnerId, periodStart)) {
            return;
        }
        if (recordedByHand(clientPartnerId, periodStart, periodEnd)) {
            throw new BusinessRuleViolationException("client-storage-recorded-by-hand",
                    "Storage for " + periodStart + " to " + periodEnd + " was already recorded "
                            + "by hand for this client; computing it from the ledger as well "
                            + "would bill the same pallets twice");
        }

        Map<LocalDate, ClientContract> contractOn = new HashMap<>();
        Map<UUID, Segment> segments = new LinkedHashMap<>();
        List<Day> days = new ArrayList<>();
        for (OwnerStockPort.DailyHolding holding
                : stock.unitsHeldEachDay(clientPartnerId, periodStart, periodEnd)) {
            long units = Math.max(0, holding.units());
            if (units == 0) {
                days.add(new Day(holding.day(), 0, null, 0, null));
                continue;
            }
            ClientContract contract = contractOn.computeIfAbsent(holding.day(), day ->
                    contracts.findInForceOn(tenantId, clientPartnerId, day).orElseThrow(() ->
                            new BusinessRuleViolationException("client-contract-missing",
                                    "No client contract was in force on " + day + " to price "
                                            + units + " units of stored goods")));
            long pallets = StoragePallets.of(units, contract.storageUnitsPerPallet());
            days.add(new Day(holding.day(), units, contract.storageUnitsPerPallet(), pallets,
                    contract.id()));
            segments.computeIfAbsent(contract.id(), id -> new Segment(holding.day()))
                    .add(pallets);
        }

        Map<UUID, UUID> chargeOfContract = new HashMap<>();
        long palletDays = 0;
        for (Map.Entry<UUID, Segment> entry : segments.entrySet()) {
            Segment segment = entry.getValue();
            palletDays += segment.palletDays;
            // Dated on the segment's first day held, which the contract that
            // priced the segment covers, so the run prices it on that version.
            ClientCharge charge = charges.save(ClientCharge.capture(UUID.randomUUID(), tenantId,
                    clientPartnerId, ServiceType.STORAGE, null,
                    BigDecimal.valueOf(segment.palletDays), ServiceType.STORAGE.unit(),
                    segment.firstDay,
                    periods.openPeriodFor(tenantId, clientPartnerId, segment.firstDay),
                    LEDGER_KEY + clientPartnerId + ":" + YearMonth.from(periodStart) + ":"
                            + entry.getKey(),
                    "warehouse ledger " + YearMonth.from(periodStart) + ", " + segment.daysHeld
                            + " day(s) held",
                    null, null));
            chargeOfContract.put(entry.getKey(), charge.id());
        }

        Instant now = Instant.now();
        for (Day day : days) {
            jdbc.sql("""
                            INSERT INTO client_storage_day (tenant_id, client_partner_id, held_on,
                                                            period_start, units_held,
                                                            units_per_pallet, pallets, contract_id,
                                                            charge_id, computed_at)
                            VALUES (:tenantId, :client, :heldOn, :period, :units, :perPallet,
                                    :pallets, :contractId, :chargeId, :at)
                            """)
                    .param("tenantId", tenantId)
                    .param("client", clientPartnerId)
                    .param("heldOn", day.heldOn())
                    .param("period", periodStart)
                    .param("units", day.units())
                    .param("perPallet", day.unitsPerPallet())
                    .param("pallets", day.pallets())
                    .param("contractId", day.contractId())
                    .param("chargeId", day.contractId() == null ? null
                            : chargeOfContract.get(day.contractId()))
                    .param("at", now.atOffset(ZoneOffset.UTC))
                    .update();
        }

        audit.recordAtomically("CLIENT_STORAGE_COMPUTED", "CLIENT", clientPartnerId, null,
                Json.object("period", periodStart.toString(),
                        "palletDays", String.valueOf(palletDays),
                        "charges", String.valueOf(chargeOfContract.size())));
    }

    private record Day(LocalDate heldOn, long units, Integer unitsPerPallet, long pallets,
                       UUID contractId) {
    }

    /** The days of one month priced by one contract version. */
    private static final class Segment {
        private final LocalDate firstDay;
        private long palletDays;
        private int daysHeld;

        Segment(LocalDate firstDay) {
            this.firstDay = firstDay;
        }

        void add(long pallets) {
            palletDays += pallets;
            daysHeld++;
        }
    }

    private boolean alreadyComputed(UUID clientPartnerId, LocalDate periodStart) {
        return jdbc.sql("""
                        SELECT EXISTS (SELECT 1 FROM client_storage_day
                                        WHERE client_partner_id = :client
                                          AND period_start = :period)
                        """)
                .param("client", clientPartnerId)
                .param("period", periodStart)
                .query(Boolean.class)
                .single();
    }

    private boolean recordedByHand(UUID clientPartnerId, LocalDate from, LocalDate to) {
        return jdbc.sql("""
                        SELECT EXISTS (SELECT 1 FROM client_charge
                                        WHERE client_partner_id = :client
                                          AND billing_period >= :from
                                          AND service_type = 'STORAGE'
                                          AND occurred_on BETWEEN :from AND :to
                                          AND source_key NOT LIKE 'LEDGER-STORAGE:%')
                        """)
                .param("client", clientPartnerId)
                .param("from", from)
                .param("to", to)
                .query(Boolean.class)
                .single();
    }
}
