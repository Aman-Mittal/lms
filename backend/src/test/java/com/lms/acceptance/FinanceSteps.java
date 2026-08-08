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
 */package com.lms.acceptance;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

import com.lms.execution.command.TripCommandService;
import com.lms.execution.command.domain.WeighbridgeReading;
import com.lms.finance.command.BillingService;
import com.lms.finance.command.FreightBillRepository;
import com.lms.finance.command.TariffCommandService;
import com.lms.finance.command.TariffRepository;
import com.lms.finance.command.domain.Tariff;
import com.lms.finance.command.domain.TariffSlab;
import com.lms.finance.query.FinanceQueryService;
import com.lms.finance.query.FreightBillLineView;
import com.lms.finance.query.FreightBillView;
import com.lms.shared.tenant.TenantContext;
import io.cucumber.datatable.DataTable;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/** Step definitions for {@code finance.feature}. */
public class FinanceSteps {

    /**
     * The tare of the lorry the fixtures register, so the gross reading can be
     * derived from the weight the Gherkin asks for.
     *
     * <p>Kept in step with {@code PlanningSteps.registerLorry}. If the two ever
     * disagree the payload comes out wrong, dispatch fails the weight
     * cross-check, and the failure points at execution rather than here.
     */
    private static final BigDecimal TARE_KG = new BigDecimal("6000");

    /**
     * The vehicle type of the lorry the fixtures register.
     *
     * <p>Rate cards are keyed on it, so a card published for anything else
     * silently fails to match and the trip is billed UNPRICED -- a failure that
     * looks like a missing rate card rather than like a typo. Kept in step with
     * {@code PlanningSteps.registerLorry}, where "TRUCK" is the category and
     * this is the type.
     */
    private static final String VEHICLE_TYPE = "RIGID";

    @Autowired
    private ScenarioWorld world;
    @Autowired
    private SpineFixtures fixtures;
    @Autowired
    private TariffCommandService tariffCommands;
    @Autowired
    private TariffRepository tariffs;
    @Autowired
    private BillingService billing;
    @Autowired
    private FreightBillRepository bills;
    @Autowired
    private FinanceQueryService finance;
    @Autowired
    private TripCommandService tripCommands;
    @Autowired
    private PlatformTransactionManager transactionManager;

    /** The card the most recent {@code a rate card ...} step published. */
    private UUID currentTariffId;

    /** What each trip is carrying, so the weighbridge readings can be derived. */
    private final Map<String, TripUnderTest> trips = new HashMap<>();

    private RuntimeException failure;

    private record TripUnderTest(UUID tripId, UUID loadId, UUID vendorPartnerId, String tripNo,
                                 BigDecimal weightKg, Instant dispatchedAt, Instant gateInAt) {
    }

    // ------------------------------------------------------------ rate cards

    @Given("a rate card {string} from {string} to {string} for {string} effective {int} days ago:")
    public void aRateCard(String code, String originCode, String destinationCode,
                          String vendorCode, int daysAgo, DataTable terms) {
        Map<String, String> row = terms.asMap();
        failure = null;
        try {
            UUID id = tariffCommands.publishTariff(world.orgUnitId(), world.uniqueCode(code),
                    world.ref(vendorCode), world.ref(originCode), world.ref(destinationCode),
                    VEHICLE_TYPE, "INR", LocalDate.now(ZoneOffset.UTC).minusDays(daysAgo),
                    new BigDecimal(row.get("free hours")),
                    new BigDecimal(row.get("detention per hour")),
                    new BigDecimal(row.get("additional drop")),
                    new BigDecimal(row.get("minimum charge")));
            currentTariffId = id;
            world.putRef("tariff:" + code, id);
        } catch (RuntimeException e) {
            failure = e;
        }
    }

    @Given("the card charges {double} per kg for any weight")
    public void theCardChargesPerKg(double rate) {
        addBand(BigDecimal.ZERO, null, rate);
    }

    @When("the card charges {double} per kg from {int} kg to {int} kg")
    public void theCardChargesBand(double rate, int fromKg, int toKg) {
        addBand(BigDecimal.valueOf(fromKg), BigDecimal.valueOf(toKg), rate);
    }

    @Given("the fuel surcharge {int} days ago was {double} percent")
    public void theFuelSurcharge(int daysAgo, double pct) {
        tariffCommands.recordFuelSurcharge(LocalDate.now(ZoneOffset.UTC).minusDays(daysAgo),
                BigDecimal.valueOf(pct));
    }

    // ----------------------------------------------------------------- trips

