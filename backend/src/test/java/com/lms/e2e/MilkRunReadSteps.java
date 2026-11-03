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

import com.lms.client.api.ControlTowerApi;
import com.lms.client.api.MilkRunsApi;
import com.lms.client.model.AssignCircuitTripRequest;
import com.lms.client.model.AuthResult;
import com.lms.client.model.CircuitPickupRequest;
import com.lms.client.model.CircuitRun;
import com.lms.client.model.CircuitRunStop;
import com.lms.client.model.CircuitSkipRequest;
import com.lms.client.model.Exception;
import com.lms.client.model.GenerateCircuitRunsRequest;
import com.lms.client.model.MilestoneRequest;
import com.lms.client.model.MilkRunRoute;
import com.lms.client.model.MilkRunRouteSlice;
import com.lms.client.model.MilkRunRouteStop;
import com.lms.client.model.MilkRunStopPlan;
import com.lms.client.model.PublishMilkRunRouteRequest;
import com.lms.client.model.RegisterPartnerRequest;
import com.lms.client.model.RegisterPointRadiusTerminalRequest;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Milk-run routes and skipped stops over HTTP: route versions read by id,
 * listed by code and paged, their stops in circuit order, and a stop of a
 * generated circuit skipped with a reason, which the control tower raises as a
 * ticket and the circuit's outcome and price reflect.
 *
 * <p>Latitude band 56. The circuit leaves six hours before the scenario
 * starts, as the other milk-run scenario does, so every recorded moment is in
 * the past.
 */
public class MilkRunReadSteps {

    private static final int RUN_RATE = 2000;
    private static final int STOP_FEE = 300;
    private static final String REASON = "Supplier closed for stock-take";

    @Value("${local.server.port}")
    private int port;
    @Autowired
    private JdbcClient jdbc;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private PlatformTransactionManager transactionManager;

    private HttpWorld world;
    private MovementSupport support;
    private LocalDate today;
    private UUID firstSupplier;
    private UUID secondSupplier;
    private UUID plant;
    private UUID vendor;
    private UUID customer;
    private UUID consignee;
    private String codeA;
    private String codeB;
    private UUID routeAv1;
    private UUID routeAv2;
    private UUID routeB;

    private String circuitRouteCode;
    private LocalDate runDate;
    private OffsetDateTime departure;
    private UUID circuitId;
    private UUID tripId;
    private AuthResult otherTenant;

    @Given("the demo administrator is signed in at the milk-run route desk")
    public void signIn() {
        world = new HttpWorld(port);
        world.signInAsDemoAdministrator();
        support = new MovementSupport(world, port, jdbc, passwordEncoder, transactionManager);
        today = LocalDate.now(ZoneOffset.UTC);
    }

    @Given("two supplier sites, a plant and an active carrier are registered for the route reads")
    public void network() {
        var site = RegisterPointRadiusTerminalRequest.CategoryEnum.CUSTOMER_SITE;
        firstSupplier = world.terminal(site, "MV-SUP-1", 56.10, 72.90);
        secondSupplier = world.terminal(site, "MV-SUP-2", 56.15, 73.00);
        plant = world.terminal(RegisterPointRadiusTerminalRequest.CategoryEnum.PLANT,
                "MV-PLANT", 56.30, 73.10);
        vendor = world.activeVendor("MVV");
        customer = world.partner("MVC", RegisterPartnerRequest.PartnerTypeEnum.CUSTOMER);
        consignee = world.partner("MVN", RegisterPartnerRequest.PartnerTypeEnum.CONSIGNEE);
    }

    // ----------------------------------------------------------- the routes

