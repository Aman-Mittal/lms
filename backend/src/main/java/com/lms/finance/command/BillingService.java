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
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.lms.finance.command.domain.CostSheet;
import com.lms.finance.command.domain.FreightBill;
import com.lms.finance.command.domain.FreightBillLine;
import com.lms.finance.command.domain.FuelSurchargeIndex;
import com.lms.finance.command.domain.Tariff;
import com.lms.finance.events.FreightBillRaised;
import com.lms.multimodal.events.RailLegCompleted;
import com.lms.planning.api.LoadLifecyclePort;
import com.lms.shared.audit.AuditTrail;
import com.lms.shared.config.Json;
import com.lms.shared.error.BusinessRuleViolationException;
import com.lms.shared.error.ResourceNotFoundException;
import com.lms.shared.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private static final Logger log = LoggerFactory.getLogger(BillingService.class);

    private final FreightBillRepository bills;
    private final FreightBillLineRepository lines;
    private final TariffRepository tariffs;
    private final TariffSlabRepository slabs;
    private final FuelSurchargeIndexRepository fuelIndex;
    private final LoadLifecyclePort loads;
    private final RatingEngine rating;
    private final AuditTrail audit;
    private final ApplicationEventPublisher events;
    private final SettlementHolds holds;

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
                          ApplicationEventPublisher events, SettlementHolds holds,
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
        this.holds = holds;
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
     * Raises the carrier payable for a completed milk-run circuit.
     *
     * <p>Unguarded by {@code @PreAuthorize} for the reason {@link #raiseBillFor}
     * is: the actor is the platform, reacting to a circuit that reached its
     * plant. Joins the caller's transaction; the listener opens a fresh one
     * after binding the tenant scope.
     *
     * <p>Idempotent on the circuit run. {@code freight_bill_circuit_run_idx}
     * is the real guarantee; this lookup turns a redelivery into a no-op.
     *
     * <p>One payable per movement. A circuit rides an ordinary execution trip,
     * and when that trip completes it is billed too -- on a lane tariff that
     * knows nothing of the circuit. The circuit's price is the contracted one,
     * so it wins: a trip bill nobody has acted on yet is cancelled with the
     * reason recorded, and a trip completed after its circuit finds the
     * circuit's bill and raises nothing. A trip bill already approved or
     * disputed is left alone and no circuit payable is raised, because
     * raising one would pay the same lorry twice; that case is logged for a
     * human to settle.
     *
     * @return the payable, or empty when there is nobody to pay or the trip
     *         was already settled on its own bill
     */
    @Transactional
    public Optional<UUID> raiseCircuitBill(CircuitCharge circuit) {
        UUID tenantId = TenantContext.requireTenantId();

        Optional<FreightBill> already = bills.findByCircuitRun(tenantId, circuit.runId());
        if (already.isPresent()) {
            return already.map(FreightBill::id);
        }
        if (circuit.vendorPartnerId() == null || circuit.loadId() == null) {
            // The tenant's own fleet ran it. There is no carrier to pay.
            log.info("Circuit {} on {} ran without a vendor; no payable raised",
                    circuit.routeCode(), circuit.runDate());
            return Optional.empty();
        }

        String billNo = "CR-" + circuit.routeCode() + "-"
                + circuit.runDate().format(DateTimeFormatter.BASIC_ISO_DATE);

        Optional<FreightBill> tripBill = bills.findByTrip(tenantId, circuit.tripId())
                .filter(bill -> bill.status() != FreightBill.BillStatus.CANCELLED);
        if (tripBill.isPresent()) {
            FreightBill existing = tripBill.get();
            if (!existing.isOpen()) {
                log.warn("Trip {} was already settled on bill {} ({}); circuit {} on {} "
                                + "raises no second payable", circuit.tripNo(),
                        existing.billNo(), existing.status(), circuit.routeCode(),
                        circuit.runDate());
                return Optional.empty();
            }
            bills.save(existing.cancel("Superseded by milk-run circuit payable " + billNo
                    + ": the circuit is paid at its route rate, not the lane tariff"));
        }

        LoadLifecyclePort.LoadSummary load = loads.summarise(circuit.loadId())
                .orElseThrow(() -> new ResourceNotFoundException("Load", circuit.loadId()));

        CostSheet sheet = circuitSheet(circuit);
        if (circuit.chargeAmount() != null
                && sheet.total().compareTo(circuit.chargeAmount()) != 0) {
            log.warn("Circuit {} on {} announced a charge of {} but its components sum to {}; "
                            + "the bill carries the components", circuit.routeCode(),
                    circuit.runDate(), circuit.chargeAmount(), sheet.total());
        }

        FreightBill bill = bills.save(FreightBill.forCircuit(UUID.randomUUID(), tenantId,
                load.orgUnitId(), circuit.tripId(), circuit.loadId(), circuit.runId(),
                circuit.vendorPartnerId(), billNo, circuit.runDate(), sheet));

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
     * Raises the rail carrier's payable for a released rail leg.
     *
     * <p>Unguarded by {@code @PreAuthorize} for the reason {@link #raiseBillFor}
     * is: the actor is the platform, reacting to a leg that finished. Joins no
     * caller's transaction, because the listener runs after the itinerary's
     * has committed.
     *
     * <p>The expected charge is the cost recorded on the leg, taken as given:
     * multimodal priced it from the rail rate card in force on the departure
     * day, or recorded the planner's spot rate, and finance has no rail card
     * of its own to second-guess it with. From here the bill is an ordinary
     * freight bill: the carrier's invoice is matched against it by the same
     * tolerance rule, disputed, approved and held to the same approval limit.
     *
     * <p>Idempotent on the leg. {@code freight_bill_itinerary_leg_idx} is the
     * real guarantee; the lookup turns a redelivery into a no-op.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<UUID> raiseRailLegBill(RailLegCompleted leg) {
        UUID tenantId = TenantContext.requireTenantId();

        Optional<FreightBill> already = bills.findByItineraryLeg(tenantId, leg.legId());
        if (already.isPresent()) {
            return already.map(FreightBill::id);
        }

        LocalDate pricedOn = (leg.departedAt() == null ? leg.releasedAt() : leg.departedAt())
                .atZone(ZoneOffset.UTC).toLocalDate();
        CostSheet sheet = railSheet(leg);
        FreightBill bill = bills.save(FreightBill.forRailLeg(UUID.randomUUID(), tenantId,
                leg.orgUnitId(), leg.legId(), leg.carrierPartnerId(), railBillNo(leg), pricedOn,
                sheet));

        int lineNo = 1;
        for (CostSheet.Charge charge : sheet.charges()) {
            lines.save(FreightBillLine.of(UUID.randomUUID(), tenantId, bill.id(), lineNo++,
                    charge.type(), charge.narrative(), charge.quantity(), charge.unit(),
                    charge.rate(), charge.amount()));
        }

        publishRaised(tenantId, bill, true);
        return Optional.of(bill.id());
    }

    /** The bill number of a rail leg's payable: unique per tenant because the itinerary's is. */
    static String railBillNo(RailLegCompleted leg) {
        return "RB-" + leg.itineraryNo() + "-" + leg.legSeq();
    }

    /**
     * The leg's recorded cost as one base-freight line. The rate shown is the
     * cost spread over the booked units, for the reader; the amount is the
     * recorded cost itself, so the line never disagrees with what the leg says.
     */
    static CostSheet railSheet(RailLegCompleted leg) {
        BigDecimal amount = leg.costAmount().setScale(2, RoundingMode.HALF_UP);
        boolean counted = leg.unitCount() != null && leg.unitCount() > 0;
        BigDecimal units = counted ? BigDecimal.valueOf(leg.unitCount()) : BigDecimal.ONE;
        String booked = counted ? leg.unitCount() + " " + leg.unitType() : "1 booking";
        String narrative = "Rail leg " + leg.legSeq() + " of " + leg.itineraryNo() + ": " + booked
                + (leg.bookingRef() == null ? "" : " on booking " + leg.bookingRef())
                + (leg.trainNo() == null ? "" : ", train " + leg.trainNo());
        CostSheet.Charge charge = new CostSheet.Charge(FreightBillLine.ChargeType.BASE_FREIGHT,
                narrative, units, counted ? leg.unitType().toLowerCase(java.util.Locale.ROOT)
                : "booking", amount.divide(units, 4, RoundingMode.HALF_UP), amount);
        return new CostSheet(leg.currency() == null ? "INR" : leg.currency(), null, null, 0,
                amount, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, List.of(charge));
    }

    /**
     * What a completed circuit is worth, as finance records it.
     *
     * @param vendorPartnerId null when the tenant's own fleet ran the circuit
     */
    public record CircuitCharge(
            UUID runId,
            String routeCode,
            LocalDate runDate,
            UUID tripId,
            String tripNo,
            UUID loadId,
            UUID vendorPartnerId,
            int stopsVisited,
            BigDecimal totalPickedUpKg,
            BigDecimal runRate,
            BigDecimal perStopFee,
            BigDecimal chargeAmount,
            String currency) {
    }

    /**
     * Records the vendor's invoice and decides whether it is payable (3.9.2).
     *
     * <p>Held to the approver's limit on the amount being claimed: a claim
     * inside tolerance is approved for payment by this very call, so the
     * limit has to apply before it, not to a later step.
     */
    @Transactional
    @PreAuthorize("hasAuthority('INVOICE_APPROVE')"
            + " and @abac.permits('INVOICE_APPROVE', 'FREIGHT_BILL', #billId, {amount: #claimedAmount})")
    public FreightBill.BillStatus matchVendorClaim(UUID billId, BigDecimal claimedAmount) {
        FreightBill bill = require(billId);
        refuseWhileHeld(bill);
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

    /**
     * Settles a dispute in the vendor's favour, with the reason on the record.
     *
     * <p>Held to the approver's limit on the amount claimed, because settling
     * in the vendor's favour is agreeing to pay the claim.
     */
    @Transactional
    @PreAuthorize("hasAuthority('INVOICE_APPROVE')"
            + " and @abac.permits('INVOICE_APPROVE', 'FREIGHT_BILL', #billId)")
    public void resolveDispute(UUID billId, String reason) {
        FreightBill bill = require(billId);
        refuseWhileHeld(bill);
        FreightBill resolved = bills.save(bill.resolveDispute(reason, Instant.now()));

        audit.record("INVOICE_DISPUTE_RESOLVED", "FREIGHT_BILL", resolved.id(),
                Json.object("status", bill.status().name()),
                Json.object("status", resolved.status().name(), "reason", reason));
    }

    // ------------------------------------------------------------- internals

    /**
     * Vision document 3.8: a critical exception suspends settlement until a
     * manager closes it.
     *
     * <p>Checked before matching, not only before approving, because matching
     * is what approves: a claim inside tolerance goes straight to
     * APPROVED_FOR_PAYMENT. The bill itself is still raised and priced while
     * held -- knowing what the trip would cost is useful to whoever is
     * investigating; paying it is what has to wait.
     */
    private void refuseWhileHeld(FreightBill bill) {
        if (bill.tripId() == null) {
            // A rail leg's bill. Holds are placed per trip and a rail leg has
            // none; a ticket on the road leg before it freezes that trip's bill.
            return;
        }
        List<String> holding = holds.holdingTickets(bill.tripId());
        if (!holding.isEmpty()) {
            throw new BusinessRuleViolationException("settlement-on-hold",
                    "Bill " + bill.billNo() + " cannot be settled while exception "
                            + String.join(", ", holding) + " is open on its trip");
        }
    }

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

    /**
     * The run rate as base freight and one line for the suppliers actually
     * collected from. A circuit that skipped every supplier still earns its
     * run rate, so the second line is left out rather than shown at zero.
     */
    private static CostSheet circuitSheet(CircuitCharge circuit) {
        BigDecimal runRate = circuit.runRate().setScale(2, RoundingMode.HALF_UP);
        BigDecimal stopsAmount = circuit.perStopFee()
                .multiply(BigDecimal.valueOf(circuit.stopsVisited()))
                .setScale(2, RoundingMode.HALF_UP);

        List<CostSheet.Charge> charges = new ArrayList<>();
        charges.add(new CostSheet.Charge(FreightBillLine.ChargeType.BASE_FREIGHT,
                "Circuit " + circuit.routeCode() + " run on " + circuit.runDate(),
                BigDecimal.ONE, "run", circuit.runRate(), runRate));
        if (circuit.stopsVisited() > 0) {
            charges.add(new CostSheet.Charge(FreightBillLine.ChargeType.COLLECTION_STOP,
                    circuit.stopsVisited() + " supplier(s) collected from",
                    BigDecimal.valueOf(circuit.stopsVisited()), "stop", circuit.perStopFee(),
                    stopsAmount));
        }
        return new CostSheet(circuit.currency() == null ? "INR" : circuit.currency(),
                circuit.totalPickedUpKg(), null, circuit.stopsVisited(), runRate,
                BigDecimal.ZERO, stopsAmount, BigDecimal.ZERO, List.copyOf(charges));
    }

    private void publishRaised(UUID tenantId, FreightBill bill, boolean priced) {
        events.publishEvent(new FreightBillRaised(tenantId, bill.id(), bill.billNo(),
                bill.tripId(), bill.itineraryLegId(), bill.vendorPartnerId(), bill.computedAmount(),
                priced,
                Instant.now()));
    }

    private FreightBill require(UUID billId) {
        return bills.findById(billId)
                .orElseThrow(() -> new ResourceNotFoundException("FreightBill", billId));
    }
}
