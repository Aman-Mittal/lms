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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

import com.lms.milkrun.command.CircuitRunCommandService;
import com.lms.milkrun.command.MilkRunRouteCommandService;
import com.lms.milkrun.events.CircuitRunCompleted;
import com.lms.milkrun.events.CircuitStopLate;
import com.lms.milkrun.events.CircuitStopSkipped;
import com.lms.milkrun.query.CircuitRunStopView;
import com.lms.milkrun.query.CircuitRunView;
import com.lms.milkrun.query.MilkRunQueryService;
import com.lms.shared.tenant.TenantContext;
import com.lms.telematics.api.PingIngestPort;
import com.lms.telematics.events.GeofenceCrossed;
import io.cucumber.datatable.DataTable;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/** Step definitions for {@code milkrun.feature}. */
public class MilkRunSteps {

    @Autowired
    private ScenarioWorld world;
    @Autowired
    private MilkRunRouteCommandService routes;
    @Autowired
    private CircuitRunCommandService circuits;
    @Autowired
    private MilkRunQueryService queries;
    @Autowired
    private PingIngestPort ingest;
    @Autowired
    private MilkRunEventLog announced;
    @Autowired
    private ApplicationEventPublisher publisher;
    @Autowired
    private JdbcClient jdbc;
    @Autowired
    private PlatformTransactionManager transactionManager;

    /** When today's circuit leaves; every position is placed relative to it. */
    private Instant departure;
    private final List<PingIngestPort.PingReport> batch = new ArrayList<>();
    private final List<Integer> planned = new ArrayList<>();
    private RuntimeException failure;

    // ------------------------------------------------------------------ routes

    /**
     * A route whose circuit left a few hours ago today, so the whole circuit
     * can be driven with positions in the recent past.
     *
     * <p>The departure is cut to the minute and the zone is UTC, so the
     * planned departure the generator works out is exactly this instant and a
     * lateness of ten minutes is ten, not nine and a bit.
     */
    @Given("a milk run route {string} to {string} that left {int} hours ago, daily, "
            + "at {double} per run and {double} per stop, calling at:")
    public void aRouteThatLeft(String code, String plant, int hoursAgo, double runRate,
                               double perStopFee, DataTable stops) {
        departure = Instant.now().minus(hoursAgo, ChronoUnit.HOURS).truncatedTo(ChronoUnit.MINUTES);
        LocalDate day = departure.atZone(ZoneOffset.UTC).toLocalDate();
        publish(code, plant, LocalTime.ofInstant(departure, ZoneOffset.UTC), "UTC",
                "MON,TUE,WED,THU,FRI,SAT,SUN", day.minusDays(1), runRate, perStopFee, stops);
    }

    @Given("a milk run route {string} to {string} leaving at {string} in {string} on {string} "
            + "from {string} at {double} per run and {double} per stop, calling at:")
    public void aScheduledRoute(String code, String plant, String departureTime, String zone,
                                String days, String effectiveFrom, double runRate,
                                double perStopFee, DataTable stops) {
        publish(code, plant, LocalTime.parse(departureTime), zone, days,
                LocalDate.parse(effectiveFrom), runRate, perStopFee, stops);
    }

    // ---------------------------------------------------------------- planning

    @When("circuits are planned for {int} days from {string}")
    public void planFor(int days, String from) {
        planned.add(circuits.generate(LocalDate.parse(from), days));
    }

    /**
     * Plans today and remembers the circuit.
     *
     * <p>Looked up rather than taken from the count: the hourly job may have
     * got there first, and then this plans nothing -- which is the point of
     * the idempotency, not a failure.
     */
    @Given("today's circuit on route {string} has been planned")
    public void todaysCircuit(String code) {
        assertThat(failure).as("publishing the route failed").isNull();
        LocalDate day = departure.atZone(ZoneOffset.UTC).toLocalDate();
        circuits.generate(day, 1);
        world.putRef("circuit:" + code, circuitOn(code, day).id());
    }