    @When("a daily route and a Monday-Wednesday-Friday route are published, and the daily one gets a successor from today")
    public void publishRoutes() {
        codeA = "MV-" + world.salt + "-A";
        codeB = "MV-" + world.salt + "-B";
        MilkRunsApi runs = world.api(MilkRunsApi.class);
        routeAv1 = runs.publishMilkRunRoute(request(codeA, today.minusDays(5),
                List.of(PublishMilkRunRouteRequest.DaysEnum.values()), "06:30", "UTC",
                2500, 400, List.of(stop(firstSupplier, 0, 30, 15), stop(secondSupplier, 45, 90, 20))),
                null).getId();
        routeB = runs.publishMilkRunRoute(request(codeB, today.minusDays(5),
                List.of(PublishMilkRunRouteRequest.DaysEnum.FRI,
                        PublishMilkRunRouteRequest.DaysEnum.MON,
                        PublishMilkRunRouteRequest.DaysEnum.WED), "22:15", "Asia/Kolkata",
                1800, 250, List.of(stop(firstSupplier, 10, 40, 25))), null).getId();
        routeAv2 = runs.publishMilkRunRoute(request(codeA, today,
                List.of(PublishMilkRunRouteRequest.DaysEnum.values()), "06:45", "UTC",
                2700, 450, List.of(stop(secondSupplier, 5, 35, 10), stop(firstSupplier, 50, 95, 30))),
                null).getId();
    }

    @Then("each route reads back by id with its plant, schedule, price and validity")
    public void routesReadBack() {
        MilkRunsApi runs = world.api(MilkRunsApi.class);
        MilkRunRoute v1 = runs.getMilkRunRoute(routeAv1);
        assertThat(v1.getId()).isEqualTo(routeAv1);
        assertThat(v1.getCode()).isEqualTo(codeA);
        assertThat(v1.getName()).isEqualTo("Collection " + codeA);
        assertThat(v1.getPlantTerminalId()).isEqualTo(plant);
        assertThat(v1.getPlantCode()).isEqualTo("MV-PLANT-" + world.salt);
        assertThat(v1.getDaysOfWeek()).isEqualTo("MON,TUE,WED,THU,FRI,SAT,SUN");
        assertThat(v1.getDepartureTime()).startsWith("06:30");
        assertThat(v1.getTimeZone()).isEqualTo("UTC");
        assertThat(v1.getRunRate()).isEqualByComparingTo("2500");
        assertThat(v1.getPerStopFee()).isEqualByComparingTo("400");
        assertThat(v1.getCurrency()).isEqualTo("INR");
        assertThat(v1.getEffectiveFrom()).isEqualTo(today.minusDays(5));
        assertThat(v1.getCreatedAt()).isNotNull();

        MilkRunRoute b = runs.getMilkRunRoute(routeB);
        assertThat(b.getCode()).isEqualTo(codeB);
        assertThat(b.getDaysOfWeek()).as("canonical, Monday first").isEqualTo("MON,WED,FRI");
        assertThat(b.getDepartureTime()).startsWith("22:15");
        assertThat(b.getTimeZone()).isEqualTo("Asia/Kolkata");
        assertThat(b.getRunRate()).isEqualByComparingTo("1800");
        assertThat(b.getPerStopFee()).isEqualByComparingTo("250");
        assertThat(b.getEffectiveTo()).isNull();
    }

    @Then("the successor closed the first version the day before and is itself in force")
    public void successorInForce() {
        MilkRunsApi runs = world.api(MilkRunsApi.class);
        MilkRunRoute v1 = runs.getMilkRunRoute(routeAv1);
        MilkRunRoute v2 = runs.getMilkRunRoute(routeAv2);
        assertThat(v1.getEffectiveTo()).isEqualTo(today.minusDays(1));
        assertThat(v2.getCode()).isEqualTo(codeA);
        assertThat(v2.getEffectiveFrom()).isEqualTo(today);
        assertThat(v2.getEffectiveTo()).isNull();
        assertThat(v2.getDepartureTime()).startsWith("06:45");
        assertThat(v2.getRunRate()).isEqualByComparingTo("2700");
    }