    @Given("a trip {string} carrying {int} kg for {string}")
    public void aTrip(String tripNo, int weightKg, String vendorCode) {
        raiseTrip(tripNo, weightKg, vendorCode,
                fixtures.awardedLoad(world.orgUnitId(), world.uniqueCode("LOAD-" + tripNo),
                        world.ref("CUST"), world.ref("ORIGIN"), world.ref("CONS-A"),
                        world.ref("DEST-A"), world.ref("vehicle:MH-01-TL-0001"),
                        world.ref(vendorCode), BigDecimal.valueOf(weightKg), false));
    }

    @Given("a trip {string} carrying {int} kg for {string} dropping at {string}")
    public void aTripDroppingAt(String tripNo, int weightKg, String vendorCode,
                                String destinationCode) {
        raiseTrip(tripNo, weightKg, vendorCode,
                fixtures.awardedLoad(world.orgUnitId(), world.uniqueCode("LOAD-" + tripNo),
                        world.ref("CUST"), world.ref("ORIGIN"), world.ref("CONS-A"),
                        world.ref(destinationCode), world.ref("vehicle:MH-01-TL-0001"),
                        world.ref(vendorCode), BigDecimal.valueOf(weightKg), false));
    }

    @Given("a trip {string} carrying {int} kg for {string} dropping at {string} and {string}")
    public void aTripWithTwoDrops(String tripNo, int weightKg, String vendorCode,
                                  String firstDrop, String lastDrop) {
        raiseTrip(tripNo, weightKg, vendorCode,
                fixtures.awardedMultiDropLoad(world.orgUnitId(),
                        world.uniqueCode("LOAD-" + tripNo), world.ref("CUST"),
                        world.ref("ORIGIN"), world.ref("CONS-A"),
                        List.of(world.ref(firstDrop), world.ref(lastDrop)),
                        world.ref("vehicle:MH-01-TL-0001"), world.ref(vendorCode),
                        BigDecimal.valueOf(weightKg)));
    }

    /**
     * Takes the trip from the gate to the destination with explicit timestamps.
     *
     * <p>The times are the point of the step. Pricing depends on the dispatch
     * <em>date</em> and detention on the dwell, so a fixture that used "now"
     * for both could not distinguish a correct rating engine from one that read
     * today's rate card.
     */
    @When("trip {string} is run, dispatched {int} days ago after {int} minutes at the origin")
    public void tripIsRun(String tripNo, int daysAgo, int dwellMinutes) {
        TripUnderTest trip = trips.get(tripNo);
        Instant dispatchedAt = Instant.now().minus(daysAgo, ChronoUnit.DAYS);
        Instant gateInAt = dispatchedAt.minus(dwellMinutes, ChronoUnit.MINUTES);
        UUID origin = world.ref("ORIGIN");

        tripCommands.gateIn(trip.tripId(), origin, gateInAt, null);
        tripCommands.recordWeighing(trip.tripId(), origin,
                WeighbridgeReading.ReadingType.TARE, TARE_KG, gateInAt);
        tripCommands.recordWeighing(trip.tripId(), origin,
                WeighbridgeReading.ReadingType.GROSS, TARE_KG.add(trip.weightKg()), gateInAt);
        tripCommands.markLoaded(trip.tripId(), dispatchedAt.minus(1, ChronoUnit.MINUTES));
        tripCommands.attachDocument(trip.tripId(), "CONSIGNMENT_NOTE", "LR-" + tripNo);
        tripCommands.attachDocument(trip.tripId(), "EWAY_BILL", "EW-" + tripNo);
        tripCommands.dispatch(trip.tripId(), dispatchedAt);
        tripCommands.beginTransit(trip.tripId());
        tripCommands.arrive(trip.tripId(), dispatchedAt.plus(6, ChronoUnit.HOURS));

        trips.put(tripNo, new TripUnderTest(trip.tripId(), trip.loadId(), trip.vendorPartnerId(),
                trip.tripNo(), trip.weightKg(), dispatchedAt, gateInAt));
    }

    /**
     * Proof of delivery, which is what raises the bill.
     *
     * <p>The listener is an after-commit one and runs on this thread, so by the
     * time this step returns the bill exists. That is a design choice rather
     * than luck -- see {@code TripBillingListener} -- and it is why these
     * scenarios can assert on the bill in the very next line instead of polling
     * for it.
     */
    @When("trip {string} is completed")
    public void tripIsCompleted(String tripNo) {
        UUID tripId = tripIdOf(tripNo);
        tripCommands.complete(tripId, Instant.now());

        UUID billId = inTransaction(() -> bills.findByTrip(TenantContext.requireTenantId(),
                tripId).orElseThrow(() ->
                new AssertionError("Completing trip " + tripNo + " raised no freight bill")).id());
        world.putRef("bill:FB-" + tripNo, billId);
    }

