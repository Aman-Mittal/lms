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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import com.lms.controltower.command.ExceptionCommandService;
import com.lms.finance.command.BillingService;
import com.lms.finance.query.FinanceQueryService;
import com.lms.finance.query.FreightBillView;
import com.lms.identity.access.SubjectAttributes;
import com.lms.identity.command.AccessPolicyCommandService;
import com.lms.identity.command.AccessPolicyRepository;
import com.lms.identity.command.AuthService;
import com.lms.identity.command.domain.AccessPolicy;
import com.lms.masterdata.command.FleetCommandService;
import com.lms.masterdata.command.domain.BusinessPartner;
import com.lms.shared.tenant.TenantContext;
import io.cucumber.java.Before;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Step definitions for {@code access.feature}.
 *
 * <p>Signs the user in with a real {@link JwtAuthenticationToken} carrying the
 * attribute claims, rather than the plain token the other step classes use.
 * That is the point: the decision point reads attributes from the verified
 * token, so a scenario that set them any other way would be testing a path
 * production never takes.
 */
public class AccessSteps {

    @Autowired
    private ScenarioWorld world;
    @Autowired
    private BillingService billing;
    @Autowired
    private FinanceQueryService finance;
    @Autowired
    private ExceptionCommandService exceptions;
    @Autowired
    private FleetCommandService fleet;
    @Autowired
    private AccessPolicyCommandService policies;
    @Autowired
    private AccessPolicyRepository policyRepository;
    @Autowired
    private AuthService auth;
    @Autowired
    private JwtDecoder jwtDecoder;
    @Autowired
    private JdbcClient jdbc;
    @Autowired
    private JsonMapper json;
    @Autowired
    private PlatformTransactionManager transactionManager;

    private RuntimeException failure;
    private AuthService.AuthResult session;

    @Before
    public void reset() {
        failure = null;
        session = null;
    }

    // ------------------------------------------------------------- subjects

    @Given("the signed-in user has an approval limit of {int}")
    public void signedInWithLimit(int limit) {
        signInWith("approver", new SubjectAttributes(null, null, null, BigDecimal.valueOf(limit)));
    }

    @Given("the signed-in user works in region {string}")
    public void signedInInRegion(String region) {
        signInWith("regional", new SubjectAttributes(region, null, null, null));
    }

    @Given("the signed-in user works only in {string}")
    public void signedInForMode(String mode) {
        signInWith("modal", new SubjectAttributes(null, List.of(mode), null, null));
    }

    @When("the signed-in user is a portal user for {string}")
    public void signedInAsPortalUser(String vendorCode) {
        signInWith("portal", List.of("INVOICE_READ"),
                new SubjectAttributes(null, null, world.ref(vendorCode), null));
    }

    @When("the signed-in user is tenant staff")
    public void signedInAsStaff() {
        signInWith("staff", SubjectAttributes.NONE);
    }

    // -------------------------------------------------------------- actions

    @When("the signed-in user matches a claim of {int} against {string}")
    public void matchClaim(int amount, String billNo) {
        attempt(() -> billing.matchVendorClaim(world.ref("bill:" + billNo), BigDecimal.valueOf(amount)));
    }

    @When("the signed-in user settles the dispute on {string}")
    public void settleDispute(String billNo) {
        attempt(() -> billing.resolveDispute(world.ref("bill:" + billNo), "Accepted the claim"));
    }

    @When("{string} with an approval limit of {int} closes ticket {string}")
    public void closeWithLimit(String who, int limit, String ticket) {
        signInWith(who, new SubjectAttributes(null, null, null, BigDecimal.valueOf(limit)));
        attempt(() -> exceptions.close(world.ref("ticket:" + ticket), "Closed"));
    }

    @When("a partner {string} is registered under {string}")
    public void registerPartnerUnder(String code, String orgPath) {
        UUID orgUnitId = world.orgUnitId(orgPath);
        assertThat(orgUnitId).as("scenario has no unit %s", orgPath).isNotNull();
        attempt(() -> fleet.registerPartner(orgUnitId, world.uniqueCode(code), code + " Ltd",
                BusinessPartner.PartnerType.CUSTOMER, "TAX-" + code));
    }

    // ------------------------------------------------------------- policies

    @Given("access policy {string} is disabled")
    public void disablePolicy(String code) {
        AccessPolicy policy = policy(code);
        policies.revise(policy.id(), policy.effect(), policy.description(),
                conditionOf(policy.condition().value()), false);
    }

    @Given("access policy {string} denies {string} when:")
    public void denyPolicy(String code, String action, String condition) {
        policies.create(code, action, AccessPolicy.Effect.DENY, "Scenario rule " + code,
                conditionOf(condition), true);
    }

    @When("an access policy {string} is written for {string} with:")
    public void writePolicy(String code, String action, String condition) {
        failure = null;
        try {
            policies.create(code, action, AccessPolicy.Effect.DENY, "Scenario rule " + code,
                    conditionOf(condition), true);
        } catch (RuntimeException e) {
            failure = e;
        }
    }

    // ---------------------------------------------------------------- token