    @Then("the stops of each version read back in circuit order with their windows and terminals")
    public void stopsReadBack() {
        MilkRunsApi runs = world.api(MilkRunsApi.class);
        List<MilkRunRouteStop> first = runs.getMilkRunRouteStops(routeAv1);
        assertThat(first).extracting(MilkRunRouteStop::getSequence).containsExactly(1, 2);
        assertThat(first).extracting(MilkRunRouteStop::getTerminalId)
                .containsExactly(firstSupplier, secondSupplier);
        assertThat(first).extracting(MilkRunRouteStop::getTerminalCode)
                .containsExactly("MV-SUP-1-" + world.salt, "MV-SUP-2-" + world.salt);
        assertThat(first).extracting(MilkRunRouteStop::getTerminalName)
                .containsExactly("MV-SUP-1", "MV-SUP-2");
        assertThat(first).extracting(MilkRunRouteStop::getWindowOpenMin).containsExactly(0, 45);
        assertThat(first).extracting(MilkRunRouteStop::getWindowCloseMin).containsExactly(30, 90);
        assertThat(first).extracting(MilkRunRouteStop::getPlannedDwellMin).containsExactly(15, 20);

        // The successor's stops are its own: other order, other windows.
        List<MilkRunRouteStop> second = runs.getMilkRunRouteStops(routeAv2);
        assertThat(second).extracting(MilkRunRouteStop::getTerminalId)
                .containsExactly(secondSupplier, firstSupplier);
        assertThat(second).extracting(MilkRunRouteStop::getWindowOpenMin).containsExactly(5, 50);
        assertThat(second).extracting(MilkRunRouteStop::getPlannedDwellMin).containsExactly(10, 30);

        assertThat(runs.getMilkRunRouteStops(routeB)).hasSize(1);
        assertThat(runs.getMilkRunRouteStops(routeB).get(0).getTerminalId()).isEqualTo(firstSupplier);
    }

    @Then("the route list holds the versions in force by code and not the superseded one, and pages by cursor")
    public void routeList() {
        MilkRunsApi runs = world.api(MilkRunsApi.class);
        String before = "MV-" + world.salt + "-";
        MilkRunRouteSlice all = runs.listMilkRunRoutes(before, 100);
        List<MilkRunRoute> mine = all.getItems().stream()
                .filter(r -> r.getCode().startsWith(before)).toList();
        assertThat(mine).extracting(MilkRunRoute::getId).containsExactly(routeAv2, routeB);
        assertThat(mine).extracting(MilkRunRoute::getCode).containsExactly(codeA, codeB);
        assertThat(mine).extracting(MilkRunRoute::getEffectiveTo).containsOnlyNulls();
        assertThat(all.getItems()).extracting(MilkRunRoute::getId).doesNotContain(routeAv1);

        MilkRunRouteSlice page = runs.listMilkRunRoutes(before, 1);
        assertThat(page.getItems()).extracting(MilkRunRoute::getId).containsExactly(routeAv2);
        assertThat(page.getHasMore()).isTrue();
        assertThat(page.getNextCursor()).isEqualTo(codeA);
        MilkRunRouteSlice next = runs.listMilkRunRoutes(page.getNextCursor(), 1);
        assertThat(next.getItems()).extracting(MilkRunRoute::getId).containsExactly(routeB);
    }

    @Then("routes that break the rules are refused with their codes and change nothing")
    public void ruleBreakers() {
        MilkRunsApi runs = world.api(MilkRunsApi.class);
        List<MilkRunRouteStop> stopsBefore = runs.getMilkRunRouteStops(routeAv2);
        List<PublishMilkRunRouteRequest.DaysEnum> daily =
                List.of(PublishMilkRunRouteRequest.DaysEnum.values());
        var oneStop = List.of(stop(firstSupplier, 0, 30, 15));

        MovementSupport.assertRefused(() -> runs.publishMilkRunRoute(request(codeA, today,
                daily, "07:00", "UTC", 100, 10, oneStop), null), 409, "milk-run-route-backdated");
        MovementSupport.assertRefused(() -> runs.publishMilkRunRoute(request(codeA,
                today.minusDays(2), daily, "07:00", "UTC", 100, 10, oneStop), null),
                409, "milk-run-route-backdated");
        MovementSupport.assertRefused(() -> runs.publishMilkRunRoute(request(world.code("MVX"),
                today, daily, "07:00", "UTC", 100, 10,
                List.of(stop(plant, 0, 30, 15))), null), 409, "milk-run-plant-as-stop");
        MovementSupport.assertRefused(() -> runs.publishMilkRunRoute(request(world.code("MVX"),
                today, daily, "07:00", "UTC", 100, 10, List.of(stop(firstSupplier, 0, 30, 15),
                        stop(firstSupplier, 40, 60, 15))), null), 409, "milk-run-supplier-repeated");
        MovementSupport.assertRefused(() -> runs.publishMilkRunRoute(request(world.code("MVX"),
                today, daily, "07:00", "UTC", 100, 10, List.of(stop(firstSupplier, 40, 60, 15),
                        stop(secondSupplier, 10, 30, 15))), null), 409, "milk-run-windows-out-of-order");
        MovementSupport.assertRefused(() -> runs.publishMilkRunRoute(request(world.code("MVX"),
                today, daily, "07:00", "UTC", 100, 10, List.of(stop(UUID.randomUUID(), 0, 30, 15))),
                null), 409, "milk-run-unknown-terminal");
        MovementSupport.assertRefused(() -> runs.publishMilkRunRoute(request(world.code("MVX"),
                today, daily, "07:00", "Mars/Olympus", 100, 10, oneStop), null),
                400, "invalid-argument");

        assertThat(runs.getMilkRunRoute(routeAv2).getEffectiveTo()).isNull();
        assertThat(runs.getMilkRunRouteStops(routeAv2)).usingRecursiveComparison()
                .isEqualTo(stopsBefore);
        assertThat(runs.listMilkRunRoutes("MV-" + world.salt + "-", 100).getItems().stream()
                .filter(r -> r.getCode().startsWith("MV-" + world.salt + "-")).toList()).hasSize(2);
    }

