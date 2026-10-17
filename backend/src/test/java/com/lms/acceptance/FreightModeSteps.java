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
package com.lms.acceptance;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import com.lms.finance.command.DocketReceivableRepository;
import com.lms.finance.command.LtlRatingService;
import com.lms.finance.command.domain.DocketReceivable;
import com.lms.finance.command.domain.LtlRateCard;
import com.lms.order.api.FreightMode;
import com.lms.order.command.OrderCommandService;
import com.lms.order.command.domain.SalesOrder;
import com.lms.planning.api.LoadLifecyclePort;
import com.lms.planning.command.ConsignmentRepository;
import com.lms.planning.command.PlanningCommandService;
import com.lms.planning.command.domain.Consignment;
import com.lms.shared.error.BusinessRuleViolationException;
import com.lms.shared.tenant.TenantContext;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Step definitions for {@code freightmodes.feature}.
 *
 * <p>Order lines, validation, consignment generation, lorries and load
 * assertions are shared with {@code planning.feature} through
 * {@link PlanningSteps}: a part-load order is still an order, and restating
 * those steps here would register the same expressions twice, which aborts
 * glue registration for the whole suite. Every expression below is unique
 * across every step class.
 */
public class FreightModeSteps {

    @Autowired
    private ScenarioWorld world;
    @Autowired
    private OrderCommandService orderCommands;
    @Autowired
    private PlanningCommandService planning;
    @Autowired
    private LoadLifecyclePort loads;
    @Autowired
    private ConsignmentRepository consignments;
    @Autowired
    private LtlRatingService ltlRating;
    @Autowired
    private DocketReceivableRepository receivables;
    @Autowired
    private PlatformTransactionManager transactionManager;

    private RuntimeException failure;

    // ------------------------------------------------------------ rating data

    @Given("terminal {string} is in LTL zone {string}")
    public void terminalInZone(String terminalCode, String zone) {
        ltlRating.assignZone(world.ref(terminalCode), zone, false);
    }

    @Given("terminal {string} is out of the delivery area in LTL zone {string}")
    public void terminalOutOfDeliveryArea(String terminalCode, String zone) {
        ltlRating.assignZone(world.ref(terminalCode), zone, true);
    }

    @Given("an LTL rate card {string} from zone {string} to zone {string} charging {double} per {word} "
            + "with a {int} kg minimum, {int} kg per CFT, a {int} docket charge and a {int} ODA surcharge")
    public void anLtlRateCard(String code, String originZone, String destinationZone, double rate,
                              String unit, int minimumKg, int kgPerCft, int docketCharge,
                              int odaSurcharge) {
        LtlRateCard.RateBasis basis = switch (unit) {
            case "kg" -> LtlRateCard.RateBasis.PER_KG;
            case "CFT" -> LtlRateCard.RateBasis.PER_CFT;
            default -> throw new IllegalArgumentException("No rate basis per " + unit);
        };
        // Started a month ago, so that it is in force on whatever day the
        // suite runs and a docket booked today is priced by it.
        ltlRating.publishRateCard(world.uniqueCode(code), originZone, destinationZone, "INR",
                LocalDate.now(ZoneOffset.UTC).minusDays(30), basis, BigDecimal.valueOf(rate),
                BigDecimal.valueOf(minimumKg), BigDecimal.valueOf(kgPerCft),
                BigDecimal.valueOf(docketCharge), BigDecimal.valueOf(odaSurcharge));
    }

    // --------------------------------------------------------------- bookings

    /** An LTL order enters the network at its origin hub, so that is its origin. */
    @Given("an LTL booking {string} for {string} through hub {string} to hub {string}")
    public void anLtlBooking(String orderNo, String customerCode, String originHub,
                             String destinationHub) {
        world.putRef("order:" + orderNo, orderCommands.raiseOrder(world.orgUnitId(),
                world.ref(customerCode), world.uniqueCode(orderNo), world.ref(originHub),
                null, null, SalesOrder.OrderSource.MANUAL, FreightMode.LTL,
                world.ref(originHub), world.ref(destinationHub)));
    }

