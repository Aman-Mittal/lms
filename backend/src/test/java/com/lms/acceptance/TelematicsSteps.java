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
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import com.lms.execution.command.TripCommandService;
import com.lms.execution.command.TripRepository;
import com.lms.execution.command.domain.Trip;
import com.lms.execution.command.domain.WeighbridgeReading;
import com.lms.shared.tenant.TenantContext;
import com.lms.telematics.api.PingIngestPort;
import com.lms.telematics.command.PingWriter;
import com.lms.telematics.command.RouteDeviationRepository;
import com.lms.telematics.command.TrackRetentionJob;
import com.lms.telematics.command.TripTrackRepository;
import com.lms.telematics.command.domain.RouteDeviation;
import com.lms.telematics.query.GeofenceCrossingView;
import com.lms.telematics.query.TelematicsQueryService;
import com.lms.telematics.query.TripProgressView;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/** Step definitions for {@code telematics.feature}. */
public class TelematicsSteps {

    @Autowired
    private ScenarioWorld world;
    @Autowired
    private SpineFixtures fixtures;
    @Autowired
    private TripCommandService tripCommands;
    @Autowired
    private TripRepository trips;
    @Autowired
    private PingIngestPort ingest;
    @Autowired
    private PingWriter pings;
    @Autowired
    private TelematicsQueryService telematics;
    @Autowired
    private RouteDeviationRepository deviations;
    @Autowired
    private TripTrackRepository tracks;
    @Autowired
    private TrackRetentionJob retention;
    @Autowired
    private PlatformTransactionManager transactionManager;

    private final List<PingIngestPort.PingReport> batch = new ArrayList<>();
    private PingIngestPort.IngestResult result;

    // ------------------------------------------------------------------ given

    /**
     * A trip taken all the way to the gate and out of it.
     *
     * <p>Every step of that path is covered by {@code execution.feature}.
     * Repeating it in each scenario here would bury the rule being tested under
     * a page of setup, and a Background nobody reads is a Background that
     * eventually asserts something other than what its name claims.
     */
    @Given("a dispatched trip {string} on lorry {string} from {string} to {string}")
    public void aDispatchedTrip(String tripNo, String registration,
                                String originCode, String destinationCode) {
        UUID loadId = fixtures.awardedLoad(world.orgUnitId(), world.uniqueCode("LOAD-" + tripNo),
                world.ref("CUST"), world.ref(originCode), world.ref("CONS-A"),
                world.ref(destinationCode), world.ref("vehicle:" + registration),
                world.ref("VENDOR-1"), new BigDecimal("5000"), false);

        UUID tripId = tripCommands.raiseTrip(world.uniqueCode(tripNo), loadId, Instant.now());
        world.putRef("trip:" + tripNo, tripId);

        tripCommands.assign(tripId, world.ref("vehicle:" + registration), world.ref("driver:Ravi"));
        tripCommands.gateIn(tripId, world.ref(originCode), Instant.now().minus(6, ChronoUnit.HOURS), null);
        tripCommands.recordWeighing(tripId, world.ref(originCode),
                WeighbridgeReading.ReadingType.TARE, new BigDecimal("6000"), null);
        tripCommands.recordWeighing(tripId, world.ref(originCode),
                WeighbridgeReading.ReadingType.GROSS, new BigDecimal("11000"), null);
        tripCommands.markLoaded(tripId, null);
        tripCommands.attachDocument(tripId, "CONSIGNMENT_NOTE", "LR-" + tripNo);
        tripCommands.attachDocument(tripId, "EWAY_BILL", "EW-" + tripNo);
        tripCommands.dispatch(tripId, Instant.now().minus(5, ChronoUnit.HOURS));
    }

    @Given("a position for {string} at {double},{double} {int} hours ago")
    public void aPosition(String registration, double lat, double lon, int hoursAgo) {
        batch.add(new PingIngestPort.PingReport(world.ref("vehicle:" + registration),
                Instant.now().minus(hoursAgo, ChronoUnit.HOURS),
                BigDecimal.valueOf(lat), BigDecimal.valueOf(lon),
                BigDecimal.valueOf(60), null, BigDecimal.valueOf(5), true, "DEVICE"));
    }