    @Then("a route with no stops, no code or a negative rate is refused as invalid with a code")
    public void invalidRoutes() {
        MilkRunsApi runs = world.api(MilkRunsApi.class);
        List<PublishMilkRunRouteRequest.DaysEnum> daily =
                List.of(PublishMilkRunRouteRequest.DaysEnum.values());
        var oneStop = List.of(stop(firstSupplier, 0, 30, 15));
        MovementSupport.assertRefused(() -> runs.publishMilkRunRoute(request(world.code("MVX"),
                today, daily, "07:00", "UTC", 100, 10, List.of()), null), 400, "validation-failed");
        MovementSupport.assertRefused(() -> runs.publishMilkRunRoute(request("", today, daily,
                "07:00", "UTC", 100, 10, oneStop), null), 400, "validation-failed");
        MovementSupport.assertRefused(() -> runs.publishMilkRunRoute(request(world.code("MVX"),
                today, daily, "07:00", "UTC", -5, 10, oneStop), null), 400, "validation-failed");
        MovementSupport.assertRefused(() -> runs.publishMilkRunRoute(request(world.code("MVX"),
                today, List.of(), "07:00", "UTC", 100, 10, oneStop), null),
                400, "validation-failed");
    }

    @Then("a route that is not there is answered as not found and has no stops")
    public void unknownRoute() {
        MilkRunsApi runs = world.api(MilkRunsApi.class);
        UUID nobody = UUID.randomUUID();
        MovementSupport.assertRefused(() -> runs.getMilkRunRoute(nobody), 404, "not-found");
        assertThat(runs.getMilkRunRouteStops(nobody)).isEmpty();
    }

    @Then("callers without the read or plan permission are refused as forbidden")
    public void routePermissions() {
        AuthResult nobody = support.callerWith("mvnone", "TRIP_READ");
        AuthResult reader = support.callerWith("mvreader", "MILKRUN_READ");
        MilkRunsApi blind = support.as(nobody, MilkRunsApi.class);
        MovementSupport.assertRefused(() -> blind.getMilkRunRoute(routeAv2), 403, "access-denied");
        MovementSupport.assertRefused(() -> blind.getMilkRunRouteStops(routeAv2), 403, "access-denied");
        MovementSupport.assertRefused(() -> blind.listMilkRunRoutes(null, 5), 403, "access-denied");

        MilkRunsApi readOnly = support.as(reader, MilkRunsApi.class);
        assertThat(readOnly.getMilkRunRoute(routeAv2).getCode()).isEqualTo(codeA);
        assertThat(readOnly.getMilkRunRouteStops(routeAv2)).hasSize(2);
        MovementSupport.assertRefused(() -> readOnly.publishMilkRunRoute(request(world.code("MVF"),
                today, List.of(PublishMilkRunRouteRequest.DaysEnum.MON), "07:00", "UTC", 100, 10,
                List.of(stop(firstSupplier, 0, 30, 15))), null), 403, "access-denied");
    }