    @When("a {word} booking {string} for {string} from {string} is attempted")
    public void aBookingIsAttempted(String mode, String orderNo, String customerCode,
                                    String originCode) {
        attempt(() -> orderCommands.raiseOrder(world.orgUnitId(), world.ref(customerCode),
                world.uniqueCode(orderNo), world.ref(originCode), null, null,
                SalesOrder.OrderSource.MANUAL, FreightMode.valueOf(mode), null, null));
    }

    @Then("the booking is refused with {string}")
    public void bookingRefused(String code) {
        assertRefusedWith(code);
    }

    // ---------------------------------------------------------- consolidation

    @When("an LTL load {string} is opened at hub {string} on lorry {string}")
    public void anLtlLoad(String loadNo, String hubCode, String registration) {
        world.putRef("load:" + loadNo, planning.openLoad(world.orgUnitId(),
                world.uniqueCode(loadNo), world.ref(hubCode), world.ref("vehicle:" + registration),
                FreightMode.LTL));
    }

    @When("the shipment of order {string} for {string} is put on load {string}")
    public void putOnLoad(String orderNo, String consigneeCode, String loadNo) {
        UUID consignmentId = shipment(orderNo, consigneeCode).id();
        attempt(() -> {
            planning.assignConsignment(world.ref("load:" + loadNo), consignmentId);
            return null;
        });
    }

    @Then("consolidation is refused with {string}")
    public void consolidationRefused(String code) {
        assertRefusedWith(code);
    }

    @Then("the shipment of order {string} for {string} is numbered in the {string} series")
    public void numberedInSeries(String orderNo, String consigneeCode, String prefix) {
        Consignment consignment = shipment(orderNo, consigneeCode);
        assertThat(consignment.trackingRef()).startsWith(prefix);
        assertThat(consignment.isDocket()).isEqualTo("DKT-".equals(prefix));
    }

    @Then("load {string} consolidates dockets from {int} customers")
    public void consolidatesCustomers(String loadNo, int expected) {
        List<Consignment> aboard = inTransaction(() ->
                consignments.findOnLoad(TenantContext.requireTenantId(), world.ref("load:" + loadNo)));
        assertThat(aboard).allMatch(Consignment::isDocket);
        assertThat(aboard.stream().map(Consignment::customerPartnerId).distinct().count())
                .isEqualTo(expected);
    }

    /**
     * A consolidated vehicle is hired for its line haul, so the lane sourcing
     * and the carrier's rate card see runs hub to hub, not to any consignee.
     */
    @Then("load {string} is sourced on the lane from {string} to {string}")
    public void sourcedOnLane(String loadNo, String originCode, String destinationCode) {
        LoadLifecyclePort.LoadSummary summary = loads.summarise(world.ref("load:" + loadNo))
                .orElseThrow();
        assertThat(summary.freightMode()).isEqualTo("LTL");
        assertThat(summary.originTerminalId()).isEqualTo(world.ref(originCode));
        assertThat(summary.destinationTerminalId()).isEqualTo(world.ref(destinationCode));
        assertThat(summary.dropCount()).isEqualTo(1);
    }

    // ------------------------------------------------------------ receivables

    @Then("order {string} raised no docket receivable")
    public void noReceivable(String orderNo) {
        UUID orderId = world.ref("order:" + orderNo);
        List<Consignment> all = inTransaction(() ->
                consignments.findByOrder(TenantContext.requireTenantId(), orderId));
        assertThat(all).isNotEmpty();
        for (Consignment consignment : all) {
            assertThat(receivableFor(consignment)).isEmpty();
        }
    }

    @Then("the receivable for order {string} and {string} is charged on {double} kg")
    public void chargedOn(String orderNo, String consigneeCode, double kg) {
        DocketReceivable receivable = priced(orderNo, consigneeCode);
        assertThat(receivable.chargeableWeightKg()).isEqualByComparingTo(BigDecimal.valueOf(kg));
    }

