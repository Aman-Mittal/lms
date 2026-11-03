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
import java.util.List;
import java.util.UUID;

import com.lms.finance.command.BillingService;
import com.lms.finance.command.FreightBillRepository;
import com.lms.finance.command.RailLegBillingListener;
import com.lms.finance.command.domain.FreightBill;
import com.lms.finance.query.FinanceQueryService;
import com.lms.finance.query.FreightBillLineView;
import com.lms.finance.query.FreightBillView;
import com.lms.multimodal.events.RailLegCompleted;
import com.lms.multimodal.query.ItineraryQueryService;
import com.lms.multimodal.query.LegView;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Step definitions for {@code rail_settlement.feature}: the freight bill a
 * released rail leg raises, and its matching. Itineraries, legs and milestones
 * are driven by {@link MultimodalSteps}; invoicing reuses the settlement desk.
 */
public class RailSettlementSteps {

    @Autowired
    private ScenarioWorld world;
    @Autowired
    private ItineraryQueryService itineraries;
    @Autowired
    private FinanceQueryService finance;
    @Autowired
    private BillingService billing;
    @Autowired
    private FreightBillRepository bills;
    @Autowired
    private RailLegBillingListener listener;
    @Autowired
    private MultimodalEventProbe announced;
    @Autowired
    private PlatformTransactionManager transactionManager;

    private RuntimeException failure;

    // ------------------------------------------------------------------- when

    @When("{string} invoices {int} against the rail freight bill for leg {int} of shipment {string}")
    public void carrierInvoices(String carrier, int amount, int seq, String shipment) {
        failure = null;
        try {
            billing.matchVendorClaim(bill(shipment, seq).id(), BigDecimal.valueOf(amount));
        } catch (RuntimeException e) {
            failure = e;
        }
    }

    @When("the dispute on the rail freight bill for leg {int} of shipment {string} is settled "
            + "with no reason given")
    public void settledWithNoReason(int seq, String shipment) {
        settle(seq, shipment, "  ");
    }

    @When("the dispute on the rail freight bill for leg {int} of shipment {string} is settled "
            + "because {string}")
    public void settledBecause(int seq, String shipment, String reason) {
        settle(seq, shipment, reason);
    }

    /** Delivers the announcement again, as the outbox would after a restart. */
    @When("the release of rail leg {int} of shipment {string} is heard again")
    public void releaseHeardAgain(int seq, String shipment) {
        listener.onRailLegCompleted(announcement(seq, shipment));
    }

    // ------------------------------------------------------------------- then

    @Then("shipment {string} has {int} rail freight bills")
    public void railBillCount(String shipment, int expected) {
        assertThat(railBills(shipment)).hasSize(expected);
    }

    @Then("the rail freight bill for leg {int} of shipment {string} is {string} and totals {int}")
    public void billIsAndTotals(int seq, String shipment, String status, int total) {
        FreightBillView bill = bill(shipment, seq);
        assertThat(bill.status()).isEqualTo(status);
        assertThat(bill.computedAmount()).isEqualByComparingTo(BigDecimal.valueOf(total));
        assertThat(bill.source()).isEqualTo("RAIL");
    }

    @Then("the rail freight bill for leg {int} of shipment {string} is payable to carrier {string}")
    public void payableTo(int seq, String shipment, String carrier) {
        UUID billId = bill(shipment, seq).id();
        FreightBill stored = new TransactionTemplate(transactionManager)
                .execute(status -> bills.findById(billId).orElseThrow());
        assertThat(stored.vendorPartnerId()).isEqualTo(world.ref(carrier));
    }

    @Then("the rail freight bill for leg {int} of shipment {string} has no trip and no load")
    public void noTripNoLoad(int seq, String shipment) {
        FreightBillView bill = bill(shipment, seq);
        assertThat(bill.tripId()).isNull();
        assertThat(bill.loadId()).isNull();
        assertThat(bill.itineraryLegId()).isEqualTo(leg(shipment, seq).id());
        assertThat(bill.legSeq()).isEqualTo(seq);
    }

    @Then("the rail freight bill for leg {int} of shipment {string} explains itself as "
            + "{int} wagons on booking {string}")
    public void explains(int seq, String shipment, int wagons, String booking) {
        List<FreightBillLineView> lines = finance.linesOf(bill(shipment, seq).id());
        assertThat(lines).hasSize(1);
        assertThat(lines.get(0).chargeType()).isEqualTo("BASE_FREIGHT");
        assertThat(lines.get(0).quantity()).isEqualByComparingTo(BigDecimal.valueOf(wagons));
        assertThat(lines.get(0).narrative()).contains("booking " + booking);
    }

    @Then("the rail freight bill for leg {int} of shipment {string} adds up")
    public void addsUp(int seq, String shipment) {
        FreightBillView bill = bill(shipment, seq);
        BigDecimal sum = finance.linesOf(bill.id()).stream()
                .map(FreightBillLineView::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(bill.computedAmount()).isEqualByComparingTo(sum);
    }

    @Then("the rail freight bill for leg {int} of shipment {string} records a variance of {int}")
    public void variance(int seq, String shipment, int expected) {
        assertThat(bill(shipment, seq).varianceAmount())
                .isEqualByComparingTo(BigDecimal.valueOf(expected));
    }

    @Then("the release of rail leg {int} of shipment {string} was announced once")
    public void announcedOnce(int seq, String shipment) {
        UUID legId = leg(shipment, seq).id();
        assertThat(announced.completed().stream()
                .filter(event -> event.legId().equals(legId)).toList()).hasSize(1);
    }

    @Then("settling the rail freight bill is refused mentioning {string}")
    public void settlingRefused(String fragment) {
        assertThat(failure).as("settling should have been refused").isNotNull();
        assertThat(failure.getMessage()).contains(fragment);
    }

    // --------------------------------------------------------------- helpers

    private void settle(int seq, String shipment, String reason) {
        failure = null;
        try {
            billing.resolveDispute(bill(shipment, seq).id(), reason);
        } catch (RuntimeException e) {
            failure = e;
        }
    }

    private RailLegCompleted announcement(int seq, String shipment) {
        UUID legId = leg(shipment, seq).id();
        return announced.completed().stream()
                .filter(event -> event.legId().equals(legId))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Leg " + seq + " of " + shipment
                        + " was never announced as released"));
    }

    private LegView leg(String shipment, int seq) {
        return itineraries.findById(world.ref("itinerary:" + shipment)).orElseThrow()
                .legs().stream().filter(l -> l.seq() == seq).findFirst()
                .orElseThrow(() -> new AssertionError("No leg " + seq + " on " + shipment));
    }

    private List<FreightBillView> railBills(String shipment) {
        String itineraryNo = itineraries.findById(world.ref("itinerary:" + shipment))
                .orElseThrow().itineraryNo();
        return finance.list(null, null, 200).items().stream()
                .filter(b -> itineraryNo.equals(b.itineraryNo()))
                .toList();
    }

    private FreightBillView bill(String shipment, int seq) {
        return railBills(shipment).stream()
                .filter(b -> Integer.valueOf(seq).equals(b.legSeq()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No rail freight bill for leg " + seq
                        + " of " + shipment));
    }
}