    @Then("another tenant's administrator cannot read the routes or their stops and cannot use these terminals")
    public void routeIsolation() {
        otherTenant = support.otherTenantAdministrator();
        MilkRunsApi theirs = support.as(otherTenant, MilkRunsApi.class);
        MovementSupport.assertRefused(() -> theirs.getMilkRunRoute(routeAv2), 404, "not-found");
        MovementSupport.assertRefused(() -> theirs.getMilkRunRoute(routeAv1), 404, "not-found");
        assertThat(theirs.getMilkRunRouteStops(routeAv2)).isEmpty();
        assertThat(theirs.listMilkRunRoutes(null, 100).getItems()).extracting(MilkRunRoute::getId)
                .doesNotContain(routeAv2, routeB);
        MovementSupport.assertRefused(() -> theirs.publishMilkRunRoute(request(world.code("MVO"),
                today, List.of(PublishMilkRunRouteRequest.DaysEnum.MON), "07:00", "UTC", 100, 10,
                List.of(stop(firstSupplier, 0, 30, 15))), null), 409, "milk-run-unknown-terminal");
    }

    // ------------------------------------------------------ the skipped stop

    @Given("a daily route is published calling at both suppliers and its circuit for the day is generated")
    public void circuit() {
        departure = OffsetDateTime.now(ZoneOffset.UTC).minusHours(6);
        runDate = departure.toLocalDate();
        circuitRouteCode = world.code("MVS");
        world.api(MilkRunsApi.class).publishMilkRunRoute(new PublishMilkRunRouteRequest()
                .code(circuitRouteCode)
                .name("Skips " + world.salt)
                .plantTerminalId(plant)
                .effectiveFrom(runDate.minusDays(2))
                .days(List.of(PublishMilkRunRouteRequest.DaysEnum.values()))
                .departureTime(departure.format(DateTimeFormatter.ofPattern("HH:mm")))
                .timeZone("UTC")
                .runRate(BigDecimal.valueOf(RUN_RATE))
                .perStopFee(BigDecimal.valueOf(STOP_FEE))
                .stops(List.of(stop(firstSupplier, 0, 30, 15), stop(secondSupplier, 45, 90, 15))),
                null);
        MilkRunsApi runs = world.api(MilkRunsApi.class);
        assertThat(runs.generateCircuitRuns(null,
                new GenerateCircuitRunsRequest().fromDate(runDate).days(1)).getGenerated())
                .isGreaterThanOrEqualTo(1);
        CircuitRun run = runs.listCircuitRuns(runDate, circuitRouteCode, null, null, 10).getItems()
                .stream().findFirst().orElseThrow(() -> new AssertionError("no circuit generated"));
        circuitId = run.getId();
        departure = run.getPlannedDeparture();
        assertThat(run.getStatus()).isEqualTo(CircuitRun.StatusEnum.PLANNED);
    }

    @Then("skipping a stop of the circuit before it has started is refused as a conflict and changes nothing")
    public void skipBeforeStart() {
        MovementSupport.assertRefused(() -> skip(circuitId, 2, REASON), 409,
                "circuit-run-not-in-progress");
        assertThat(stops()).extracting(CircuitRunStop::getStatus)
                .containsOnly(CircuitRunStop.StatusEnum.PENDING);
    }

    @When("a dispatched trip carries the circuit")
    public void carry() {
        UUID lorry = world.lorry("MS", 20000);
        UUID driver = world.driver("MS");
        tripId = world.assignedTrip("MS", customer, consignee, firstSupplier, plant, vendor,
                lorry, driver, 5000);
        world.dispatch(tripId, firstSupplier, 5000, departure.plusMinutes(1));
        world.api(MilkRunsApi.class).assignCircuitTrip(circuitId,
                new AssignCircuitTripRequest().tripId(tripId), null);
        assertThat(circuitRun().getStatus()).isEqualTo(CircuitRun.StatusEnum.IN_PROGRESS);
    }