    /**
     * A dense run of points along a straight line, as a lorry on a motorway
     * actually reports.
     *
     * <p>Straight on purpose: it is the shape simplification should collapse
     * almost entirely, so the assertion that follows is about whether retention
     * genuinely works rather than about whether it ran.
     */
    @Given("a straight run of {int} positions for {string} between {double},{double} "
            + "and {double},{double}")
    public void aStraightRun(int count, String registration,
                             double fromLat, double fromLon, double toLat, double toLon) {
        UUID vehicleId = world.ref("vehicle:" + registration);
        // Spread across four hours so consecutive points imply a plausible speed
        // rather than tripping the jump filter.
        Instant start = Instant.now().minus(4, ChronoUnit.HOURS);
        long stepSeconds = (4 * 3600L) / count;

        for (int i = 0; i < count; i++) {
            double fraction = i / (double) (count - 1);
            batch.add(new PingIngestPort.PingReport(vehicleId,
                    start.plusSeconds(i * stepSeconds),
                    BigDecimal.valueOf(fromLat + (toLat - fromLat) * fraction),
                    BigDecimal.valueOf(fromLon + (toLon - fromLon) * fraction),
                    BigDecimal.valueOf(60), null, BigDecimal.valueOf(5), true, "DEVICE"));
        }
    }

    // ------------------------------------------------------------------- when

    @Given("a position for an unregistered vehicle {int} hours ago")
    public void aPositionForUnknownVehicle(int hoursAgo) {
        batch.add(new PingIngestPort.PingReport(UUID.randomUUID(),
                Instant.now().minus(hoursAgo, ChronoUnit.HOURS),
                BigDecimal.valueOf(19.4), BigDecimal.valueOf(73.4),
                BigDecimal.valueOf(60), null, BigDecimal.valueOf(5), true, "DEVICE"));
    }

    @When("the positions are ingested")
    public void ingestPositions() {
        result = ingest.ingest(List.copyOf(batch));
        batch.clear();
    }

    @When("the track for trip {string} is compacted")
    public void compactTrack(String tripNo) {
        inTransaction(() -> retention.compact(TenantContext.requireTenantId(),
                world.ref("trip:" + tripNo)));
    }

    // ------------------------------------------------------------------- then

    @Then("{int} positions were accepted")
    public void positionsAccepted(int expected) {
        assertThat(result.accepted())
                .as("rejections were: %s", result.rejections())
                .isEqualTo(expected);
    }

    @Then("{int} position was accepted")
    public void onePositionAccepted(int expected) {
        positionsAccepted(expected);
    }

    @Then("{int} position was rejected mentioning {string}")
    public void positionRejected(int expected, String fragment) {
        assertThat(result.rejected()).isEqualTo(expected);
        assertThat(result.rejections()).anySatisfy(rejection ->
                assertThat(rejection.reason()).contains(fragment));
    }

    @Then("trip {string} has {int} geofence crossings")
    public void geofenceCrossings(String tripNo, int expected) {
        assertThat(crossings(tripNo)).hasSize(expected);
    }

    @Then("trip {string} crossed {string} as {string}")
    public void crossedAs(String tripNo, String terminalCode, String eventType) {
        String code = world.uniqueCode(terminalCode);
        assertThat(crossings(tripNo))
                .as("crossings recorded: %s", crossings(tripNo))
                .anySatisfy(crossing -> {
                    assertThat(crossing.terminalCode()).isEqualTo(code);
                    assertThat(crossing.eventType()).isEqualTo(eventType);
                });
    }

    @Then("trip {string} has {int} open route deviation")
    public void openDeviation(String tripNo, int expected) {
        openDeviations(tripNo, expected);
    }

    @Then("trip {string} has {int} open route deviations")
    public void openDeviations(String tripNo, int expected) {
        assertThat(deviationsFor(tripNo).stream().filter(RouteDeviation::isOpen).toList())
                .hasSize(expected);
    }