    @Then("the receivable for order {string} and {string} applied the minimum weight")
    public void minimumApplied(String orderNo, String consigneeCode) {
        assertThat(priced(orderNo, consigneeCode).minimumApplied()).isTrue();
    }

    @Then("the receivable for order {string} and {string} carries a docket charge of {double} "
            + "and an ODA surcharge of {double}")
    public void carriesCharges(String orderNo, String consigneeCode, double docketCharge,
                               double odaSurcharge) {
        DocketReceivable receivable = priced(orderNo, consigneeCode);
        assertThat(receivable.docketCharge()).isEqualByComparingTo(BigDecimal.valueOf(docketCharge));
        assertThat(receivable.odaSurcharge()).isEqualByComparingTo(BigDecimal.valueOf(odaSurcharge));
        assertThat(receivable.outOfDeliveryArea()).isEqualTo(odaSurcharge > 0);
    }

    @Then("the receivable for order {string} and {string} totals {double}")
    public void totals(String orderNo, String consigneeCode, double total) {
        DocketReceivable receivable = priced(orderNo, consigneeCode);
        assertThat(receivable.totalAmount()).isEqualByComparingTo(BigDecimal.valueOf(total));
        // The total is the sum of its parts, never a separate figure.
        assertThat(receivable.totalAmount()).isEqualByComparingTo(receivable.freightAmount()
                .add(receivable.docketCharge()).add(receivable.odaSurcharge()));
    }

    @Then("the receivable for order {string} and {string} is unpriced because {string}")
    public void unpriced(String orderNo, String consigneeCode, String fragment) {
        DocketReceivable receivable = receivableFor(shipment(orderNo, consigneeCode))
                .orElseThrow(() -> new AssertionError("No receivable was raised"));
        assertThat(receivable.status()).isEqualTo(DocketReceivable.Status.UNPRICED);
        assertThat(receivable.reason()).contains(fragment);
        assertThat(receivable.totalAmount()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    // ---------------------------------------------------------------- helpers

    private DocketReceivable priced(String orderNo, String consigneeCode) {
        DocketReceivable receivable = receivableFor(shipment(orderNo, consigneeCode))
                .orElseThrow(() -> new AssertionError("No receivable was raised for the docket"));
        assertThat(receivable.status())
                .as("receivable should be priced, but: %s", receivable.reason())
                .isEqualTo(DocketReceivable.Status.PRICED);
        return receivable;
    }

    private Optional<DocketReceivable> receivableFor(Consignment consignment) {
        return inTransaction(() ->
                receivables.findByDocket(TenantContext.requireTenantId(), consignment.id()));
    }

    private Consignment shipment(String orderNo, String consigneeCode) {
        UUID orderId = world.ref("order:" + orderNo);
        UUID consigneeId = world.ref(consigneeCode);
        return inTransaction(() -> consignments
                .findByOrder(TenantContext.requireTenantId(), orderId).stream()
                .filter(c -> c.consigneePartnerId().equals(consigneeId))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "Order " + orderNo + " has no consignment for " + consigneeCode)));
    }

    private void assertRefusedWith(String code) {
        assertThat(failure)
                .as("expected a refusal with '%s' but the operation succeeded", code)
                .isInstanceOf(BusinessRuleViolationException.class);
        assertThat(((BusinessRuleViolationException) failure).code()).isEqualTo(code);
    }

    private <T> T attempt(Supplier<T> action) {
        failure = null;
        try {
            return action.get();
        } catch (BusinessRuleViolationException | IllegalArgumentException e) {
            failure = e;
            return null;
        }
    }

    private <T> T inTransaction(Supplier<T> action) {
        TenantContext.requireTenantId();
        return new TransactionTemplate(transactionManager).execute(status -> action.get());
    }
}
