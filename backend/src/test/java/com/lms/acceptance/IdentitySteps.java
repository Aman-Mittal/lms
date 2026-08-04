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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.lms.identity.AuthService;
import com.lms.shared.error.AuthenticationFailedException;
import io.cucumber.java.Before;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;

import static org.assertj.core.api.Assertions.assertThat;

/** Step definitions for {@code identity.feature}. */
public class IdentitySteps {

    @Autowired
    private TestFixtures fixtures;
    @Autowired
    private AuthService authService;
    @Autowired
    private JwtDecoder jwtDecoder;

    private final Map<String, UUID> tenantIds = new HashMap<>();
    private final Map<String, UUID> orgUnitIds = new HashMap<>();

    private AuthService.AuthResult lastResult;
    private final List<String> failureMessages = new ArrayList<>();
    private List<String> listedEmails;
    private List<Map<String, Object>> listedSubtree;

    @Before
    public void resetScenarioState() {
        tenantIds.clear();
        orgUnitIds.clear();
        failureMessages.clear();
        lastResult = null;
        listedEmails = null;
        listedSubtree = null;
    }

    // ---------------------------------------------------------------- given

    @Given("a tenant {string} named {string}")
    public void aTenant(String code, String name) {
        // Codes are made unique per scenario so a shared container does not
        // leak state between scenarios.
        tenantIds.put(code, fixtures.createTenant(uniqueCode(code), name));
    }

    @Given("tenant {string} has an organisational unit {string} named {string} of type {string}")
    public void anOrgUnit(String tenantCode, String path, String name, String type) {
        UUID tenantId = tenantIds.get(tenantCode);
        UUID parentId = orgUnitIds.get(parentPathOf(path));
        orgUnitIds.put(path, fixtures.createOrgUnit(tenantId, path, name, type, parentId));
    }

    @Given("tenant {string} has a user {string} with password {string} in unit {string}")
    public void aUser(String tenantCode, String email, String password, String orgPath) {
        fixtures.createUser(tenantIds.get(tenantCode), orgUnitIds.get(orgPath), orgPath, email, password);
    }

    @Given("the user {string} of tenant {string} is suspended")
    public void suspendUser(String email, String tenantCode) {
        fixtures.suspendUser(tenantIds.get(tenantCode), email);
    }

    @Given("{string} has logged in to tenant {string} with password {string}")
    public void hasLoggedIn(String email, String tenantCode, String password) {
        logsIn(email, tenantCode, password);
        assertThat(lastResult).as("precondition: login must succeed").isNotNull();
    }

    // ----------------------------------------------------------------- when

    @When("{string} logs in to tenant {string} with password {string}")
    public void logsIn(String email, String tenantCode, String password) {
        // Unknown tenant codes are passed through verbatim so the "no such
        // tenant" path is genuinely exercised.
        String code = tenantIds.containsKey(tenantCode) ? uniqueCode(tenantCode) : tenantCode;
        try {
            lastResult = authService.login(code, email, password, null);
        } catch (AuthenticationFailedException e) {
            lastResult = null;
            failureMessages.add(e.getMessage());
        }
    }

    @When("users are listed without a tenant predicate while scoped to {string}")
    public void listUsersScoped(String tenantCode) {
        listedEmails = fixtures.listAllUserEmailsWithoutTenantPredicate(tenantIds.get(tenantCode));
    }

    @When("users are listed without a tenant predicate and without any tenant scope")
    public void listUsersUnscoped() {
        listedEmails = fixtures.listAllUserEmailsWithoutTenantPredicate(null);
    }

    @When("the organisational subtree of {string} is listed for tenant {string}")
    public void listSubtree(String orgPath, String tenantCode) {
        listedSubtree = fixtures.listSubtree(tenantIds.get(tenantCode), orgPath);
    }

    @When("the refresh token is exchanged")
    public void exchangeRefreshToken() {
        previousRefreshToken = lastResult.refreshToken();
        lastResult = authService.refresh(previousRefreshToken);
    }

    private String previousRefreshToken;

    // ----------------------------------------------------------------- then

    @Then("authentication succeeds")
    public void authenticationSucceeds() {
        assertThat(lastResult).isNotNull();
        assertThat(lastResult.accessToken()).isNotBlank();
    }

    @Then("authentication fails")
    public void authenticationFails() {
        assertThat(lastResult).isNull();
        assertThat(failureMessages).isNotEmpty();
    }

    @Then("both authentication failures report the same message")
    public void failuresAreIndistinguishable() {
        assertThat(failureMessages).hasSize(2);
        assertThat(failureMessages.get(0)).isEqualTo(failureMessages.get(1));
    }

    @Then("the access token carries the tenant of {string}")
    public void tokenCarriesTenant(String tenantCode) {
        Jwt jwt = jwtDecoder.decode(lastResult.accessToken());
        assertThat(jwt.getClaimAsString("tenant")).isEqualTo(tenantIds.get(tenantCode).toString());
    }

    @Then("the access token carries the organisational path {string}")
    public void tokenCarriesOrgPath(String orgPath) {
        Jwt jwt = jwtDecoder.decode(lastResult.accessToken());
        assertThat(jwt.getClaimAsString("org")).isEqualTo(orgPath);
    }

    @Then("only users belonging to {string} are returned")
    public void onlyScopedTenantUsers(String tenantCode) {
        assertThat(listedEmails).isNotEmpty();
        assertThat(listedEmails).allSatisfy(email ->
                assertThat(email).endsWith("@" + tenantCode + ".test"));
    }

    @Then("no users are returned")
    public void noUsersReturned() {
        assertThat(listedEmails).isEmpty();
    }

    @Then("the subtree contains {string} and {string}")
    public void subtreeContains(String first, String second) {
        assertThat(paths()).contains(first, second);
    }

    @Then("the subtree does not contain {string}")
    public void subtreeExcludes(String path) {
        assertThat(paths()).doesNotContain(path);
    }

    @Then("a new access token is issued")
    public void newAccessTokenIssued() {
        assertThat(lastResult).isNotNull();
        assertThat(lastResult.accessToken()).isNotBlank();
        assertThat(lastResult.refreshToken()).isNotEqualTo(previousRefreshToken);
    }

    @Then("the previous refresh token is no longer accepted")
    public void previousRefreshTokenRejected() {
        try {
            authService.refresh(previousRefreshToken);
            org.junit.jupiter.api.Assertions.fail("A rotated refresh token must not be reusable");
        } catch (AuthenticationFailedException expected) {
            assertThat(expected).hasMessageContaining("invalid or expired");
        }
    }

    // -------------------------------------------------------------- helpers

    private List<String> paths() {
        return listedSubtree.stream().map(row -> (String) row.get("path")).toList();
    }

    private static String parentPathOf(String path) {
        String trimmed = path.substring(0, path.length() - 1);
        int lastSlash = trimmed.lastIndexOf('/');
        return lastSlash <= 0 ? null : trimmed.substring(0, lastSlash + 1);
    }

    /** Scenario-scoped tenant codes, since the container is shared across the suite. */
    private String uniqueCode(String code) {
        return code + "-" + scenarioSalt;
    }

    private final String scenarioSalt = UUID.randomUUID().toString().substring(0, 8);
}
