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
 */package com.lms.finance.command;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import com.lms.finance.command.domain.CostSheet;
import com.lms.finance.command.domain.FreightBill;
import com.lms.finance.command.domain.FreightBillLine;
import com.lms.finance.command.domain.FuelSurchargeIndex;
import com.lms.finance.command.domain.Tariff;
import com.lms.finance.events.FreightBillRaised;
import com.lms.planning.api.LoadLifecyclePort;
import com.lms.shared.audit.AuditTrail;
import com.lms.shared.config.Json;
import com.lms.shared.error.ResourceNotFoundException;
import com.lms.shared.tenant.TenantContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Raising freight bills and settling them against vendor invoices (3.9).
 */
@Service
public class BillingService {

    private final FreightBillRepository bills;
    private final FreightBillLineRepository lines;
    private final TariffRepository tariffs;
    private final TariffSlabRepository slabs;
    private final FuelSurchargeIndexRepository fuelIndex;
    private final LoadLifecyclePort loads;
    private final RatingEngine rating;
    private final AuditTrail audit;
    private final ApplicationEventPublisher events;

    /**
     * How far a vendor's invoice may differ from the computed cost before it is
     * disputed, as a percentage and as an absolute floor.
     *
     * <p>Both, because either alone misbehaves. A percentage on its own
     * disputes a two-rupee rounding difference on a small bill; an absolute on
     * its own waves through a five-figure gap on a large one. The allowance is
     * the greater of the two.
     */
    private final BigDecimal tolerancePct;
    private final BigDecimal toleranceAbsolute;

    public BillingService(FreightBillRepository bills, FreightBillLineRepository lines,
                          TariffRepository tariffs, TariffSlabRepository slabs,
                          FuelSurchargeIndexRepository fuelIndex, LoadLifecyclePort loads,
                          RatingEngine rating, AuditTrail audit,
                          ApplicationEventPublisher events,
                          @Value("${lms.finance.match-tolerance-pct:2}") BigDecimal tolerancePct,
                          @Value("${lms.finance.match-tolerance-absolute:100}")
                          BigDecimal toleranceAbsolute) {
        this.bills = bills;
        this.lines = lines;
        this.tariffs = tariffs;
        this.slabs = slabs;
        this.fuelIndex = fuelIndex;
        this.loads = loads;
        this.rating = rating;
        this.audit = audit;
        this.events = events;
        this.tolerancePct = tolerancePct;
        this.toleranceAbsolute = toleranceAbsolute;
    }

