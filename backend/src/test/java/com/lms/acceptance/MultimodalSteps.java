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
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.lms.execution.command.TripCommandService;
import com.lms.execution.command.domain.WeighbridgeReading;
import com.lms.masterdata.command.RegisterTerminalCommand;
import com.lms.masterdata.command.TerminalCommandService;
import com.lms.masterdata.command.domain.Terminal;
import com.lms.multimodal.command.ItineraryCommandService;
import com.lms.multimodal.command.PlanItineraryCommand;
import com.lms.multimodal.command.domain.ItineraryLeg;
import com.lms.multimodal.query.CarrierCostView;
import com.lms.multimodal.query.ItineraryQueryService;
import com.lms.multimodal.query.ItineraryView;
import com.lms.multimodal.query.LegView;
import com.lms.shared.error.BusinessRuleViolationException;
import com.lms.shared.geo.LatLon;
import io.cucumber.datatable.DataTable;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/** Step definitions for {@code multimodal.feature}. */
public class MultimodalSteps {

    private static final BigDecimal TARE_KG = new BigDecimal("6000");

    @Autowired
    private ScenarioWorld world;
    @Autowired
    private SpineFixtures fixtures;
    @Autowired
    private TerminalCommandService terminals;
    @Autowired
    private TripCommandService tripCommands;
    @Autowired
    private ItineraryCommandService itineraries;
    @Autowired
    private ItineraryQueryService queries;

    private RuntimeException failure;
    private Instant missedAt;

    // ------------------------------------------------------------------ given

    /** Railheads are terminals like any other; a hub is the nearest category. */
    @Given("a railhead {string} at {double},{double}")
    public void aRailhead(String code, double lat, double lon) {
        world.putRef(code, terminals.register(new RegisterTerminalCommand.PointRadius(
                world.orgUnitId(), world.uniqueCode(code), code,
                Terminal.FunctionalCategory.HUB,
                new LatLon(lat, lon), 1500, 2, null, null, 45, List.of())));
    }

    /**
     * A trip raised and nominated but not yet at the gate -- the state in
     * which a road leg is planned around it.
     */
    @Given("a road trip {string} on lorry {string} driven by {string} from {string} to {string} "
            + "for {string}")
    public void aRoadTrip(String tripNo, String registration, String driver, String originCode,
                          String destinationCode, String vendorCode) {
        UUID loadId = fixtures.awardedLoad(world.orgUnitId(), world.uniqueCode("LOAD-" + tripNo),
                world.ref("CUST"), world.ref(originCode), world.ref("CONS-A"),
                world.ref(destinationCode), world.ref("vehicle:" + registration),
                world.ref(vendorCode), new BigDecimal("5000"), false);
        UUID tripId = tripCommands.raiseTrip(world.uniqueCode(tripNo), loadId, Instant.now());
        tripCommands.assign(tripId, world.ref("vehicle:" + registration),
                world.ref("driver:" + driver));
        world.putRef("trip:" + tripNo, tripId);
        world.putRef("origin:" + tripNo, world.ref(originCode));
    }

    /**
     * Times are hours before now, so a scenario reads as a timeline without
     * pinning a date that would rot.
     */
    @Given("shipment {string} is planned as an itinerary:")
    public void planned(String shipment, DataTable table) {
        failure = null;
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        List<ItineraryLeg.Plan> legs = new ArrayList<>();
        for (Map<String, String> row : table.asMaps()) {
            String trip = blank(row.get("trip"));
            String units = blank(row.get("units"));
            legs.add(new ItineraryLeg.Plan(
                    ItineraryLeg.Mode.valueOf(row.get("mode")),
                    world.ref(row.get("from")), world.ref(row.get("to")),
                    hoursAgo(now, row.get("departs h ago")), hoursAgo(now, row.get("arrives h ago")),
                    Integer.valueOf(row.get("dwell min")),
                    trip == null ? null : world.ref("trip:" + trip),
                    units == null ? null : ItineraryLeg.UnitType.valueOf(units.split(" ")[1]),
                    units == null ? null : Integer.valueOf(units.split(" ")[0]),
                    blank(row.get("booking")), null,
                    world.ref(row.get("carrier")), new BigDecimal(row.get("cost"))));
        }
        try {
            world.putRef("itinerary:" + shipment, itineraries.plan(
                    new PlanItineraryCommand(world.uniqueCode(shipment), "INR", legs)));
        } catch (RuntimeException e) {
            failure = e;
        }
    }