    @Then("the open deviation is more than {int} m off route")
    public void deviationDistance(int expectedMetres) {
        // The trip is implied: only one scenario opens a deviation, and naming
        // it again would be noise.
        assertThat(world.ref("trip:TRIP-T1")).isNotNull();
        RouteDeviation open = deviationsFor("TRIP-T1").stream()
                .filter(RouteDeviation::isOpen)
                .findFirst()
                .orElseThrow(() -> new AssertionError("No open deviation"));

        assertThat(open.distanceM()).isGreaterThan(BigDecimal.valueOf(expectedMetres));
    }

    @Then("lorry {string} was last seen at {double},{double}")
    public void lastSeenAt(String registration, double lat, double lon) {
        var last = inTransaction(() -> pings.lastKnown(TenantContext.requireTenantId(),
                world.ref("vehicle:" + registration)));

        assertThat(last).isPresent();
        assertThat(last.get().lat().doubleValue()).isEqualTo(lat);
        assertThat(last.get().lon().doubleValue()).isEqualTo(lon);
    }

    @Then("trip {string} reports progress of about {int} percent")
    public void progressAbout(String tripNo, int expected) {
        TripProgressView progress = progressOf(tripNo);

        assertThat(progress.completionPct())
                .as("completion was %s", progress.completionPct())
                // A wide band on purpose. The figure is straight-line distance
                // covered, and pinning it to a decimal would make the test a
                // restatement of the arithmetic rather than a check that
                // progress is being measured at all.
                .isBetween(BigDecimal.valueOf(expected - 15), BigDecimal.valueOf(expected + 15));
    }

    @Then("trip {string} has an estimated time of arrival")
    public void hasEta(String tripNo) {
        assertThat(progressOf(tripNo).estimatedMinutesRemaining()).isNotNull().isPositive();
    }

    @Then("trip {string} has no raw positions left")
    public void noRawPositions(String tripNo) {
        assertThat(inTransaction(() -> pings.trackOf(TenantContext.requireTenantId(),
                world.ref("trip:" + tripNo)))).isEmpty();
    }

    @Then("the stored track for trip {string} has fewer than {int} points")
    public void storedTrackSize(String tripNo, int fewerThan) {
        assertThat(track(tripNo).pointCount()).isLessThan(fewerThan);
    }

    @Then("the stored track for trip {string} is longer than {int} m")
    public void storedTrackLength(String tripNo, int metres) {
        assertThat(track(tripNo).distanceM()).isGreaterThan(BigDecimal.valueOf(metres));
    }

    // ---------------------------------------------------------------- helpers

    private List<GeofenceCrossingView> crossings(String tripNo) {
        return inTransaction(() -> telematics.crossingsFor(world.ref("trip:" + tripNo)));
    }

    private List<RouteDeviation> deviationsFor(String tripNo) {
        return inTransaction(() -> deviations.findByTrip(TenantContext.requireTenantId(),
                world.ref("trip:" + tripNo)));
    }

    private TripProgressView progressOf(String tripNo) {
        return inTransaction(() -> telematics.progressOf(world.ref("trip:" + tripNo)))
                .orElseThrow(() -> new AssertionError("No progress for trip " + tripNo));
    }

    private com.lms.telematics.command.domain.TripTrack track(String tripNo) {
        return inTransaction(() -> tracks.findByTrip(TenantContext.requireTenantId(),
                world.ref("trip:" + tripNo)))
                .orElseThrow(() -> new AssertionError("No stored track for trip " + tripNo));
    }

    /** The current status, read through the aggregate rather than a projection. */
    Trip trip(String tripNo) {
        return inTransaction(() -> trips.findById(world.ref("trip:" + tripNo)).orElseThrow());
    }

    private <T> T inTransaction(Supplier<T> action) {
        TenantContext.requireTenantId();
        return new TransactionTemplate(transactionManager).execute(status -> action.get());
    }
}