    @Then("a skip with no reason, for a stop the circuit does not have, or for a circuit that does not exist is refused")
    public void badSkips() {
        MovementSupport.assertRefused(() -> skip(circuitId, 2, ""), 400, "validation-failed");
        MovementSupport.assertRefused(() -> skip(circuitId, 2, "   "), 400, "validation-failed");
        MovementSupport.assertRefused(() -> skip(circuitId, 9, REASON), 404, "not-found");
        MovementSupport.assertRefused(() -> skip(UUID.randomUUID(), 1, REASON), 404, "not-found");
        assertThat(stops()).extracting(CircuitRunStop::getStatus)
                .containsOnly(CircuitRunStop.StatusEnum.PENDING);
    }

    @Then("a caller who may only read, and another tenant's administrator, cannot skip a stop")
    public void skipForbidden() {
        AuthResult reader = support.callerWith("mvskipreader", "MILKRUN_READ");
        MovementSupport.assertRefused(() -> support.as(reader, MilkRunsApi.class)
                .skipCircuitStop(circuitId, 1, new CircuitSkipRequest().reason(REASON), null),
                403, "access-denied");
        otherTenant = support.otherTenantAdministrator();
        MovementSupport.assertRefused(() -> support.as(otherTenant, MilkRunsApi.class)
                .skipCircuitStop(circuitId, 1, new CircuitSkipRequest().reason(REASON), null),
                404, "not-found");
        assertThat(stops()).extracting(CircuitRunStop::getStatus)
                .containsOnly(CircuitRunStop.StatusEnum.PENDING);
    }

    @When("the second supplier is skipped with a reason")
    public void skipSecond() {
        skip(circuitId, 2, "  " + REASON + "  ");
    }

    @Then("the second stop is skipped with the reason, the first is still waiting and the circuit is under way")
    public void stopSkipped() {
        List<CircuitRunStop> stops = stops();
        CircuitRunStop first = stops.get(0);
        CircuitRunStop second = stops.get(1);
        assertThat(second.getSequence()).isEqualTo(2);
        assertThat(second.getStatus()).isEqualTo(CircuitRunStop.StatusEnum.SKIPPED);
        assertThat(second.getSkipReason()).isEqualTo(REASON);
        assertThat(second.getArrivedAt()).isNull();
        assertThat(second.getDepartedAt()).isNull();
        assertThat(second.getPickedUpKg()).isNull();
        assertThat(second.getTerminalId()).isEqualTo(secondSupplier);
        assertThat(first.getStatus()).isEqualTo(CircuitRunStop.StatusEnum.PENDING);
        assertThat(first.getSkipReason()).isNull();
        assertThat(circuitRun().getStatus()).isEqualTo(CircuitRun.StatusEnum.IN_PROGRESS);
    }

    @Then("skipping the same stop again is refused as a conflict")
    public void skipAgain() {
        MovementSupport.assertRefused(() -> skip(circuitId, 2, "Again"), 409,
                "circuit-stop-illegal-transition");
        assertThat(stops().get(1).getSkipReason()).isEqualTo(REASON);
    }

    @Then("the control tower raises one skipped-stop ticket for the circuit naming the route, the stop and the reason")
    public void ticket() {
        List<Exception> tickets = HttpWorld.eventually("the skipped-stop ticket", () -> {
            List<Exception> found = world.api(ControlTowerApi.class)
                    .listExceptions(null, null, null, 200).getItems().stream()
                    .filter(t -> circuitId.equals(t.getSubjectId()))
                    .toList();
            assertThat(found).isNotEmpty();
            return found;
        });
        assertThat(tickets).hasSize(1);
        Exception ticket = tickets.get(0);
        assertThat(ticket.getCategory().getValue()).isEqualTo("MILK_RUN_STOP_SKIPPED");
        assertThat(ticket.getSource()).isEqualTo(Exception.SourceEnum.MILK_RUN_STOP_SKIPPED);
        assertThat(ticket.getSeverity()).isEqualTo(Exception.SeverityEnum.P3);
        assertThat(ticket.getSubjectType()).isEqualTo(Exception.SubjectTypeEnum.CIRCUIT_RUN);
        assertThat(ticket.getTripId()).isEqualTo(tripId);
        assertThat(ticket.getStatus()).isEqualTo(Exception.StatusEnum.RAISED);
        assertThat(ticket.getSuspendsSettlement()).isFalse();
        assertThat(ticket.getDescription()).contains("stop 2").contains(circuitRouteCode)
                .contains(REASON);
    }