    // ------------------------------------------------------------------- when

    /** Gate, weighbridge, documents and dispatch, timed to the scenario. */
    @When("road trip {string} leaves its origin {int} hours ago")
    public void roadTripLeaves(String tripNo, int hours) {
        UUID tripId = world.ref("trip:" + tripNo);
        UUID origin = world.ref("origin:" + tripNo);
        Instant dispatchedAt = Instant.now().minus(hours, ChronoUnit.HOURS);
        Instant gateInAt = dispatchedAt.minus(60, ChronoUnit.MINUTES);

        tripCommands.gateIn(tripId, origin, gateInAt, null);
        tripCommands.recordWeighing(tripId, origin, WeighbridgeReading.ReadingType.TARE,
                TARE_KG, gateInAt);
        tripCommands.recordWeighing(tripId, origin, WeighbridgeReading.ReadingType.GROSS,
                TARE_KG.add(new BigDecimal("5000")), gateInAt);
        tripCommands.markLoaded(tripId, dispatchedAt.minus(1, ChronoUnit.MINUTES));
        tripCommands.attachDocument(tripId, "CONSIGNMENT_NOTE", "LR-" + tripNo);
        tripCommands.attachDocument(tripId, "EWAY_BILL", "EW-" + tripNo);
        tripCommands.dispatch(tripId, dispatchedAt);
    }

    @When("road trip {string} delivers {int} hours ago")
    public void roadTripDelivers(String tripNo, int hours) {
        UUID tripId = world.ref("trip:" + tripNo);
        Instant at = Instant.now().minus(hours, ChronoUnit.HOURS);
        tripCommands.beginTransit(tripId);
        tripCommands.arrive(tripId, at.minus(10, ChronoUnit.MINUTES));
        tripCommands.complete(tripId, at);
    }

    /** Replays the proof of delivery as the outbox would after a restart. */
    @When("the delivery of road trip {string} is heard again")
    public void deliveryHeardAgain(String tripNo) {
        UUID tripId = world.ref("trip:" + tripNo);
        LegView leg = legRunBy(tripId);
        itineraries.tripCompleted(tripId, leg.departedAt(), leg.arrivedAt());
    }

    @When("rail leg {int} of shipment {string} is reported {string} {int} hours ago")
    public void railMilestone(int seq, String shipment, String milestone, int hours) {
        failure = null;
        try {
            itineraries.recordRailMilestone(world.ref("itinerary:" + shipment), seq,
                    ItineraryLeg.Status.valueOf(milestone),
                    Instant.now().minus(hours, ChronoUnit.HOURS));
        } catch (RuntimeException e) {
            failure = e;
        }
    }

    @When("rail leg {int} of shipment {string} is reported {string}, {string} and {string} "
            + "at {int}, {int} and {int} hours ago")
    public void railMilestones(int seq, String shipment, String first, String second,
                               String third, int h1, int h2, int h3) {
        railMilestone(seq, shipment, first, h1);
        assertNoFailure();
        railMilestone(seq, shipment, second, h2);
        assertNoFailure();
        railMilestone(seq, shipment, third, h3);
        assertNoFailure();
    }

    // ------------------------------------------------------------------- then

    @Then("shipment {string} is {string}")
    public void shipmentIs(String shipment, String status) {
        assertThat(itinerary(shipment).status()).isEqualTo(status);
    }

    @Then("shipment {string} costs {int} in total over {int} legs")
    public void totalCost(String shipment, int cost, int legs) {
        ItineraryView view = itinerary(shipment);
        assertThat(view.totalCost()).isEqualByComparingTo(BigDecimal.valueOf(cost));
        assertThat(view.legCount()).isEqualTo(legs);
    }