    /**
     * The trip, whichever step family raised it.
     *
     * <p>These scenarios build their own trips with explicit timestamps, but
     * the end-to-end walk in {@code spine.feature} raises one through
     * execution's own steps -- and a completion step that only knew about its
     * own map would make the two families uncombinable, which is the one thing
     * a spine scenario needs them to be.
     */
    private UUID tripIdOf(String tripNo) {
        TripUnderTest known = trips.get(tripNo);
        return known != null ? known.tripId() : world.ref("trip:" + tripNo);
    }

    // ------------------------------------------------------------ settlement

    @When("{string} invoices {int} against {string}")
    public void vendorInvoices(String vendorCode, int amount, String billNo) {
        failure = null;
        try {
            billing.matchVendorClaim(world.ref("bill:" + billNo), BigDecimal.valueOf(amount));
        } catch (RuntimeException e) {
            failure = e;
        }
    }

    @When("the dispute on {string} is settled with no reason given")
    public void settledWithNoReason(String billNo) {
        settle(billNo, "  ");
    }

    @When("the dispute on {string} is settled because {string}")
    public void settledBecause(String billNo, String reason) {
        settle(billNo, reason);
    }

    @When("the minimum charge on {string} is edited directly")
    public void editTariffDirectly(String code) {
        failure = null;
        try {
            inTransaction(() -> {
                Tariff card = tariffs.findById(world.ref("tariff:" + code)).orElseThrow();
                return tariffs.save(new Tariff(card.id(), card.tenantId(), card.orgUnitId(),
                        card.code(), card.vendorPartnerId(), card.originTerminalId(),
                        card.destinationTerminalId(), card.vehicleType(), card.currency(),
                        card.effectiveFrom(), card.effectiveTo(), card.detentionFreeHours(),
                        card.detentionHourlyRate(), card.additionalDropFee(),
                        new BigDecimal("99999"), card.version(), card.createdAt()));
            });
        } catch (RuntimeException e) {
            failure = e;
        }
    }

    @When("the bill for trip {string} is raised again")
    public void raiseBillAgain(String tripNo) {
        TripUnderTest trip = trips.get(tripNo);
        billing.raiseBillFor(trip.tripId(), trip.loadId(), trip.vendorPartnerId(),
                trip.tripNo(), trip.dispatchedAt(),
                java.time.Duration.between(trip.gateInAt(), trip.dispatchedAt()),
                trip.weightKg());
    }

    // ---------------------------------------------------------- assertions

    @Then("bill {string} is {string}")
    public void billIs(String billNo, String status) {
        assertThat(bill(billNo).status()).isEqualTo(status);
    }

    @Then("bill {string} totals {int}")
    public void billTotals(String billNo, int expected) {
        assertThat(bill(billNo).computedAmount())
                .as("computed total of %s", billNo)
                .isEqualByComparingTo(BigDecimal.valueOf(expected));
    }

    @Then("bill {string} has a {string} line of {int}")
    @Then("bill {string} has an {string} line of {int}")
    public void billHasLine(String billNo, String chargeType, int amount) {
        List<FreightBillLineView> matching = linesOf(billNo).stream()
                .filter(line -> line.chargeType().equals(chargeType))
                .toList();

        assertThat(matching).as("%s lines on %s", chargeType, billNo).hasSize(1);
        assertThat(matching.get(0).amount())
                .as("%s on %s", chargeType, billNo)
                .isEqualByComparingTo(BigDecimal.valueOf(amount));
    }

    @Then("bill {string} has no {string} line")
    public void billHasNoLine(String billNo, String chargeType) {
        assertThat(linesOf(billNo))
                .as("%s should carry no %s line", billNo, chargeType)
                .noneMatch(line -> line.chargeType().equals(chargeType));
    }