    @Given("the circuit on route {string} is carried by trip {string}")
    public void carriedBy(String code, String tripNo) {
        circuits.assignTrip(circuit(code), world.ref("trip:" + tripNo));
    }

    // --------------------------------------------------------------- positions

    @Given("lorry {string} reports from {string} {int} minutes into the circuit")
    public void reportsFrom(String registration, String terminal, int minutes) {
        double[] at = centreOf(terminal);
        report(registration, at[0], at[1], minutes);
    }

    @Given("lorry {string} reports between {string} and {string} {int} minutes into the circuit")
    public void reportsBetween(String registration, String from, String to, int minutes) {
        double[] a = centreOf(from);
        double[] b = centreOf(to);
        report(registration, (a[0] + b[0]) / 2, (a[1] + b[1]) / 2, minutes);
    }

    @When("the circuit's positions are ingested")
    public void ingestCircuitPositions() {
        PingIngestPort.IngestResult result = ingest.ingest(List.copyOf(batch));
        batch.clear();
        assertThat(result.rejected()).as("rejections: %s", result.rejections()).isZero();
    }

    /**
     * Replays every crossing the way the outbox would after a restart: the
     * same events, published again inside a transaction so the after-commit
     * listener is the one that hears them.
     */
    @When("every geofence crossing of trip {string} is delivered again")
    public void crossingsRedelivered(String tripNo) {
        UUID tripId = world.ref("trip:" + tripNo);
        UUID tenantId = TenantContext.requireTenantId();
        List<Map<String, Object>> crossings = inTransaction(() -> jdbc.sql("""
                        SELECT terminal_id, event_type, occurred_at
                          FROM geofence_event
                         WHERE trip_id = :tripId
                         ORDER BY occurred_at
                        """)
                .param("tripId", tripId)
                .query()
                .listOfRows());
        assertThat(crossings).isNotEmpty();

        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                crossings.forEach(row -> publisher.publishEvent(new GeofenceCrossed(tenantId,
                        tripId, tripNo, (UUID) row.get("terminal_id"),
                        (String) row.get("event_type"),
                        ((java.sql.Timestamp) row.get("occurred_at")).toInstant(),
                        Instant.now()))));
    }

    // ------------------------------------------------------------- by hand

    @When("stop {int} of the circuit on route {string} is recorded as reached {int} minutes "
            + "into the circuit")
    public void reachedByHand(int sequence, String code, int minutes) {
        attempt(() -> circuits.recordArrival(circuit(code), sequence, minutesIn(minutes)));
    }

    @When("stop {int} of the circuit on route {string} is recorded as left {int} minutes "
            + "into the circuit")
    public void leftByHand(int sequence, String code, int minutes) {
        attempt(() -> circuits.recordDeparture(circuit(code), sequence, minutesIn(minutes)));
    }

    @When("{int} kg is recorded as collected at stop {int} of the circuit on route {string}")
    public void collected(int kg, int sequence, String code) {
        attempt(() -> circuits.recordPickup(circuit(code), sequence, BigDecimal.valueOf(kg)));
    }

    @When("stop {int} of the circuit on route {string} is skipped because {string}")
    public void skippedByHand(int sequence, String code, String reason) {
        attempt(() -> circuits.skip(circuit(code), sequence, reason));
    }

    @When("the circuit on route {string} is recorded as reaching the plant {int} minutes "
            + "into the circuit")
    public void plantByHand(String code, int minutes) {
        attempt(() -> circuits.complete(circuit(code), minutesIn(minutes)));
    }

    // -------------------------------------------------------------- assertions

    @Then("the circuit on route {string} is {string}")
    public void circuitIs(String code, String status) {
        assertThat(failure).as("an earlier step failed").isNull();
        assertThat(run(code).status()).isEqualTo(status);
    }

    @Then("stop {int} of the circuit on route {string} is {string}")
    public void stopIs(int sequence, String code, String status) {
        assertThat(stop(code, sequence).status()).isEqualTo(status);
    }

    @Then("stop {int} of the circuit on route {string} was reached {int} minutes late")
    public void reachedLate(int sequence, String code, int minutes) {
        assertThat(stop(code, sequence).minutesLate()).isEqualTo(minutes);
    }

    @Then("every stop of the circuit on route {string} was reached on time by geofence")
    public void allOnTime(String code) {
        assertThat(stops(code)).allSatisfy(stop -> {
            assertThat(stop.minutesLate()).isZero();
            assertThat(stop.arrivalSource()).isEqualTo("GEOFENCE");
            assertThat(stop.departedAt()).isAfter(stop.arrivedAt());
        });
    }

    @Then("the circuit on route {string} was charged {double} for {int} suppliers visited")
    public void charged(String code, double amount, int visited) {
        CircuitRunView run = run(code);
        assertThat(run.chargeAmount()).isEqualByComparingTo(BigDecimal.valueOf(amount));
        assertThat(run.stopsVisited()).isEqualTo(visited);
    }

    @Then("the circuit on route {string} collected {int} kg in total")
    public void collectedInTotal(String code, int kg) {
        assertThat(run(code).totalPickedUpKg()).isEqualByComparingTo(BigDecimal.valueOf(kg));
    }

    @Then("a skipped-supplier alert was raised for stop {int} of the circuit on route {string}")
    public void skipAnnounced(int sequence, String code) {
        assertThat(announced.skipsOf(circuit(code)))
                .extracting(CircuitStopSkipped::sequence)
                .containsExactly(sequence);
    }

    @Then("a late-window alert of {int} minutes was raised for stop {int} of the circuit "
            + "on route {string}")
    public void lateAnnounced(int minutes, int sequence, String code) {
        List<CircuitStopLate> lates = announced.latesOf(circuit(code));
        assertThat(lates).hasSize(1);
        assertThat(lates.get(0).sequence()).isEqualTo(sequence);
        assertThat(lates.get(0).minutesLate()).isEqualTo(minutes);
    }

    @Then("no milk run alerts were raised for route {string}")
    public void noAlerts(String code) {
        assertThat(announced.skipsOf(circuit(code))).isEmpty();
        assertThat(announced.latesOf(circuit(code))).isEmpty();
        // And the circuit was finished exactly once, however often it was told.
        assertThat(announced.completionsOf(circuit(code))).hasSize(1);
    }

    @Then("the completion of the circuit on route {string} was announced with a charge of {double}")
    public void completionAnnounced(String code, double amount) {
        List<CircuitRunCompleted> completions = announced.completionsOf(circuit(code));
        assertThat(completions).hasSize(1);
        assertThat(completions.get(0).chargeAmount()).isEqualByComparingTo(BigDecimal.valueOf(amount));
        assertThat(completions.get(0).stopsSkipped()).isZero();
    }

    @Then("the planner planned {int} circuits and then {int}")
    public void plannedCounts(int first, int second) {
        assertThat(planned).containsExactly(first, second);
    }

    @Then("route {string} has circuits on {string}")
    public void circuitsOn(String code, String days) {
        List<LocalDate> expected = Arrays.stream(days.split(",")).map(LocalDate::parse).toList();
        List<LocalDate> actual = inTransaction(() ->
                queries.listRuns(null, code, null, null, 200).items()).stream()
                .map(CircuitRunView::runDate)
                .toList();
        assertThat(actual).containsExactlyElementsOf(expected);
    }

    @Then("the circuit on route {string} on {string} leaves at {string}")
    public void leavesAt(String code, String day, String instant) {
        assertThat(circuitOn(code, LocalDate.parse(day)).plannedDeparture())
                .isEqualTo(Instant.parse(instant));
    }

    @Then("the circuit on route {string} on {string} calls at {int} suppliers")
    public void callsAt(String code, String day, int count) {
        UUID id = circuitOn(code, LocalDate.parse(day)).id();
        assertThat(inTransaction(() -> queries.runStops(id))).hasSize(count);
    }

    @Then("the circuit on route {string} on {string} is priced at {double} per run")
    public void pricedAt(String code, String day, double rate) {
        assertThat(circuitOn(code, LocalDate.parse(day)).runRate())
                .isEqualByComparingTo(BigDecimal.valueOf(rate));
    }

    @Then("milk run planning refuses mentioning {string}")
    public void planningRefuses(String fragment) {
        circuitRefuses(fragment);
    }

    @Then("the circuit refuses mentioning {string}")
    public void circuitRefuses(String fragment) {
        assertThat(failure).as("the command should have been refused").isNotNull();
        assertThat(failure.getMessage()).contains(fragment);
    }

    // ----------------------------------------------------------------- helpers

    private void publish(String code, String plant, LocalTime departureTime, String zone,
                         String days, LocalDate effectiveFrom, double runRate, double perStopFee,
                         DataTable stops) {
        List<MilkRunRouteCommandService.StopPlan> plans = stops.asMaps().stream()
                .map(row -> new MilkRunRouteCommandService.StopPlan(
                        world.ref(row.get("supplier")),
                        Integer.parseInt(row.get("opens after")),
                        Integer.parseInt(row.get("closes after")),
                        Integer.parseInt(row.get("dwell"))))
                .toList();
        attempt(() -> routes.publish(code, code + " circuit", world.ref(plant), effectiveFrom,
                Arrays.asList(days.split(",")), departureTime, zone, BigDecimal.valueOf(runRate),
                BigDecimal.valueOf(perStopFee), "INR", plans));
    }

    private void report(String registration, double lat, double lon, int minutes) {
        batch.add(new PingIngestPort.PingReport(world.ref("vehicle:" + registration),
                minutesIn(minutes), BigDecimal.valueOf(lat), BigDecimal.valueOf(lon),
                BigDecimal.valueOf(40), null, BigDecimal.valueOf(5), true, "DEVICE"));
    }

    private Instant minutesIn(int minutes) {
        return departure.plus(minutes, ChronoUnit.MINUTES);
    }

    private double[] centreOf(String terminal) {
        UUID id = world.ref(terminal);
        return inTransaction(() -> jdbc.sql(
                        "SELECT centre_lat, centre_lon FROM terminal WHERE id = :id")
                .param("id", id)
                .query((rs, row) -> new double[] {rs.getDouble(1), rs.getDouble(2)})
                .single());
    }

    private UUID circuit(String code) {
        return world.ref("circuit:" + code);
    }

    private CircuitRunView circuitOn(String code, LocalDate day) {
        return inTransaction(() -> queries.listRuns(day, code, null, null, 1).items()).stream()
                .filter(run -> run.runDate().equals(day))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No circuit on route " + code + " on " + day));
    }

    private CircuitRunView run(String code) {
        UUID id = circuit(code);
        return inTransaction(() -> queries.findRun(id))
                .orElseThrow(() -> new AssertionError("No circuit on route " + code));
    }

    private List<CircuitRunStopView> stops(String code) {
        UUID id = circuit(code);
        return inTransaction(() -> queries.runStops(id));
    }

    private CircuitRunStopView stop(String code, int sequence) {
        return stops(code).stream()
                .filter(stop -> stop.sequence() == sequence)
                .findFirst()
                .orElseThrow(() -> new AssertionError("No stop " + sequence + " on " + code));
    }

    /**
     * Keeps the first refusal rather than the last, so a scenario that should
     * have gone through cleanly cannot have an early failure overwritten by a
     * later success.
     */
    private void attempt(Runnable action) {
        try {
            action.run();
        } catch (RuntimeException e) {
            if (failure == null) {
                failure = e;
            }
        }
    }

    private <T> T inTransaction(Supplier<T> action) {
        TenantContext.requireTenantId();
        return new TransactionTemplate(transactionManager).execute(status -> action.get());
    }
}
