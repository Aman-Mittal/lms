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

import com.lms.controltower.command.DetectorSettingsService;
import com.lms.controltower.command.TripWatchCommandService;
import com.lms.controltower.query.ControlTowerQueryService;
import com.lms.controltower.query.TripWatchView;
import com.lms.telematics.api.PingIngestPort;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Step definitions for {@code detectors.feature}. The ticket assertions are
 * {@link ControlTowerSteps}'s; these set the terms and send the readings.
 */
public class DetectorSteps {

    @Autowired
    private ScenarioWorld world;
    @Autowired
    private TripWatchCommandService watches;
    @Autowired
    private DetectorSettingsService settings;
    @Autowired
    private ControlTowerQueryService controlTower;
    @Autowired
    private PingIngestPort ingest;

    private final List<PingIngestPort.PingReport> buffered = new ArrayList<>();
    private PingIngestPort.IngestResult lastResult;
    private RuntimeException failure;

    // ------------------------------------------------------------------ given

    @Given("the tenant sets the tolerances to {int} minutes for an ETA slip and {int} minutes "
            + "for a temperature excursion")
    public void tolerances(int etaMinutes, int temperatureMinutes) {
        settings.update(etaMinutes, temperatureMinutes);
    }

    @Given("trip {string} is committed to arrive in {int} minutes")
    public void committed(String tripNo, int minutes) {
        watches.set(tripId(tripNo), Instant.now().plus(minutes, ChronoUnit.MINUTES), null, null);
    }

    @Given("trip {string} must stay between {bigdecimal} and {bigdecimal} C")
    public void band(String tripNo, BigDecimal min, BigDecimal max) {
        watches.set(tripId(tripNo), null, min, max);
    }

    @Given("the lorry {string} has buffered a reading of {bigdecimal} C at {double},{double} "
            + "{int} minutes ago")
    public void buffer(String registration, BigDecimal celsius, double lat, double lon,
                       int minutesAgo) {
        buffered.add(report(registration, celsius, lat, lon, minutesAgo));
    }

    // ------------------------------------------------------------------- when

    @When("the lorry {string} reports a fix at {double},{double} now")
    public void fixNow(String registration, double lat, double lon) {
        post(List.of(report(registration, null, lat, lon, 0)));
    }

    @When("the lorry {string} reports a fix at {double},{double} with cargo at {bigdecimal} C "
            + "{int} minutes ago")
    public void fixWithCargo(String registration, double lat, double lon, BigDecimal celsius,
                             int minutesAgo) {
        post(List.of(report(registration, celsius, lat, lon, minutesAgo)));
    }

    @When("the buffered readings are flushed")
    public void flush() {
        post(List.copyOf(buffered));
    }

    /** The same batch again, as the outbox or a retrying device would send it. */
    @When("the buffered readings are flushed again")
    public void flushAgain() {
        post(List.copyOf(buffered));
    }

    @When("the desk sets only a minimum of {bigdecimal} C on trip {string}")
    public void onlyMinimum(BigDecimal min, String tripNo) {
        attempt(() -> watches.set(tripId(tripNo), null, min, null));
    }

    @When("the desk sets a band of {bigdecimal} to {bigdecimal} C on trip {string}")
    public void setBand(BigDecimal min, BigDecimal max, String tripNo) {
        attempt(() -> watches.set(tripId(tripNo), null, min, max));
    }

    @When("the desk sets an empty watch on trip {string}")
    public void emptyWatch(String tripNo) {
        attempt(() -> watches.set(tripId(tripNo), null, null, null));
    }

    @When("the desk sets the ETA tolerance to {int} minutes")
    public void etaTolerance(int minutes) {
        attempt(() -> settings.update(minutes, 15));
    }

    @When("the desk stops watching trip {string}")
    public void stopWatching(String tripNo) {
        watches.clear(tripId(tripNo));
    }

    // ------------------------------------------------------------------- then

    @Then("trip {string} is watched for a band of {bigdecimal} to {bigdecimal} C")
    public void watchedForBand(String tripNo, BigDecimal min, BigDecimal max) {
        TripWatchView view = controlTower.watchOf(tripId(tripNo)).orElseThrow();
        assertThat(view.tempMinC()).isEqualByComparingTo(min);
        assertThat(view.tempMaxC()).isEqualByComparingTo(max);
    }

    @Then("trip {string} has been out of its band since {int} minutes ago")
    public void outSince(String tripNo, int minutesAgo) {
        Instant since = controlTower.watchOf(tripId(tripNo)).orElseThrow().outsideRangeSince();
        assertThat(since).isNotNull();
        assertThat(Instant.now().toEpochMilli() - since.toEpochMilli())
                .isCloseTo(minutesAgo * 60_000L, within(60_000L));
    }

    @Then("trip {string} is not out of its band")
    public void notOut(String tripNo) {
        assertThat(controlTower.watchOf(tripId(tripNo)).orElseThrow().outsideRangeSince())
                .isNull();
    }

    @Then("the tolerances in force are {int} minutes for an ETA slip and {int} minutes for a "
            + "temperature excursion")
    public void inForce(int etaMinutes, int temperatureMinutes) {
        DetectorSettingsService.Settings now = settings.view();
        assertThat(now.etaSlipMinutes()).isEqualTo(etaMinutes);
        assertThat(now.temperatureExcursionMinutes()).isEqualTo(temperatureMinutes);
    }

    @Then("the last report was rejected as an implausible temperature")
    public void rejectedTemperature() {
        assertThat(lastResult.rejected()).isEqualTo(1);
        assertThat(lastResult.rejections().get(0).reason()).contains("implausible cargo temperature");
    }

    @Then("the watch is refused mentioning {string}")
    public void refusedMentioning(String fragment) {
        assertThat(failure).as("the change should have been refused").isNotNull();
        assertThat(failure.getMessage()).contains(fragment);
    }

    @Then("the watch change is refused as unauthorised")
    public void refusedAsUnauthorised() {
        assertThat(failure).isInstanceOf(AccessDeniedException.class);
    }

    // --------------------------------------------------------------- helpers

    private UUID tripId(String tripNo) {
        return world.ref("trip:" + tripNo);
    }

    private PingIngestPort.PingReport report(String registration, BigDecimal celsius, double lat,
                                             double lon, int minutesAgo) {
        return new PingIngestPort.PingReport(world.ref("vehicle:" + registration),
                Instant.now().minus(minutesAgo, ChronoUnit.MINUTES),
                BigDecimal.valueOf(lat), BigDecimal.valueOf(lon), BigDecimal.ZERO, null,
                BigDecimal.valueOf(5), true, "DEVICE", celsius);
    }

    private void post(List<PingIngestPort.PingReport> reports) {
        lastResult = ingest.ingest(reports);
    }

    private void attempt(Runnable change) {
        failure = null;
        try {
            change.run();
        } catch (AccessDeniedException | com.lms.shared.error.BusinessRuleViolationException e) {
            failure = e;
        }
    }
}
