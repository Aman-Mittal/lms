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
package com.lms.e2e;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

import com.lms.client.api.MilkRunsApi;
import com.lms.client.api.SettlementApi;
import com.lms.client.model.AssignCircuitTripRequest;
import com.lms.client.model.CircuitPickupRequest;
import com.lms.client.model.CircuitRun;
import com.lms.client.model.CircuitRunStop;
import com.lms.client.model.FreightBill;
import com.lms.client.model.GenerateCircuitRunsRequest;
import com.lms.client.model.MilestoneRequest;
import com.lms.client.model.MilkRunStopPlan;
import com.lms.client.model.PublishMilkRunRouteRequest;
import com.lms.client.model.RegisterPartnerRequest;
import com.lms.client.model.RegisterPointRadiusTerminalRequest;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import org.springframework.beans.factory.annotation.Value;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Milk runs over HTTP: a published route, a generated circuit, a carrying
 * trip, stops recorded by hand, completion, and the carrier payable finance
 * raises for the circuit.
 *
 * <p>Stops are recorded manually rather than by pings, which the contract
 * allows for a silent tracker and which keeps the scenario independent of
 * geofence timing. The route leaves six hours before the scenario starts, so
 * every recorded moment is in the past.
 */
public class MilkRunCircuitSteps {

    private static final int DEPARTED_HOURS_AGO = 6;

    @Value("${local.server.port}")
    private int port;

    private HttpWorld world;
    private UUID firstSupplier;
    private UUID secondSupplier;
    private UUID plant;
    private UUID vendor;
    private UUID customer;
    private UUID consignee;
    private UUID tripId;
    private String routeCode;
    private LocalDate runDate;
    private UUID circuitId;
    private OffsetDateTime departure;

    @Given("the demo administrator is signed in at the milk-run desk")
    public void signIn() {
        world = new HttpWorld(port);
        world.signInAsDemoAdministrator();
    }

    @Given("two supplier sites, a plant and an active carrier are registered for the milk run")
    public void network() {
        var site = RegisterPointRadiusTerminalRequest.CategoryEnum.CUSTOMER_SITE;
        firstSupplier = world.terminal(site, "SUP-1", 15.10, 72.90);
        secondSupplier = world.terminal(site, "SUP-2", 15.15, 73.00);
        plant = world.terminal(RegisterPointRadiusTerminalRequest.CategoryEnum.PLANT,
                "MR-PLANT", 15.30, 73.10);
        vendor = world.activeVendor("MRV");
        customer = world.partner("MRC", RegisterPartnerRequest.PartnerTypeEnum.CUSTOMER);
        consignee = world.partner("MRN", RegisterPartnerRequest.PartnerTypeEnum.CONSIGNEE);
    }

    @Given("a daily milk-run route is published at {int} per run and {int} per stop calling at both suppliers")
    public void publishRoute(int runRate, int stopFee) {
        departure = OffsetDateTime.now(ZoneOffset.UTC).minusHours(DEPARTED_HOURS_AGO);
        runDate = departure.toLocalDate();
        routeCode = world.code("MR");
        world.api(MilkRunsApi.class).publishMilkRunRoute(new PublishMilkRunRouteRequest()
                .code(routeCode)
                .name("Northern collection " + world.salt)
                .plantTerminalId(plant)
                .effectiveFrom(runDate.minusDays(2))
                .days(List.of(PublishMilkRunRouteRequest.DaysEnum.values()))
                .departureTime(departure.format(DateTimeFormatter.ofPattern("HH:mm")))
                .timeZone("UTC")
                .runRate(BigDecimal.valueOf(runRate))
                .perStopFee(BigDecimal.valueOf(stopFee))
                .stops(List.of(
                        new MilkRunStopPlan().terminalId(firstSupplier)
                                .windowOpenMin(0).windowCloseMin(30).plannedDwellMin(15),
                        new MilkRunStopPlan().terminalId(secondSupplier)
                                .windowOpenMin(45).windowCloseMin(90).plannedDwellMin(15))), null);
    }

