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
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

import com.lms.execution.command.ExecutionSettingDefinitions;
import com.lms.execution.command.GatePassService;
import com.lms.execution.command.GateRegisterService;
import com.lms.execution.command.TripCommandService;
import com.lms.execution.command.TripStopService;
import com.lms.execution.command.domain.GateVisit;
import com.lms.execution.query.GateQueryService;
import com.lms.execution.query.GateVisitView;
import com.lms.execution.query.TripQueryService;
import com.lms.execution.query.TripStopView;
import com.lms.execution.query.TripStopsView;
import com.lms.settings.api.Settings;
import com.lms.shared.error.BusinessRuleViolationException;
import com.lms.shared.tenant.TenantContext;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/** Step definitions for {@code yard.feature}: the gate register, gate passes and a trip's ordered stops. */
public class YardSteps {

    @Autowired
    private ScenarioWorld world;
    @Autowired
    private SpineFixtures fixtures;
    @Autowired
    private TripCommandService tripCommands;
    @Autowired
    private TripQueryService tripQueries;
    @Autowired
    private TripStopService stopCommands;
    @Autowired
    private GateRegisterService gate;
    @Autowired
    private GatePassService passes;
    @Autowired
    private GateQueryService gateQueries;
    @Autowired
    private Settings settings;
    @Autowired
    private PlatformTransactionManager transactionManager;

    private RuntimeException failure;

    // ------------------------------------------------------------------ given

    @Given("an awarded load {string} of {int} kg from {string} to {string} dropping at {string} then {string} "
            + "on lorry {string} for {string}")
    public void anAwardedMultiDropLoad(String loadNo, int weightKg, String origin, String consignee, String firstDrop,
                                       String lastDrop, String registration, String vendor) {
        world.putRef("load:" + loadNo, fixtures.awardedMultiDropLoad(world.orgUnitId(), world.uniqueCode(loadNo),
                world.ref("CUST"), world.ref(origin), world.ref(consignee),
                List.of(world.ref(firstDrop), world.ref(lastDrop)), world.ref("vehicle:" + registration),
                world.ref(vendor), BigDecimal.valueOf(weightKg)));
    }

    @Given("the unit requires a gate pass to enter")
    public void passRequired() {
        inTransaction(() -> {
            settings.setOrgDefaults(Map.of(ExecutionSettingDefinitions.GATE_PASS_REQUIRED, true));
            return null;
        });
    }

    @Given("a gate pass {string} for lorry {string} valid for {int} hours")
    public void aPass(String passNo, String registration, int hours) {
        Instant now = Instant.now();
        attempt(() -> passes.issue(new GatePassService.IssueCommand(world.orgUnitId(), null,
                world.ref("vehicle:" + registration), null, null, GateVisit.Purpose.MAINTENANCE,
                now.minusSeconds(3600), now.plusSeconds(hours * 3600L), passNo)));
    }

    @Given("a trip {string} is raised against load {string} and assigned lorry {string}")
    public void aTrip(String tripNo, String loadNo, String registration) {
        UUID trip = tripCommands.raiseTrip(world.uniqueCode(tripNo), world.ref("load:" + loadNo), Instant.now());
        world.putRef("trip:" + tripNo, trip);
        tripCommands.assign(trip, world.ref("vehicle:" + registration), null);
    }

    // ------------------------------------------------------------------- when

    @When("lorry {string} enters {string} for {string}")
    public void lorryEnters(String registration, String terminal, String purpose) {
        entered(registration, attempt(() -> gate.enter(entry(world.ref("vehicle:" + registration), null, terminal,
                purpose, null))));
    }

    @When("visitor lorry {string} enters {string} for {string}")
    public void visitorEnters(String registration, String terminal, String purpose) {
        entered(registration, attempt(() -> gate.enter(entry(null, registration, terminal, purpose, null))));
    }

    @When("lorry {string} enters {string} for {string} on pass {string}")
    public void lorryEntersOnPass(String registration, String terminal, String purpose, String passNo) {
        entered(registration, attempt(() -> gate.enter(entry(world.ref("vehicle:" + registration), null, terminal,
                purpose, passNo))));
    }

    @When("the visit of {string} ends")
    public void visitEnds(String registration) {
        UUID visit = world.ref("visit:" + registration);
        attempt(() -> {
            gate.exit(visit, null, List.of("S-9"), "WB-1", null);
            return null;
        });
    }

    @When("the visit of {string} ends again")
    public void visitEndsAgain(String registration) {
        UUID id = world.ref("visit:" + registration);
        attempt(() -> {
            gate.exit(id, null, null, null, null);
            return null;
        });
    }