    @Given("{string} has an approval limit of {int}")
    public void userHasLimit(String email, int limit) {
        UUID userId = inTransaction(() -> jdbc.sql("SELECT id FROM app_user WHERE email = :email")
                .param("email", email)
                .query(UUID.class)
                .single());
        policies.setUserAttributes(userId,
                new SubjectAttributes(null, null, null, BigDecimal.valueOf(limit)));
    }

    @When("{string} signs in to tenant {string} with password {string}")
    public void signIn(String email, String tenantCode, String password) {
        session = auth.login(world.uniqueCode(tenantCode), email, password, null);
    }

    @When("the access token is refreshed")
    public void refresh() {
        session = auth.refresh(session.refreshToken());
    }

    // ----------------------------------------------------------- assertions

    @Then("the access policy allows it")
    public void allowed() {
        assertThat(failure)
                .as("the access policy should have allowed this, but: %s",
                        failure == null ? "" : failure.getMessage())
                .isNull();
    }

    @Then("the access policy refuses it")
    public void refused() {
        assertThat(failure)
                .as("the access policy should have refused this")
                .isInstanceOf(AccessDeniedException.class);
    }

    @Then("the tenant has access policy {string} on {string}")
    public void tenantHasPolicy(String code, String action) {
        AccessPolicy policy = policy(code);
        assertThat(policy.action()).isEqualTo(action);
        assertThat(policy.enabled()).isTrue();
    }

    @Then("writing the policy is refused mentioning {string}")
    public void writingRefused(String fragment) {
        assertThat(failure).as("the policy should have been refused").isNotNull();
        assertThat(failure.getMessage()).contains(fragment);
    }

    @Then("the bill listing shows {string} only")
    public void listingShowsOnly(String billNo) {
        assertThat(listedBillIds()).containsExactly(world.ref("bill:" + billNo));
    }

    @Then("the bill listing shows {string} and {string}")
    public void listingShowsBoth(String first, String second) {
        assertThat(listedBillIds()).containsExactlyInAnyOrder(
                world.ref("bill:" + first), world.ref("bill:" + second));
    }

    @Then("bill {string} cannot be seen")
    public void billHidden(String billNo) {
        assertThat(inTransaction(() -> finance.findById(world.ref("bill:" + billNo)))).isEmpty();
    }

    @Then("the lines of bill {string} cannot be seen")
    public void linesHidden(String billNo) {
        assertThat(inTransaction(() -> finance.linesOf(world.ref("bill:" + billNo)))).isEmpty();
    }

    @Then("the access token carries an approval limit of {int}")
    public void tokenCarriesLimit(int limit) {
        Jwt jwt = jwtDecoder.decode(session.accessToken());
        assertThat(new BigDecimal(jwt.getClaimAsString(SubjectAttributes.CLAIM_APPROVAL_LIMIT)))
                .isEqualByComparingTo(BigDecimal.valueOf(limit));
    }

    // -------------------------------------------------------------- helpers

    private List<UUID> listedBillIds() {
        return inTransaction(() -> finance.list(null, null, 200).items().stream()
                .map(FreightBillView::id)
                .toList());
    }

    private AccessPolicy policy(String code) {
        return inTransaction(() -> policyRepository
                .findByCode(TenantContext.requireTenantId(), code)
                .orElseThrow(() -> new AssertionError("Tenant has no access policy " + code)));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> conditionOf(String condition) {
        return json.readValue(condition, Map.class);
    }

    private void attempt(Runnable action) {
        failure = null;
        try {
            action.run();
        } catch (AccessDeniedException e) {
            failure = e;
        }
    }

    private static void signInWith(String who, SubjectAttributes attributes) {
        signInWith(who, AuthorityHooks.ALL_PERMISSIONS, attributes);
    }

    /**
     * Signs in with a verified-token-shaped principal carrying the attribute
     * claims exactly as {@code TokenService} would mint them.
     */
    private static void signInWith(String who, List<String> permissions,
                                   SubjectAttributes attributes) {
        Jwt.Builder token = Jwt.withTokenValue("acceptance-" + who)
                .header("alg", "RS256")
                .subject(who)
                .claim("perms", permissions);
        if (attributes.region() != null) {
            token.claim(SubjectAttributes.CLAIM_REGION, attributes.region());
        }
        if (attributes.freightModes() != null) {
            token.claim(SubjectAttributes.CLAIM_MODES, attributes.freightModes());
        }
        if (attributes.partnerId() != null) {
            token.claim(SubjectAttributes.CLAIM_PARTNER, attributes.partnerId().toString());
        }
        if (attributes.approvalLimit() != null) {
            token.claim(SubjectAttributes.CLAIM_APPROVAL_LIMIT,
                    attributes.approvalLimit().toPlainString());
        }
        Set<SimpleGrantedAuthority> authorities = permissions.stream()
                .map(SimpleGrantedAuthority::new)
                .collect(Collectors.toSet());
        SecurityContextHolder.getContext().setAuthentication(
                new JwtAuthenticationToken(token.build(), authorities, who));
    }

    private <T> T inTransaction(Supplier<T> action) {
        return new TransactionTemplate(transactionManager).execute(status -> action.get());
    }
}
