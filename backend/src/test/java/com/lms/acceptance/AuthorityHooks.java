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

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import com.lms.masterdata.command.FleetCommandService;
import com.lms.masterdata.command.domain.BusinessPartner;
import io.cucumber.java.After;
import io.cucumber.java.Before;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Gives every scenario an authenticated principal.
 *
 * <p>The command services are guarded by {@code @PreAuthorize}, so without this
 * the suite would fail everywhere with an access-denied error that says nothing
 * about the rule under test. Scenarios about <em>business</em> rules should not
 * have to restate who is signed in, in the same way they do not restate that a
 * database is running.
 *
 * <p>The default principal holds every permission, which is exactly why
 * {@code identity.feature} also contains scenarios that take one away. A
 * blanket grant with nothing testing the other side would make the
 * authorisation annotations look enforced while proving nothing.
 */
public class AuthorityHooks {

    @Autowired
    private ScenarioWorld world;
    @Autowired
    private FleetCommandService fleet;

    private RuntimeException failure;

    /**
     * The permission vocabulary seeded by V2.
     *
     * <p>Listed rather than read from the database on purpose: if somebody adds
     * a permission to the migration and a {@code @PreAuthorize} that uses it,
     * this list not having it is how they find out, rather than the suite
     * silently granting whatever exists.
     */
    static final List<String> ALL_PERMISSIONS = List.of(
            "PARTNER_CREATE", "PARTNER_READ", "PARTNER_UPDATE",
            "VEHICLE_CREATE", "VEHICLE_READ", "VEHICLE_UPDATE",
            "DRIVER_CREATE", "DRIVER_READ",
            "TERMINAL_CREATE", "TERMINAL_READ",
            "ORDER_CREATE", "ORDER_READ", "ORDER_UPDATE",
            "LOAD_CREATE", "LOAD_READ", "LOAD_ALLOCATE",
            "TRIP_READ", "TRIP_EXECUTE",
            "TELEMATICS_INGEST",
            "INVOICE_READ", "INVOICE_APPROVE",
            "AUDIT_READ");

    /**
     * Runs before the tenant-scope hooks, because a step that issues a command
     * needs both and the ordering between them does not matter -- but leaving
     * either unset produces a failure that points at the wrong thing.
     */
    @Before(order = 0)
    public void authenticateWithEveryPermission() {
        authenticateWith(ALL_PERMISSIONS);
    }

    @After(order = 0)
    public void clearAuthentication() {
        // The context is thread-bound and the suite reuses threads. Left behind,
        // it would grant the next scenario permissions it never asked for --
        // which would make a scenario that tests a denial pass or fail
        // depending on what ran before it.
        SecurityContextHolder.clearContext();
    }

    @Given("the signed-in user lacks {string}")
    public void userLacks(String permission) {
        authenticateWith(ALL_PERMISSIONS.stream()
                .filter(held -> !held.equals(permission))
                .toList());
    }

    @Given("the signed-in user holds only {string}")
    public void userHoldsOnly(String permissions) {
        authenticateWith(Arrays.stream(permissions.split(","))
                .map(String::trim)
                .filter(code -> !code.isEmpty())
                .toList());
    }

    /**
     * A command chosen because it is the simplest one that is guarded.
     *
     * <p>Registering a partner touches one table and has no prerequisites, so a
     * failure here can only be about authorisation -- which is what these two
     * scenarios are for.
     */
    @When("a partner {string} is registered")
    public void registerPartner(String code) {
        failure = null;
        try {
            world.putRef(code, fleet.registerPartner(world.orgUnitId(), world.uniqueCode(code),
                    code + " Ltd", BusinessPartner.PartnerType.CUSTOMER, "TAX-" + code));
        } catch (AccessDeniedException e) {
            failure = e;
        }
    }

    @Then("the command is refused as unauthorised")
    public void refusedAsUnauthorised() {
        org.assertj.core.api.Assertions.assertThat(failure)
                .as("the command should have been refused for lack of permission")
                .isInstanceOf(AccessDeniedException.class);
    }

    @Then("the command succeeds")
    public void commandSucceeds() {
        org.assertj.core.api.Assertions.assertThat(failure)
                .as("the command should have been permitted but failed with: %s",
                        failure == null ? "" : failure.getMessage())
                .isNull();
    }

    private static void authenticateWith(List<String> permissions) {
        Set<SimpleGrantedAuthority> authorities = permissions.stream()
                .map(SimpleGrantedAuthority::new)
                .collect(Collectors.toSet());

        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("acceptance-user", "n/a", authorities));
    }
}
