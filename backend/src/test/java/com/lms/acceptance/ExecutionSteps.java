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
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.function.Supplier;

import com.lms.execution.command.TripCommandService;
import com.lms.execution.command.TripRepository;
import com.lms.execution.command.domain.Trip;
import com.lms.execution.command.domain.WeighbridgeReading;
import com.lms.execution.query.TripQueryService;
import com.lms.masterdata.command.FleetCommandService;
import com.lms.masterdata.command.domain.ComplianceDocument;
import com.lms.shared.error.BusinessRuleViolationException;
import com.lms.shared.tenant.TenantContext;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/** Step definitions for {@code execution.feature}. */
public class ExecutionSteps {

    /**
     * Wall-clock times in the Gherkin are anchored to today in UTC.
     *
     * <p>Today rather than a fixed date, so that the compliance checks -- which
     * judge a certificate against the departure date -- see the same "now" the
     * fixtures used when they set expiry dates. A hard-coded date would make
     * every certificate in these scenarios expire as the calendar moved past
     * it, and the suite would start failing on a date nobody chose.
     */
    private static Instant at(String hhmm) {
        LocalTime time = LocalTime.parse(hhmm);
        return LocalDate.now(ZoneOffset.UTC).atTime(time).toInstant(ZoneOffset.UTC);
    }

    @Autowired
    private ScenarioWorld world;
    @Autowired
    private SpineFixtures fixtures;
    @Autowired
    private FleetCommandService fleet;
    @Autowired
    private TripCommandService tripCommands;
    @Autowired
    private TripQueryService tripQueries;
    @Autowired
    private TripRepository trips;
    @Autowired
    private PlatformTransactionManager transactionManager;

    private RuntimeException failure;

    // ------------------------------------------------------------ master data

    @Given("a lorry driver {string} licensed for {int} days")
    public void aLorryDriver(String name, int days) {
        world.putRef("driver:" + name, fleet.registerDriver(world.orgUnitId(), null, name,
                "+910000000001", world.uniqueCode("DL-" + name), "HEAVY", "RTO",
                LocalDate.now(ZoneOffset.UTC).plusDays(days), false));
    }

    @Given("lorry {string} has an {string} certificate valid for {int} days")
    @Given("lorry {string} has a {string} certificate valid for {int} days")
    public void lorryCertificate(String registration, String type, int days) {
        fleet.attachDocument(ComplianceDocument.OwnerType.VEHICLE,
                world.ref("vehicle:" + registration), type, type + "-" + registration, "RTO",
                LocalDate.now(ZoneOffset.UTC).minusYears(1),
                LocalDate.now(ZoneOffset.UTC).plusDays(days));
    }

    // ------------------------------------------------------------------ loads

    @Given("an awarded load {string} of {int} kg from {string} to {string} at {string} "
            + "on lorry {string} for {string}")
    public void anAwardedLoad(String loadNo, int weightKg, String originCode, String consigneeCode,
                              String destinationCode, String registration, String vendorCode) {
        awardLoad(loadNo, weightKg, originCode, consigneeCode, destinationCode,
                registration, vendorCode, false);
    }

    @Given("an awarded hazmat load {string} of {int} kg from {string} to {string} at {string} "
            + "on lorry {string} for {string}")
    public void anAwardedHazmatLoad(String loadNo, int weightKg, String originCode,
                                    String consigneeCode, String destinationCode,
                                    String registration, String vendorCode) {
        awardLoad(loadNo, weightKg, originCode, consigneeCode, destinationCode,
                registration, vendorCode, true);
    }

    // ------------------------------------------------------------------ trips

    @When("a trip {string} is raised against load {string}")
    public void raiseTrip(String tripNo, String loadNo) {
        raise(tripNo, loadNo, Instant.now());
    }

    @When("a trip {string} is raised against load {string} departing in {int} days")
    public void raiseTripDeparting(String tripNo, String loadNo, int days) {
        raise(tripNo, loadNo, Instant.now().plus(days, ChronoUnit.DAYS));
    }

    @When("trip {string} is assigned lorry {string} and driver {string}")
    public void assignTrip(String tripNo, String registration, String driverName) {
        attempt(() -> {
            tripCommands.assign(world.ref("trip:" + tripNo),
                    world.ref("vehicle:" + registration), world.ref("driver:" + driverName));
            return null;
        });
    }

    @When("trip {string} gates in at {word}")
    public void gateIn(String tripNo, String time) {
        Trip trip = trip(tripNo);
        attempt(() -> {
            tripCommands.gateIn(trip.id(), trip.originTerminalId(), at(time), null);
            return null;
        });
    }

    @When("trip {string} is weighed {int} kg empty")
    public void weighEmpty(String tripNo, int weightKg) {
        weigh(tripNo, WeighbridgeReading.ReadingType.TARE, weightKg);
    }