    @When("the first supplier is visited, collected from and left, and the circuit reaches the plant")
    public void visitFirstAndComplete() {
        MilkRunsApi runs = world.api(MilkRunsApi.class);
        runs.recordCircuitStopArrival(circuitId, 1, null,
                new MilestoneRequest().at(departure.plusMinutes(5)));
        runs.recordCircuitStopPickup(circuitId, 1,
                new CircuitPickupRequest().quantityKg(new BigDecimal("1200")), null);
        runs.recordCircuitStopDeparture(circuitId, 1, null,
                new MilestoneRequest().at(departure.plusMinutes(25)));
        runs.completeCircuitRun(circuitId, null, new MilestoneRequest().at(departure.plusMinutes(120)));
    }

    @Then("the circuit is completed having visited one supplier and skipped one, charged the run rate plus one stop fee")
    public void outcome() {
        CircuitRun run = circuitRun();
        assertThat(run.getStatus()).isEqualTo(CircuitRun.StatusEnum.COMPLETED);
        assertThat(run.getStopsVisited()).isEqualTo(1);
        assertThat(run.getStopsSkipped()).isEqualTo(1);
        assertThat(run.getChargeAmount()).isEqualByComparingTo(BigDecimal.valueOf(RUN_RATE + STOP_FEE));
        assertThat(run.getTotalPickedUpKg()).isEqualByComparingTo("1200");
        assertThat(run.getCompletionSource()).isEqualTo(CircuitRun.CompletionSourceEnum.MANUAL);
        assertThat(stops()).extracting(CircuitRunStop::getStatus).containsExactly(
                CircuitRunStop.StatusEnum.DEPARTED, CircuitRunStop.StatusEnum.SKIPPED);
        assertThat(stops().get(1).getSkipReason()).isEqualTo(REASON);
    }

    @Then("a stop that was visited and a stop of a completed circuit can no longer be skipped")
    public void tooLate() {
        MovementSupport.assertRefused(() -> skip(circuitId, 1, REASON), 409,
                "circuit-run-not-in-progress");
        assertThat(stops().get(0).getStatus()).isEqualTo(CircuitRunStop.StatusEnum.DEPARTED);
    }

    @Then("a stop that was visited cannot be skipped while the circuit is under way")
    public void visitedCannotBeSkipped() {
        MilkRunsApi runs = world.api(MilkRunsApi.class);
        runs.recordCircuitStopArrival(circuitId, 1, null,
                new MilestoneRequest().at(departure.plusMinutes(5)));
        MovementSupport.assertRefused(() -> skip(circuitId, 1, REASON), 409,
                "circuit-stop-illegal-transition");
        assertThat(stops().get(0).getStatus()).isEqualTo(CircuitRunStop.StatusEnum.ARRIVED);
    }

    // -------------------------------------------------------------- helpers

    private PublishMilkRunRouteRequest request(String code, LocalDate from,
                                               List<PublishMilkRunRouteRequest.DaysEnum> days,
                                               String time, String zone, int runRate, int fee,
                                               List<MilkRunStopPlan> stops) {
        return new PublishMilkRunRouteRequest()
                .code(code)
                .name("Collection " + code)
                .plantTerminalId(plant)
                .effectiveFrom(from)
                .days(days)
                .departureTime(time)
                .timeZone(zone)
                .runRate(BigDecimal.valueOf(runRate))
                .perStopFee(BigDecimal.valueOf(fee))
                .stops(stops);
    }

    private static MilkRunStopPlan stop(UUID terminal, int open, int close, int dwell) {
        return new MilkRunStopPlan().terminalId(terminal)
                .windowOpenMin(open).windowCloseMin(close).plannedDwellMin(dwell);
    }

    private void skip(UUID circuit, int sequence, String reason) {
        world.api(MilkRunsApi.class).skipCircuitStop(circuit, sequence,
                new CircuitSkipRequest().reason(reason), null);
    }

    private CircuitRun circuitRun() {
        return world.api(MilkRunsApi.class).getCircuitRun(circuitId);
    }

    private List<CircuitRunStop> stops() {
        return world.api(MilkRunsApi.class).getCircuitRunStops(circuitId);
    }
}