    /**
     * Prices a finished trip.
     *
     * <p>Unguarded by {@code @PreAuthorize}, and deliberately: the actor is the
     * platform, reacting to a trip that finished. Requiring a permission here
     * would mean the bill for a trip completed by a gate clerk was raised with
     * the gate clerk's authority, and a tenant that forgot to grant it would
     * quietly stop billing.
     *
     * <p>Idempotent on the trip. The unique constraint on
     * {@code (tenant_id, trip_id)} is the real guarantee -- two bills for one
     * movement means paying it twice -- and this check is what turns that
     * constraint from an error into a no-op when an event is redelivered.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<UUID> raiseBillFor(UUID tripId, UUID loadId, UUID vendorPartnerId,
                                       String tripNo, Instant dispatchedAt,
                                       Duration originDwell, BigDecimal payloadWeightKg) {
        UUID tenantId = TenantContext.requireTenantId();

        Optional<FreightBill> already = bills.findByTrip(tenantId, tripId);
        if (already.isPresent()) {
            return already.map(FreightBill::id);
        }

        LoadLifecyclePort.LoadSummary load = loads.summarise(loadId)
                .orElseThrow(() -> new ResourceNotFoundException("Load", loadId));

        // The dispatch date, not today. The whole reason 3.9.1 versions rates:
        // a bill raised in March for a January trip is priced with January's
        // card, and re-running it next year must give the same number.
        LocalDate pricedOn = (dispatchedAt == null ? Instant.now() : dispatchedAt)
                .atZone(ZoneOffset.UTC).toLocalDate();

        String billNo = "FB-" + tripNo;

        Optional<Tariff> card = vendorPartnerId == null ? Optional.empty()
                : tariffs.findInForceOn(tenantId, vendorPartnerId, load.originTerminalId(),
                        load.destinationTerminalId(), load.vehicleType(), pricedOn);

        if (card.isEmpty()) {
            FreightBill unpriced = bills.save(FreightBill.unpriced(UUID.randomUUID(), tenantId,
                    load.orgUnitId(), tripId, loadId, vendorPartnerId, billNo, pricedOn,
                    "No rate card in force for this vendor, lane and vehicle type on "
                            + pricedOn));
            publishRaised(tenantId, unpriced, false);
            return Optional.of(unpriced.id());
        }

        Tariff tariff = card.get();
        CostSheet sheet = rating.rate(tariff, slabs.findForTariff(tenantId, tariff.id()),
                chargeableWeight(load, payloadWeightKg), originDwell, load.dropCount(),
                fuelPctFor(tenantId, pricedOn));

        FreightBill bill = bills.save(FreightBill.priced(UUID.randomUUID(), tenantId,
                load.orgUnitId(), tripId, loadId, vendorPartnerId, billNo, tariff.id(),
                pricedOn, sheet));

        int lineNo = 1;
        for (CostSheet.Charge charge : sheet.charges()) {
            lines.save(FreightBillLine.of(UUID.randomUUID(), tenantId, bill.id(), lineNo++,
                    charge.type(), charge.narrative(), charge.quantity(), charge.unit(),
                    charge.rate(), charge.amount()));
        }

        publishRaised(tenantId, bill, true);
        return Optional.of(bill.id());
    }

    /**
     * Records the vendor's invoice and decides whether it is payable (3.9.2).
     */
    @Transactional
    @PreAuthorize("hasAuthority('INVOICE_APPROVE')")
    public FreightBill.BillStatus matchVendorClaim(UUID billId, BigDecimal claimedAmount) {
        FreightBill bill = require(billId);
        FreightBill matched = bills.save(
                bill.matchAgainst(claimedAmount, tolerancePct, toleranceAbsolute, Instant.now()));

        audit.record(matched.status() == FreightBill.BillStatus.APPROVED_FOR_PAYMENT
                        ? "INVOICE_APPROVED" : "INVOICE_DISPUTED",
                "FREIGHT_BILL", matched.id(),
                Json.object("status", bill.status().name(),
                        "computed", String.valueOf(bill.computedAmount())),
                Json.object("status", matched.status().name(),
                        "claimed", String.valueOf(claimedAmount),
                        "variance", String.valueOf(matched.varianceAmount())));

        return matched.status();
    }

    /** Settles a dispute in the vendor's favour, with the reason on the record. */
    @Transactional
    @PreAuthorize("hasAuthority('INVOICE_APPROVE')")
    public void resolveDispute(UUID billId, String reason) {
        FreightBill bill = require(billId);
        FreightBill resolved = bills.save(bill.resolveDispute(reason, Instant.now()));

        audit.record("INVOICE_DISPUTE_RESOLVED", "FREIGHT_BILL", resolved.id(),
                Json.object("status", bill.status().name()),
                Json.object("status", resolved.status().name(), "reason", reason));
    }

    // ------------------------------------------------------------- internals

    /**
     * What the movement is billed on.
     *
     * <p>The greater of the weighed payload and the load's chargeable weight.
     * The weighbridge is the measurement both parties witnessed, so it cannot
     * be ignored; the chargeable weight already accounts for volume, so a load
     * of cushions that weighs half a tonne and fills the lorry is not billed as
     * half a tonne. Taking the larger is the only rule that respects both.
     */
    private static BigDecimal chargeableWeight(LoadLifecyclePort.LoadSummary load,
                                               BigDecimal payloadWeightKg) {
        BigDecimal planned = load.chargeableWeightKg() == null
                ? BigDecimal.ZERO : load.chargeableWeightKg();
        return payloadWeightKg == null ? planned : planned.max(payloadWeightKg);
    }

    /**
     * The surcharge percentage for the dispatch month.
     *
     * <p>Absent means none is charged, not that pricing fails. A month whose
     * index has not been entered yet is an administrative gap; refusing to
     * raise the bill would turn it into an operational one.
     */
    private BigDecimal fuelPctFor(UUID tenantId, LocalDate pricedOn) {
        return fuelIndex.findForMonth(tenantId, pricedOn.withDayOfMonth(1))
                .map(FuelSurchargeIndex::surchargePct)
                .orElse(null);
    }

    private void publishRaised(UUID tenantId, FreightBill bill, boolean priced) {
        events.publishEvent(new FreightBillRaised(tenantId, bill.id(), bill.billNo(),
                bill.tripId(), bill.vendorPartnerId(), bill.computedAmount(), priced,
                Instant.now()));
    }

    private FreightBill require(UUID billId) {
        return bills.findById(billId)
                .orElseThrow(() -> new ResourceNotFoundException("FreightBill", billId));
    }
}