    @When("stop {int} of trip {string} is reached")
    public void stopReached(int seq, String tripNo) {
        attempt(() -> {
            stopCommands.arrive(world.ref("trip:" + tripNo), stop(tripNo, seq).id(), null);
            return null;
        });
    }

    @When("stop {int} of trip {string} is left")
    public void stopLeft(int seq, String tripNo) {
        attempt(() -> {
            stopCommands.depart(world.ref("trip:" + tripNo), stop(tripNo, seq).id(), null);
            return null;
        });
    }

    @When("stop {int} of trip {string} is skipped because {string}")
    public void stopSkipped(int seq, String tripNo, String reason) {
        attempt(() -> {
            stopCommands.skip(world.ref("trip:" + tripNo), stop(tripNo, seq).id(), reason);
            return null;
        });
    }

    // ------------------------------------------------------------------- then

    @Then("{int} vehicle(s) is/are inside {string}")
    public void insideCount(int expected, String terminal) {
        assertThat(inTransaction(() -> gateQueries.openVisits(null, world.ref(terminal)))).hasSize(expected);
    }

    @Then("entering is refused with {string}")
    public void refusedWith(String code) {
        assertThat(failure).as("expected a refusal coded '%s' but the operation succeeded", code)
                .isInstanceOfSatisfying(BusinessRuleViolationException.class,
                        e -> assertThat(e.code()).isEqualTo(code));
    }

    @Then("the visit of {string} is closed with seal {string}")
    public void visitClosed(String registration, String seal) {
        GateVisitView visit = inTransaction(() -> gateQueries.visit(world.ref("visit:" + registration)).orElseThrow());
        assertThat(visit.status()).isEqualTo("CLOSED");
        assertThat(visit.exitSealNos()).containsExactly(seal);
    }

    @Then("the visit of {string} used pass {string}")
    public void visitUsedPass(String registration, String passNo) {
        GateVisitView visit = inTransaction(() -> gateQueries.visit(world.ref("visit:" + registration)).orElseThrow());
        assertThat(visit.gatePassNo()).isEqualTo(passNo);
    }

    @Then("trip {string} has {int} stops: {string}")
    public void stopsAre(String tripNo, int count, String described) {
        TripStopsView stops = stopsOf(tripNo);
        assertThat(stops.stops()).hasSize(count);
        assertThat(stops.stops().stream().map(s -> s.kind() + " " + terminalName(s)).toList())
                .containsExactly(described.split(", "));
    }

    @Then("stop {int} of trip {string} is {string}")
    public void stopStatus(int seq, String tripNo, String status) {
        assertThat(stop(tripNo, seq).status()).isEqualTo(status);
    }

    @Then("stop {int} of trip {string} is flagged out of order")
    public void flagged(int seq, String tripNo) {
        assertThat(stop(tripNo, seq).outOfOrder()).isTrue();
    }

    @Then("stop {int} of trip {string} is not flagged out of order")
    public void notFlagged(int seq, String tripNo) {
        assertThat(stop(tripNo, seq).outOfOrder()).isFalse();
    }

    @Then("stop {int} of trip {string} records the reason {string}")
    public void stopReason(int seq, String tripNo, String reason) {
        assertThat(stop(tripNo, seq).skipReason()).isEqualTo(reason);
    }

    @Then("the stop change is refused with {string}")
    public void stopRefused(String code) {
        refusedWith(code);
    }

    // ---------------------------------------------------------------- helpers

    private GateRegisterService.EnterCommand entry(UUID vehicleId, String registration, String terminal,
                                                   String purpose, String passNo) {
        return new GateRegisterService.EnterCommand(null, world.ref(terminal), vehicleId, registration, null,
                registration == null ? null : "Visitor", GateVisit.Purpose.valueOf(purpose), null, passNo, null,
                List.of(), null, null);
    }

    private void entered(String registration, UUID visitId) {
        if (visitId != null) {
            world.putRef("visit:" + registration, visitId);
        }
    }

    private TripStopsView stopsOf(String tripNo) {
        return inTransaction(() -> tripQueries.findStops(world.ref("trip:" + tripNo)));
    }

    private TripStopView stop(String tripNo, int seq) {
        return stopsOf(tripNo).stops().stream().filter(s -> s.seq() == seq).findFirst()
                .orElseThrow(() -> new AssertionError("Trip " + tripNo + " has no stop " + seq));
    }

    private static String terminalName(TripStopView stop) {
        // Codes carry the scenario's salt: "ORIGIN-1a2b3c4d" is "ORIGIN".
        return stop.terminalCode().replaceAll("-[0-9a-f]{8}$", "");
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