    /**
     * The total is the sum of the lines shown beneath it.
     *
     * <p>Asserted separately from the amounts because it is a different bug. A
     * total that does not equal its own breakdown is the single most damaging
     * thing this module could produce: it would be argued over rather than
     * noticed.
     */
    @Then("bill {string} adds up")
    public void billAddsUp(String billNo) {
        BigDecimal sum = linesOf(billNo).stream()
                .map(FreightBillLineView::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(bill(billNo).computedAmount())
                .as("total of %s against the sum of its lines", billNo)
                .isEqualByComparingTo(sum);
    }

    @Then("bill {string} was priced under {string}")
    public void billPricedUnder(String billNo, String tariffCode) {
        assertThat(bill(billNo).tariffCode()).isEqualTo(world.uniqueCode(tariffCode));
    }

    @Then("bill {string} records a variance of {int}")
    public void billVariance(String billNo, int expected) {
        assertThat(bill(billNo).varianceAmount())
                .isEqualByComparingTo(BigDecimal.valueOf(expected));
    }

    @Then("bill {string} records the resolution {string}")
    public void billResolution(String billNo, String fragment) {
        assertThat(bill(billNo).resolution()).contains(fragment);
    }

    @Then("bill {string} explains itself mentioning {string}")
    public void billExplains(String billNo, String fragment) {
        assertThat(bill(billNo).resolution()).contains(fragment);
    }

    @Then("rate card {string} was closed {int} days ago")
    public void cardWasClosed(String code, int daysAgo) {
        Tariff card = inTransaction(() ->
                tariffs.findById(world.ref("tariff:" + code)).orElseThrow());
        assertThat(card.effectiveTo())
                .isEqualTo(LocalDate.now(ZoneOffset.UTC).minusDays(daysAgo));
    }

    @Then("rate card {string} is the version in force")
    public void cardIsInForce(String code) {
        Tariff card = inTransaction(() ->
                tariffs.findById(world.ref("tariff:" + code)).orElseThrow());
        assertThat(card.effectiveTo()).as("an open version has no end date").isNull();
    }

    @Then("trip {string} has exactly {int} bill")
    public void tripHasBills(String tripNo, int expected) {
        UUID tripId = trips.get(tripNo).tripId();
        long count = inTransaction(() -> java.util.stream.StreamSupport
                .stream(bills.findAll().spliterator(), false)
                .filter(b -> b.tripId().equals(tripId))
                .count());
        assertThat(count).isEqualTo(expected);
    }

    @Then("publishing the card is refused mentioning {string}")
    public void publishRefused(String fragment) {
        assertRefused(fragment);
    }

    @Then("adding the band is refused mentioning {string}")
    public void bandRefused(String fragment) {
        assertRefused(fragment);
    }

    @Then("the edit is refused mentioning {string}")
    public void editRefused(String fragment) {
        assertRefused(fragment);
    }

    @Then("settling is refused mentioning {string}")
    public void settlingRefused(String fragment) {
        assertRefused(fragment);
    }

    @Then("matching is refused mentioning {string}")
    public void matchingRefused(String fragment) {
        assertRefused(fragment);
    }

    @Then("matching is refused as unauthorised")
    public void matchingUnauthorised() {
        assertThat(failure).isInstanceOf(AccessDeniedException.class);
    }

    @Then("publishing the card is refused as unauthorised")
    public void publishUnauthorised() {
        assertThat(failure).isInstanceOf(AccessDeniedException.class);
    }

    // ------------------------------------------------------------- internals

    private void addBand(BigDecimal from, BigDecimal to, double rate) {
        failure = null;
        try {
            tariffCommands.addSlab(currentTariffId, from, to, TariffSlab.RateBasis.PER_KG,
                    BigDecimal.valueOf(rate));
        } catch (RuntimeException e) {
            failure = e;
        }
    }

    private void settle(String billNo, String reason) {
        failure = null;
        try {
            billing.resolveDispute(world.ref("bill:" + billNo), reason);
        } catch (RuntimeException e) {
            failure = e;
        }
    }

    private void raiseTrip(String tripNo, int weightKg, String vendorCode, UUID loadId) {
        String salted = world.uniqueCode(tripNo);
        UUID tripId = tripCommands.raiseTrip(salted, loadId, Instant.now());
        tripCommands.assign(tripId, world.ref("vehicle:MH-01-TL-0001"), world.ref("driver:Ravi"));
        trips.put(tripNo, new TripUnderTest(tripId, loadId, world.ref(vendorCode), salted,
                BigDecimal.valueOf(weightKg), null, null));
    }

    private FreightBillView bill(String billNo) {
        return inTransaction(() -> finance.findById(world.ref("bill:" + billNo))
                .orElseThrow(() -> new AssertionError("No bill " + billNo)));
    }

    private List<FreightBillLineView> linesOf(String billNo) {
        return inTransaction(() -> finance.linesOf(world.ref("bill:" + billNo)));
    }

    /**
     * Asserts a refusal by looking through the whole cause chain.
     *
     * <p>The rate-card rules are enforced in two different places -- the
     * service for the ones it can see, the database trigger for the one it
     * cannot -- and only the second arrives wrapped in Spring's data-access
     * translation. A test that only read the top-level message would pass for
     * the service rules and quietly stop checking the trigger.
     */
    private void assertRefused(String fragment) {
        assertThat(failure).as("the operation should have been refused").isNotNull();

        StringBuilder chain = new StringBuilder();
        for (Throwable t = failure; t != null; t = t.getCause()) {
            chain.append(t.getMessage()).append(' ');
        }
        assertThat(chain.toString())
                .as("refusal should mention '%s'", fragment)
                .contains(fragment);
    }

    /**
     * Runs a read or a repository call in a transaction, which row-level
     * security requires: the tenant identifier is set at transaction start, so
     * a bare repository call outside one sees nothing at all.
     */
    private <T> T inTransaction(Supplier<T> action) {
        return new TransactionTemplate(transactionManager).execute(status -> action.get());
    }
}