    @When("the circuit for that day is generated")
    public void generate() {
        var result = world.api(MilkRunsApi.class).generateCircuitRuns(null,
                new GenerateCircuitRunsRequest().fromDate(runDate).days(1));
        assertThat(result.getGenerated()).isGreaterThanOrEqualTo(1);
        CircuitRun run = world.api(MilkRunsApi.class)
                .listCircuitRuns(runDate, routeCode, null, null, 10).getItems().stream()
                .findFirst().orElseThrow(() -> new AssertionError("no circuit was generated"));
        circuitId = run.getId();
        departure = run.getPlannedDeparture();
        assertThat(run.getStatus()).isEqualTo(CircuitRun.StatusEnum.PLANNED);
    }

    @When("a trip from the first supplier to the plant is dispatched and carries the circuit")
    public void carrier() {
        UUID lorry = world.lorry("MR", 20000);
        UUID driver = world.driver("MR");
        tripId = world.assignedTrip("MR", customer, consignee, firstSupplier, plant, vendor,
                lorry, driver, 5000);
        world.dispatch(tripId, firstSupplier, 5000, departure.plusMinutes(1));
        world.api(MilkRunsApi.class).assignCircuitTrip(circuitId,
                new AssignCircuitTripRequest().tripId(tripId), null);
    }

    @When("both suppliers are reached and left within their windows collecting {int} kg at the first")
    public void stops(int kilos) {
        MilkRunsApi runs = world.api(MilkRunsApi.class);
        runs.recordCircuitStopArrival(circuitId, 1, null,
                new MilestoneRequest().at(departure.plusMinutes(5)));
        runs.recordCircuitStopPickup(circuitId, 1,
                new CircuitPickupRequest().quantityKg(BigDecimal.valueOf(kilos)), null);
        runs.recordCircuitStopDeparture(circuitId, 1, null,
                new MilestoneRequest().at(departure.plusMinutes(25)));
        runs.recordCircuitStopArrival(circuitId, 2, null,
                new MilestoneRequest().at(departure.plusMinutes(50)));
        runs.recordCircuitStopDeparture(circuitId, 2, null,
                new MilestoneRequest().at(departure.plusMinutes(70)));
    }

    @When("the circuit is recorded as reaching the plant")
    public void completion() {
        world.api(MilkRunsApi.class).completeCircuitRun(circuitId, null,
                new MilestoneRequest().at(departure.plusMinutes(120)));
    }

    @Then("the circuit is completed having visited {int} suppliers and skipped none")
    public void completed(int visited) {
        CircuitRun run = world.api(MilkRunsApi.class).getCircuitRun(circuitId);
        assertThat(run.getStatus()).isEqualTo(CircuitRun.StatusEnum.COMPLETED);
        assertThat(run.getStopsVisited()).isEqualTo(visited);
        assertThat(run.getStopsSkipped()).isZero();
        List<CircuitRunStop> stops = world.api(MilkRunsApi.class).getCircuitRunStops(circuitId);
        assertThat(stops).extracting(CircuitRunStop::getStatus)
                .containsOnly(CircuitRunStop.StatusEnum.DEPARTED);
    }

    @Then("the circuit is charged {int} and collected {int} kg")
    public void charged(int charge, int kilos) {
        CircuitRun run = world.api(MilkRunsApi.class).getCircuitRun(circuitId);
        assertThat(run.getChargeAmount()).isEqualByComparingTo(BigDecimal.valueOf(charge));
        assertThat(run.getTotalPickedUpKg()).isEqualByComparingTo(BigDecimal.valueOf(kilos));
    }

    @Then("a freight bill of {int} is raised for the circuit as a milk-run payable")
    public void billRaised(int amount) {
        FreightBill bill = HttpWorld.eventually("the circuit's freight bill", () -> {
            SettlementApi settlement = world.api(SettlementApi.class);
            return settlement.listFreightBills(null, null, 200).getItems().stream()
                    .filter(b -> circuitId.equals(b.getCircuitRunId()))
                    .findFirst()
                    .map(b -> settlement.getFreightBill(b.getId()))
                    .orElseThrow(() -> new AssertionError("no bill for the circuit"));
        });
        assertThat(bill.getSource()).isEqualTo(FreightBill.SourceEnum.MILK_RUN);
        assertThat(bill.getRouteCode()).isEqualTo(routeCode);
        assertThat(bill.getComputedAmount()).isEqualByComparingTo(BigDecimal.valueOf(amount));
        assertThat(bill.getStatus()).isEqualTo(FreightBill.StatusEnum.DRAFT);
    }
}
