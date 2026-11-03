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

import java.util.List;
import java.util.UUID;

import com.lms.masterdata.command.RegisterTerminalCommand;
import com.lms.masterdata.command.TerminalCommandService;
import com.lms.masterdata.command.TerminalRepository;
import com.lms.masterdata.command.UpdateTerminalCommand;
import com.lms.masterdata.command.domain.Terminal;
import com.lms.shared.error.BusinessRuleViolationException;
import com.lms.shared.error.ResourceNotFoundException;
import com.lms.shared.geo.LatLon;
import com.lms.shared.tenant.TenantContext;
import io.cucumber.java.After;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.simple.JdbcClient;

import static org.assertj.core.api.Assertions.assertThat;

/** Step definitions for {@code masterdata.feature}. */
public class MasterDataSteps {

    @Autowired
    private ScenarioWorld world;
    @Autowired
    private TerminalCommandService terminals;
    @Autowired
    private TerminalRepository terminalRepository;
    @Autowired
    private JdbcClient jdbc;

    private UUID registeredId;
    private RuntimeException failure;

    @After
    public void clearScope() {
        // The scope is thread-bound and the suite reuses threads. Left behind,
        // it would silently give the next scenario another tenant's view.
        TenantContext.clear();
    }

    // ---------------------------------------------------------------- given

    @Given("the tenant scope is {string} at {string}")
    public void setScope(String tenantCode, String orgPath) {
        TenantContext.set(world.tenantId(tenantCode), orgPath);
        // Commands issued from here on name this scope's organisational unit, not the
        // first tenant's: a row may not be created under another tenant's unit.
        world.useScope(tenantCode, orgPath);
    }

    // ----------------------------------------------------- when (and given)

    // One annotation only. Cucumber keywords are interchangeable -- a step
    // registered with @When also matches a Given line -- so annotating the same
    // expression with both registers it twice, which aborts registration of the
    // whole class partway and leaves later steps mysteriously "undefined".
    @When("a {word} terminal {string} is registered with the square from {double},{double} to {double},{double}")
    public void registerSquare(String category, String code,
                               double lat1, double lon1, double lat2, double lon2) {
        List<LatLon> ring = List.of(
                new LatLon(lat1, lon1),
                new LatLon(lat1, lon2),
                new LatLon(lat2, lon2),
                new LatLon(lat2, lon1));

        attempt(() -> terminals.register(new RegisterTerminalCommand.Polygon(
                world.orgUnitId(), code, code,
                Terminal.FunctionalCategory.valueOf(category),
                ring, 4, null, null, 60, List.of())));
        remember(code);
    }

    @When("a {word} terminal {string} is registered at {double},{double} with radius {int} m")
    public void registerCircle(String category, String code, double lat, double lon, int radius) {
        attempt(() -> terminals.register(new RegisterTerminalCommand.PointRadius(
                world.orgUnitId(), code, code,
                Terminal.FunctionalCategory.valueOf(category),
                new LatLon(lat, lon), radius, 2, null, null, 45, List.of())));
        remember(code);
    }

    @When("a {word} terminal {string} is registered with a self-intersecting ring")
    public void registerBowTie(String category, String code) {
        // A bow-tie: opposite corners visited out of order.
        List<LatLon> ring = List.of(
                new LatLon(19.00, 72.80),
                new LatLon(19.01, 72.81),
                new LatLon(19.00, 72.81),
                new LatLon(19.01, 72.80));

        attempt(() -> terminals.register(new RegisterTerminalCommand.Polygon(
                world.orgUnitId(), code, code,
                Terminal.FunctionalCategory.valueOf(category),
                ring, 1, null, null, 30, List.of())));
    }

    // ------------------------------------------------- when: maintenance

    @When("terminal {string} is renamed {string} with {int} docks")
    public void rename(String code, String name, int docks) {
        Terminal terminal = find(code);
        maintain(() -> terminals.update(new UpdateTerminalCommand(terminal.id(), name,
                terminal.version(), null, docks, null, null, terminal.avgDwellMinutes(),
                List.of())));
    }

    @When("terminal {string} is renamed {string} from the version before the last change")
    public void renameFromStaleVersion(String code, String name) {
        Terminal terminal = find(code);
        maintain(() -> terminals.update(new UpdateTerminalCommand(terminal.id(), name,
                terminal.version() - 1, null, terminal.dockCount(), null, null,
                terminal.avgDwellMinutes(), List.of())));
    }

    @When("terminal {string} is moved to the square from {double},{double} to {double},{double}")
    public void moveToSquare(String code, double lat1, double lon1, double lat2, double lon2) {
        Terminal terminal = find(code);
        maintain(() -> terminals.update(new UpdateTerminalCommand(terminal.id(), terminal.name(),
                terminal.version(),
                new UpdateTerminalCommand.Polygon(List.of(new LatLon(lat1, lon1),
                        new LatLon(lat1, lon2), new LatLon(lat2, lon2), new LatLon(lat2, lon1))),
                terminal.dockCount(), terminal.opensAt(), terminal.closesAt(),
                terminal.avgDwellMinutes(), List.of())));
    }

    @When("terminal {string} is moved to the circle at {double},{double} with radius {int} m")
    public void moveToCircle(String code, double lat, double lon, int radius) {
        Terminal terminal = find(code);
        maintain(() -> terminals.update(new UpdateTerminalCommand(terminal.id(), terminal.name(),
                terminal.version(),
                new UpdateTerminalCommand.PointRadius(new LatLon(lat, lon), radius),
                terminal.dockCount(), terminal.opensAt(), terminal.closesAt(),
                terminal.avgDwellMinutes(), List.of())));
    }