    @Then("carrier {string} is owed {int} for shipment {string}")
    public void carrierOwed(String carrier, int amount, String shipment) {
        UUID carrierId = world.ref(carrier);
        CarrierCostView owed = itinerary(shipment).costByCarrier().stream()
                .filter(c -> c.carrierPartnerId().equals(carrierId))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Carrier " + carrier + " runs no leg"));
        assertThat(owed.totalCost()).isEqualByComparingTo(BigDecimal.valueOf(amount));
    }

    @Then("leg {int} of shipment {string} is {string}")
    public void legIs(int seq, String shipment, String status) {
        assertThat(leg(shipment, seq).status()).isEqualTo(status);
    }

    @Then("leg {int} of shipment {string} started early")
    public void startedEarly(int seq, String shipment) {
        assertThat(leg(shipment, seq).startedEarly()).isTrue();
    }

    @Then("leg {int} of shipment {string} started on time")
    public void startedOnTime(int seq, String shipment) {
        assertThat(leg(shipment, seq).startedEarly()).isFalse();
    }

    @Then("leg {int} of shipment {string} missed its connection and can start {int} hours ago "
            + "at the earliest")
    public void missedConnection(int seq, String shipment, int hours) {
        LegView leg = leg(shipment, seq);
        assertThat(leg.connectionMissedAt()).as("connection missed").isNotNull();
        Duration ago = Duration.between(leg.earliestStartAt(), Instant.now());
        assertThat(ago.toMinutes()).isCloseTo(hours * 60L, within(5L));
        assertThat(itinerary(shipment).connectionMissed()).isTrue();
        missedAt = leg.connectionMissedAt();
    }

    @Then("the missed connection on leg {int} of shipment {string} is recorded once")
    public void missedOnce(int seq, String shipment) {
        assertThat(leg(shipment, seq).connectionMissedAt()).isEqualTo(missedAt);
    }

    @Then("shipment {string} has no missed connection")
    public void noMissedConnection(String shipment) {
        ItineraryView view = itinerary(shipment);
        assertThat(view.connectionMissed()).isFalse();
        assertThat(view.legs()).allSatisfy(l -> assertThat(l.connectionMissedAt()).isNull());
    }

    @Then("the multimodal desk refuses mentioning {string}")
    public void refused(String fragment) {
        assertThat(failure).as("the multimodal desk should have refused").isNotNull();
        assertThat(failure.getMessage()).contains(fragment);
    }

    @Then("the multimodal desk refuses with code {string}")
    public void refusedWithCode(String code) {
        assertThat(failure).as("the multimodal desk should have refused")
                .isInstanceOf(BusinessRuleViolationException.class);
        assertThat(((BusinessRuleViolationException) failure).code()).isEqualTo(code);
    }

    // --------------------------------------------------------------- helpers

    private void assertNoFailure() {
        assertThat(failure).as("terminal event refused: %s",
                failure == null ? "" : failure.getMessage()).isNull();
    }

    private ItineraryView itinerary(String shipment) {
        UUID id = world.ref("itinerary:" + shipment);
        return queries.findById(id).orElseThrow(() -> new AssertionError("No " + shipment));
    }

    private LegView leg(String shipment, int seq) {
        return itinerary(shipment).legs().stream()
                .filter(l -> l.seq() == seq)
                .findFirst()
                .orElseThrow(() -> new AssertionError("No leg " + seq + " on " + shipment));
    }

    private LegView legRunBy(UUID tripId) {
        return queries.list(null, 200).items().stream()
                .flatMap(summary -> queries.findById(summary.id()).orElseThrow().legs().stream())
                .filter(l -> tripId.equals(l.tripId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No leg runs trip " + tripId));
    }

    private static Instant hoursAgo(Instant now, String hours) {
        return now.minus(Long.parseLong(hours.strip()), ChronoUnit.HOURS);
    }

    private static String blank(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
