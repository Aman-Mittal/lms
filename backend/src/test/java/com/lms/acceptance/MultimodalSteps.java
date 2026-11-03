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
import java.time.LocalDate;
import java.time.ZoneOffset;
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
import com.lms.multimodal.command.MissedConnectionSweep;
import com.lms.multimodal.command.PlanItineraryCommand;
import com.lms.multimodal.command.PublishRailTariffCommand;
import com.lms.multimodal.command.RailTariffCommandService;
import com.lms.multimodal.command.domain.ItineraryLeg;
import com.lms.multimodal.events.ConnectionMissed;
import com.lms.multimodal.query.CarrierCostView;
import com.lms.multimodal.query.ItineraryQueryService;
import com.lms.multimodal.query.ItineraryView;
import com.lms.multimodal.query.LegView;
import com.lms.multimodal.query.RailTariffQueryService;
import com.lms.multimodal.query.RailTariffView;
import com.lms.shared.error.BusinessRuleViolationException;
import com.lms.shared.geo.LatLon;
import io.cucumber.datatable.DataTable;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import org.springframework.security.access.AccessDeniedException;
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
    @Autowired
    private RailTariffCommandService railTariffs;
    @Autowired
    private RailTariffQueryService railTariffQueries;
    @Autowired
    private MissedConnectionSweep sweep;
    @Autowired
    private MultimodalEventProbe announced;

    private RuntimeException failure;
    private Instant missedAt;

    // ------------------------------------------------------------------ given

    /** A terminal of the RAILHEAD category, where rail legs start and end. */
    @Given("a railhead {string} at {double},{double}")
    public void aRailhead(String code, double lat, double lon) {
        world.putRef(code, terminals.register(new RegisterTerminalCommand.PointRadius(
                world.orgUnitId(), world.uniqueCode(code), code,
                Terminal.FunctionalCategory.RAILHEAD,
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
                    world.ref(row.get("carrier")),
                    blank(row.get("cost")) == null ? null : new BigDecimal(row.get("cost").strip())));
        }
        try {
            world.putRef("itinerary:" + shipment, itineraries.plan(
                    new PlanItineraryCommand(world.uniqueCode(shipment), "INR", legs)));
        } catch (RuntimeException e) {
            failure = e;
        }
    }

    /**
     * Days are counted from today in UTC, as every tariff date is; a dash is a
     * unit the card does not price.
     */
    @When("rail rate card {string} is published for {string} from {string} to {string} "
            + "from day {int} at {string} per wagon, {string} per container, minimum {int}")
    public void railRateCard(String code, String carrier, String from, String to, int day,
                             String perWagon, String perContainer, int minimum) {
        failure = null;
        try {
            world.putRef("rail-tariff:" + code, railTariffs.publish(new PublishRailTariffCommand(
                    world.uniqueCode(code), world.ref(carrier), world.ref(from), world.ref(to),
                    "INR", amount(perWagon), amount(perContainer), BigDecimal.valueOf(minimum),
                    today().plusDays(day))));
        } catch (RuntimeException e) {
            failure = e;
        }
    }

    // ------------------------------------------------------------------- when

    @Given("a railhead {string} at {double},{double} under {string}")
    public void aRailheadUnder(String code, double lat, double lon, String orgPath) {
        world.putRef(code, terminals.register(new RegisterTerminalCommand.PointRadius(
                world.orgUnitId(orgPath), world.uniqueCode(code), code,
                Terminal.FunctionalCategory.RAILHEAD,
                new LatLon(lat, lon), 1500, 2, null, null, 45, List.of())));
    }

    @When("the missed-connection sweep runs")
    public void sweepRuns() {
        sweep.sweep();
    }

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

    @Then("leg {int} of shipment {string} missed its connection with leg {int} not yet arrived")
    public void missedWithNoArrival(int seq, String shipment, int predecessor) {
        LegView leg = leg(shipment, seq);
        assertThat(leg(shipment, predecessor).arrivedAt()).as("leg %s arrived", predecessor)
                .isNull();
        assertThat(leg.connectionMissedAt()).as("connection missed").isNotNull();
        // Nothing has arrived, so the earliest start is a bound: arriving at
        // the moment of detection and transferring.
        assertThat(leg.earliestStartAt()).isEqualTo(
                leg.connectionMissedAt().plus(leg.transferDwellMinutes(), ChronoUnit.MINUTES));
    }

    @Then("leg {int} of shipment {string} has not missed its connection")
    public void notMissed(int seq, String shipment) {
        assertThat(leg(shipment, seq).connectionMissedAt()).isNull();
    }

    @Then("the missed connection on leg {int} of shipment {string} was announced once")
    public void announcedOnce(int seq, String shipment) {
        UUID legId = leg(shipment, seq).id();
        List<ConnectionMissed> events = announced.missed().stream()
                .filter(event -> event.legId().equals(legId))
                .toList();
        assertThat(events).hasSize(1);
    }

    @Then("leg {int} of shipment {string} costs {int}, priced by rail rate card {string}")
    public void pricedByCard(int seq, String shipment, int cost, String card) {
        LegView leg = leg(shipment, seq);
        assertThat(leg.costAmount()).isEqualByComparingTo(BigDecimal.valueOf(cost));
        assertThat(leg.railTariffId()).isEqualTo(world.ref("rail-tariff:" + card));
    }

    @Then("leg {int} of shipment {string} costs {int} as given, priced by no card")
    public void pricedAsGiven(int seq, String shipment, int cost) {
        LegView leg = leg(shipment, seq);
        assertThat(leg.costAmount()).isEqualByComparingTo(BigDecimal.valueOf(cost));
        assertThat(leg.railTariffId()).isNull();
    }

    @Then("rail rate card {string} is in force until day {int}")
    public void cardClosed(String code, int day) {
        assertThat(railTariff(code).effectiveTo()).isEqualTo(today().plusDays(day));
    }

    @Then("rail rate card {string} is in force with no end")
    public void cardOpen(String code) {
        assertThat(railTariff(code).effectiveTo()).isNull();
    }

    @Then("the multimodal desk refuses mentioning {string}")
    public void refused(String fragment) {
        assertThat(failure).as("the multimodal desk should have refused").isNotNull();
        assertThat(failure.getMessage()).contains(fragment);
    }

    @Then("the multimodal desk refuses access")
    public void refusedAccess() {
        assertThat(failure).as("the multimodal desk should have refused access")
                .isInstanceOf(AccessDeniedException.class);
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

    private RailTariffView railTariff(String code) {
        UUID id = world.ref("rail-tariff:" + code);
        return railTariffQueries.findById(id).orElseThrow(() -> new AssertionError("No " + code));
    }

    private static LocalDate today() {
        return LocalDate.now(ZoneOffset.UTC);
    }

    private static BigDecimal amount(String value) {
        return "-".equals(value.strip()) ? null : new BigDecimal(value.strip());
    }

    private static Instant hoursAgo(Instant now, String hours) {
        return now.minus(Long.parseLong(hours.strip()), ChronoUnit.HOURS);
    }

    private static String blank(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