    @When("terminal {string} is deleted")
    public void delete(String code) {
        UUID id = idOf(code);
        maintain(() -> terminals.delete(id));
    }

    // ----------------------------------------------------------------- then

    @Then("the terminal is registered")
    public void terminalRegistered() {
        assertThat(failure)
                .as("registration should have succeeded but failed with: %s",
                        failure == null ? "" : failure.getMessage())
                .isNull();
        assertThat(registeredId).isNotNull();
    }

    @Then("registration is refused because the terminals overlap")
    public void refusedForOverlap() {
        assertThat(failure).isInstanceOf(BusinessRuleViolationException.class);
        assertThat(((BusinessRuleViolationException) failure).code()).isEqualTo("terminal-overlap");
    }

    @Then("registration is refused because the code is taken")
    public void refusedForDuplicateCode() {
        assertThat(failure).isInstanceOf(BusinessRuleViolationException.class);
        assertThat(((BusinessRuleViolationException) failure).code()).isEqualTo("terminal-code-taken");
    }

    @Then("registration is refused as invalid geometry")
    public void refusedForGeometry() {
        // Deliberately IllegalArgumentException rather than a business rule:
        // this is a malformed input, not a domain decision, and it surfaces as
        // 400 rather than 409.
        assertThat(failure).isInstanceOf(IllegalArgumentException.class);
    }

    @Then("the terminal is updated")
    public void terminalUpdated() {
        assertThat(failure).as("the update should have succeeded").isNull();
    }

    @Then("the update is refused with {string}")
    public void updateRefused(String code) {
        assertThat(codeOf(failure)).isEqualTo(code);
    }

    @Then("the terminal is deleted")
    public void terminalDeleted() {
        assertThat(failure).as("the delete should have succeeded").isNull();
    }

    @Then("deleting the terminal is refused with {string} mentioning {string}")
    public void deleteRefused(String code, String fragment) {
        assertThat(codeOf(failure)).isEqualTo(code);
        assertThat(failure.getMessage()).contains(fragment);
    }

    @Then("deleting the terminal is refused as not found")
    public void deleteRefusedAsAbsent() {
        assertThat(failure).isInstanceOf(ResourceNotFoundException.class);
    }

    @Then("terminal {string} is named {string} with {int} docks")
    public void terminalNamed(String code, String name, int docks) {
        Terminal terminal = find(code);
        assertThat(terminal.name()).isEqualTo(name);
        assertThat(terminal.dockCount()).isEqualTo(docks);
    }

    @Then("terminal {string} still exists")
    public void terminalExists(String code) {
        assertThat(count(idOf(code))).isOne();
    }

    @Then("terminal {string} no longer exists")
    public void terminalGone(String code) {
        assertThat(count(idOf(code))).isZero();
    }

    @Then("the audit trail has {int} {string} entry for terminal {string}")
    public void terminalAudit(int expected, String action, String code) {
        assertThat(jdbc.sql("SELECT count(*) FROM audit_log WHERE action = :a AND resource_id = :id")
                .param("a", action).param("id", idOf(code)).query(Long.class).single())
                .isEqualTo((long) expected);
    }

    @Then("terminal {string} contains the point {double},{double}")
    public void terminalContains(String code, double lat, double lon) {
        assertThat(load(code).contains(new LatLon(lat, lon))).isTrue();
    }

    @Then("terminal {string} does not contain the point {double},{double}")
    public void terminalDoesNotContain(String code, double lat, double lon) {
        assertThat(load(code).contains(new LatLon(lat, lon))).isFalse();
    }

    // -------------------------------------------------------------- helpers

    private void remember(String code) {
        if (registeredId != null) {
            world.putRef("terminal:" + code, registeredId);
        }
    }

    /** The terminal a scenario means: one it registered under that code, or a depot it was given. */
    private UUID idOf(String code) {
        try {
            return world.ref("terminal:" + code);
        } catch (IllegalStateException notRegisteredHere) {
            return world.ref(code);
        }
    }

    /** Read as the table owner, so it works whichever tenant scope the scenario is in. */
    private Terminal find(String code) {
        return terminalRepository.findById(idOf(code))
                .orElseThrow(() -> new AssertionError("Terminal " + code + " is not visible here"));
    }

    private long count(UUID id) {
        return jdbc.sql("SELECT count(*) FROM terminal WHERE id = :id")
                .param("id", id).query(Long.class).single();
    }

    private static String codeOf(RuntimeException failure) {
        assertThat(failure).as("the operation should have been refused").isNotNull();
        if (failure instanceof BusinessRuleViolationException rule) {
            return rule.code();
        }
        if (failure instanceof OptimisticLockingFailureException) {
            // The web layer turns this into 409 concurrent-modification.
            return "concurrent-modification";
        }
        return failure.getClass().getSimpleName();
    }

    private void maintain(Runnable action) {
        failure = null;
        try {
            action.run();
        } catch (BusinessRuleViolationException | IllegalArgumentException
                 | ResourceNotFoundException | OptimisticLockingFailureException e) {
            failure = e;
        }
    }

    private Terminal load(String code) {
        return terminalRepository.findByCode(TenantContext.requireTenantId(), code)
                .orElseThrow(() -> new AssertionError("Terminal " + code + " was not persisted"));
    }

    private void attempt(java.util.function.Supplier<UUID> action) {
        failure = null;
        registeredId = null;
        try {
            registeredId = action.get();
        } catch (BusinessRuleViolationException | IllegalArgumentException e) {
            failure = e;
        }
    }
}