    @When("trip {string} is weighed {int} kg laden")
    public void weighLaden(String tripNo, int weightKg) {
        weigh(tripNo, WeighbridgeReading.ReadingType.GROSS, weightKg);
    }

    @When("trip {string} is marked loaded")
    public void markLoaded(String tripNo) {
        attempt(() -> {
            tripCommands.markLoaded(world.ref("trip:" + tripNo), null);
            return null;
        });
    }

    // One expression per keyword annotation, but two expressions on one method
    // is fine -- "a" and "an" are genuinely different text, and Gherkin that
    // reads like English is the whole point of writing it in Gherkin.
    @When("trip {string} carries a {string} numbered {string}")
    @When("trip {string} carries an {string} numbered {string}")
    public void attachDocument(String tripNo, String documentType, String reference) {
        attempt(() -> tripCommands.attachDocument(world.ref("trip:" + tripNo),
                documentType, reference));
    }

    @When("trip {string} is dispatched at {word}")
    public void dispatch(String tripNo, String time) {
        attempt(() -> {
            tripCommands.dispatch(world.ref("trip:" + tripNo), at(time));
            return null;
        });
    }

    // ------------------------------------------------------------------- then

    @Then("trip {string} is {string}")
    public void tripStatusIs(String tripNo, String expected) {
        assertThat(trip(tripNo).status().name()).isEqualTo(expected);
    }

    @Then("trip {string} has an origin dwell of {int} minutes")
    public void tripDwell(String tripNo, int expectedMinutes) {
        assertThat(trip(tripNo).originDwell()).isNotNull();
        assertThat(trip(tripNo).originDwell().toMinutes()).isEqualTo(expectedMinutes);
    }

    @Then("trip {string} has a payload of {int} kg")
    public void tripPayload(String tripNo, int expectedKg) {
        assertThat(trip(tripNo).payloadWeightKg())
                .isEqualByComparingTo(BigDecimal.valueOf(expectedKg));
    }

    @Then("trip {string} has {int} gate events")
    public void tripGateEvents(String tripNo, int expected) {
        assertThat(inTransaction(() -> tripQueries.findGateLog(world.ref("trip:" + tripNo))))
                .hasSize(expected);
    }

    @Then("dispatch is refused mentioning {string}")
    public void dispatchRefused(String fragment) {
        assertRefusal(fragment);
    }

    // Not "assignment is refused ...": planning.feature already uses that for
    // assigning a consignment to a load. A duplicate expression across two step
    // classes aborts glue registration for a whole class, and the symptom is an
    // unrelated class's steps reporting as undefined.
    @Then("assigning the lorry is refused mentioning {string}")
    public void lorryAssignmentRefused(String fragment) {
        assertRefusal(fragment);
    }

    @Then("weighing is refused mentioning {string}")
    public void weighingRefused(String fragment) {
        assertRefusal(fragment);
    }

    @Then("loading is refused mentioning {string}")
    public void loadingRefused(String fragment) {
        assertRefusal(fragment);
    }

    @Then("raising the trip is refused mentioning {string}")
    public void raisingRefused(String fragment) {
        assertRefusal(fragment);
    }

    @Then("attaching the document is refused mentioning {string}")
    public void attachingRefused(String fragment) {
        assertRefusal(fragment);
    }

    // ---------------------------------------------------------------- helpers

    private void awardLoad(String loadNo, int weightKg, String originCode, String consigneeCode,
                           String destinationCode, String registration, String vendorCode,
                           boolean hazmat) {
        world.putRef("load:" + loadNo, fixtures.awardedLoad(world.orgUnitId(),
                world.uniqueCode(loadNo), world.ref("CUST"), world.ref(originCode),
                world.ref(consigneeCode), world.ref(destinationCode),
                world.ref("vehicle:" + registration), world.ref(vendorCode),
                BigDecimal.valueOf(weightKg), hazmat));
    }

    private void raise(String tripNo, String loadNo, Instant plannedStart) {
        attempt(() -> {
            UUID id = tripCommands.raiseTrip(world.uniqueCode(tripNo),
                    world.ref("load:" + loadNo), plannedStart);
            world.putRef("trip:" + tripNo, id);
            return id;
        });
    }

    private void weigh(String tripNo, WeighbridgeReading.ReadingType type, int weightKg) {
        Trip trip = trip(tripNo);
        attempt(() -> {
            tripCommands.recordWeighing(trip.id(), trip.originTerminalId(), type,
                    BigDecimal.valueOf(weightKg), null);
            return null;
        });
    }

    private Trip trip(String tripNo) {
        return inTransaction(() -> trips.findById(world.ref("trip:" + tripNo)).orElseThrow());
    }

    private void assertRefusal(String fragment) {
        assertThat(failure)
                .as("expected a refusal mentioning '%s' but the operation succeeded", fragment)
                .isInstanceOf(BusinessRuleViolationException.class);
        assertThat(failure.getMessage()).contains(fragment);
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
