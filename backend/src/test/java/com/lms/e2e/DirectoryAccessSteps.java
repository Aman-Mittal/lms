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
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.lms.client.api.AccessPoliciesApi;
import com.lms.client.api.AuthenticationApi;
import com.lms.client.api.PartnersApi;
import com.lms.client.api.UsersApi;
import com.lms.client.model.AccessPolicy;
import com.lms.client.model.AuthResult;
import com.lms.client.model.CreateAccessPolicyRequest;
import com.lms.client.model.Partner;
import com.lms.client.model.RefreshRequest;
import com.lms.client.model.RegisterPartnerRequest;
import com.lms.client.model.ReviseAccessPolicyRequest;
import com.lms.client.model.SetUserAccessAttributesRequest;
import com.lms.client.model.User;
import com.lms.client.model.UserAccessAttributes;
import feign.FeignException;
import io.cucumber.java.After;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.JsonNode;

import static com.lms.e2e.DirectorySupport.refusedWith;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Who the users are, what they may do and how their session works, over HTTP:
 * the user directory bounded by organisational unit, the access attributes an
 * administrator sets and the rule that reads them, and the token lifecycle of
 * sign-in, refresh with rotation, and logout.
 *
 * <p>Everything here is provisioned for the scenario under salted names. The
 * only shared state it touches is the one rule the policy scenario writes,
 * and that is removed in an {@code @After} hook whatever the outcome; the rule
 * names a region only this scenario's user carries, so it could not refuse
 * anybody else even while it exists.
 */
public class DirectoryAccessSteps {

    @Value("${local.server.port}")
    private int port;
    @Autowired
    private JdbcClient jdbc;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private PasswordEncoder passwordEncoder;

    private HttpWorld world;
    private DirectorySupport support;
    private DirectorySupport.Cast cast;
    private DirectorySupport.Account branchUser;
    private UUID branchUnit;
    private String branchPath;
    private AuthResult branchSession;
    private String tag;
    private String region;

    private final List<UUID> policies = new ArrayList<>();
    private UUID customerPartner;
    private int registrations;

    // token lifecycle
    private AuthResult first;
    private AuthResult second;
    private AuthResult third;

    @After("@directory_policy")
    public void removeRules() {
        if (world == null) {
            return;
        }
        for (UUID policy : policies) {
            try {
                world.api(AccessPoliciesApi.class).deleteAccessPolicy(policy);
            } catch (FeignException e) {
                // Already deleted by the scenario itself.
            }
        }
    }

    @Given("the access directory is open to the demo administrator, a second tenant and a branch unit with a user of its own")
    public void open() {
        world = new HttpWorld(port);
        world.signInAsDemoAdministrator();
        support = new DirectorySupport(jdbc, transactionManager, passwordEncoder, port);
        cast = support.cast(world);
        tag = world.salt.toLowerCase();
        region = "E2E-" + world.salt;

        branchPath = cast.demo().getOrgPath() + "br-" + tag + "/";
        branchUnit = support.orgUnit(cast.demo().getTenantId(), cast.demo().getOrgUnitId(), branchPath,
                "Branch " + world.salt, "BRANCH");
        branchUser = support.user(cast.demo().getTenantId(), branchUnit, branchPath,
                "branch-" + tag + "@demo.local", "Branch User " + world.salt, "E2E-BRANCH-" + world.salt,
                "PARTNER_CREATE", "PARTNER_READ");
        branchSession = support.login(HttpWorld.DEMO_TENANT, branchUser.email());
    }

    private UsersApi users(String token) {
        return world.client(token, UsersApi.class);
    }

    private AccessPoliciesApi access() {
        return world.api(AccessPoliciesApi.class);
    }

    // ------------------------------------------------------------- users

    @Then("the user directory lists the whole tenant to its administrator but only the branch to the branch user")
    public void userListing() {
        JsonNode all = support.rawGet(cast.demo().getAccessToken(), "/api/v1/users");
        assertThat(all.isArray()).as("the listing is an array").isTrue();
        List<String> emails = DirectorySupport.texts(all, "email");
        assertThat(emails).contains(HttpWorld.DEMO_ADMIN, branchUser.email(),
                cast.readerAccount().email(), cast.nobodyAccount().email());
        assertThat(emails).doesNotContain(cast.other().adminEmail());

        JsonNode branch = null;
        for (JsonNode row : all) {
            if (row.get("email").asString().equals(branchUser.email())) {
                branch = row;
            }
        }
        assertThat(branch).isNotNull();
        assertThat(branch.get("id").asString()).isEqualTo(branchUser.id().toString());
        assertThat(branch.get("fullName").asString()).isEqualTo("Branch User " + world.salt);
        assertThat(branch.get("status").asString()).isEqualTo("ACTIVE");
        assertThat(branch.get("orgPath").asString()).isEqualTo(branchPath);
        // The server names the unit "orgName"; the contract says "orgUnitName".
        assertThat(branch.get("orgName").asString()).isEqualTo("Branch " + world.salt);

        JsonNode own = support.rawGet(branchSession.getAccessToken(), "/api/v1/users");
        assertThat(DirectorySupport.texts(own, "email")).containsExactly(branchUser.email());
    }

    @Then("a user reads back by id with name, status, path, unit and last sign-in")
    public void userReadsBack() {
        User user = users(cast.demo().getAccessToken()).getUser(branchUser.id());
        assertThat(user.getId()).isEqualTo(branchUser.id());
        assertThat(user.getEmail()).isEqualTo(branchUser.email());
        assertThat(user.getFullName()).isEqualTo("Branch User " + world.salt);
        assertThat(user.getStatus()).isEqualTo(User.StatusEnum.ACTIVE);
        assertThat(user.getOrgPath()).isEqualTo(branchPath);
        assertThat(user.getLastLoginAt()).as("signed in during provisioning")
                .isAfter(OffsetDateTime.now().minusMinutes(5));

        User admin = users(branchSession.getAccessToken()).getUser(world.userId());
        assertThat(admin.getEmail()).isEqualTo(HttpWorld.DEMO_ADMIN);
    }

    @Then("an unknown user and another tenant's user are 404 problems and a request with no token is unauthorised")
    public void userRefusals() {
        UsersApi api = users(cast.demo().getAccessToken());
        refusedWith(404, "not-found", () -> api.getUser(UUID.randomUUID()));
        refusedWith(404, "not-found", () -> api.getUser(cast.otherAdmin().getUserId()));
        refusedWith(404, "not-found", () -> users(cast.otherAdmin().getAccessToken()).getUser(branchUser.id()));

        FeignException unauthenticated = null;
        try {
            world.client(null, UsersApi.class).getUser(branchUser.id());
        } catch (FeignException e) {
            unauthenticated = e;
        }
        assertThat(unauthenticated).isNotNull();
        assertThat(unauthenticated.status()).isEqualTo(401);
    }

    @Then("the permissions of a user are exactly those its roles grant")
    public void userPermissions() {
        UsersApi api = users(cast.demo().getAccessToken());
        assertThat(api.getUserPermissions(cast.readerAccount().id()))
                .containsExactly("DRIVER_READ", "PARTNER_READ", "TERMINAL_READ", "VEHICLE_READ");
        assertThat(api.getUserPermissions(branchUser.id())).containsExactly("PARTNER_CREATE", "PARTNER_READ");
        assertThat(api.getUserPermissions(cast.nobodyAccount().id())).isEmpty();

        List<String> admin = api.getUserPermissions(world.userId());
        assertThat(admin).contains("ACCESS_POLICY_MANAGE", "PARTNER_CREATE", "VEHICLE_READ", "TERMINAL_CREATE")
                .isSorted();
        assertThat(admin).as("what the token says is what the directory says")
                .containsExactlyInAnyOrderElementsOf(cast.demo().getPermissions());

        assertThat(api.getUserPermissions(UUID.randomUUID())).as("nobody, so nothing").isEmpty();
        assertThat(api.getUserPermissions(cast.otherAdmin().getUserId()))
                .as("another tenant's roles are invisible").isEmpty();
    }

    // -------------------------------------------------- access attributes

    @Then("a user's access attributes start out unrestricted")
    public void attributesStartEmpty() {
        UserAccessAttributes attributes = access().getUserAccessAttributes(branchUser.id());
        assertThat(attributes.getUserId()).isEqualTo(branchUser.id());
        assertThat(attributes.getRegion()).isNull();
        assertThat(attributes.getFreightModes()).isNullOrEmpty();
        assertThat(attributes.getPartnerId()).isNull();
        assertThat(attributes.getApprovalLimit()).isNull();
    }

    @When("the administrator restricts the branch user to a region, two freight modes, a partner and an approval limit")
    public void restrict() {
        customerPartner = world.partner("ATTR", RegisterPartnerRequest.PartnerTypeEnum.CUSTOMER);
        access().setUserAccessAttributes(branchUser.id(), new SetUserAccessAttributesRequest()
                .region(region)
                .freightModes(List.of(SetUserAccessAttributesRequest.FreightModesEnum.FTL,
                        SetUserAccessAttributesRequest.FreightModesEnum.RAIL))
                .partnerId(customerPartner)
                .approvalLimit(new BigDecimal("12500.50")));
    }

    @Then("the attributes read back exactly as set")
    public void attributesReadBack() {
        UserAccessAttributes attributes = access().getUserAccessAttributes(branchUser.id());
        assertThat(attributes.getUserId()).isEqualTo(branchUser.id());
        assertThat(attributes.getRegion()).isEqualTo(region);
        assertThat(attributes.getFreightModes()).containsExactly(
                UserAccessAttributes.FreightModesEnum.FTL, UserAccessAttributes.FreightModesEnum.RAIL);
        assertThat(attributes.getPartnerId()).isEqualTo(customerPartner);
        assertThat(attributes.getApprovalLimit()).isEqualByComparingTo("12500.50");
    }

    @Then("the branch user's current token does not carry them but a refreshed one and a new sign-in both do")
    public void attributesReachTheNextToken() {
        JsonNode before = DirectorySupport.claims(branchSession.getAccessToken());
        assertThat(before.has("region")).as("issued before the change").isFalse();
        assertThat(before.has("approvalLimit")).isFalse();

        AuthResult refreshed = world.client(null, AuthenticationApi.class)
                .refreshToken(new RefreshRequest().refreshToken(branchSession.getRefreshToken()));
        AuthResult signedIn = support.login(HttpWorld.DEMO_TENANT, branchUser.email());
        for (AuthResult session : List.of(refreshed, signedIn)) {
            JsonNode claims = DirectorySupport.claims(session.getAccessToken());
            assertThat(claims.get("region").asString()).isEqualTo(region);
            List<String> modes = new ArrayList<>();
            for (JsonNode mode : claims.get("modes")) {
                modes.add(mode.asString());
            }
            assertThat(modes).containsExactly("FTL", "RAIL");
            assertThat(claims.get("partner").asString()).isEqualTo(customerPartner.toString());
            assertThat(claims.get("approvalLimit").asString()).isEqualTo("12500.50");
        }
    }

    @Then("setting the attributes again replaces the whole set so a field left out is cleared")
    public void attributesAreReplaced() {
        access().setUserAccessAttributes(branchUser.id(), new SetUserAccessAttributesRequest()
                .region("SECOND-" + world.salt).freightModes(null));
        UserAccessAttributes attributes = access().getUserAccessAttributes(branchUser.id());
        assertThat(attributes.getRegion()).isEqualTo("SECOND-" + world.salt);
        assertThat(attributes.getFreightModes()).isNullOrEmpty();
        assertThat(attributes.getPartnerId()).isNull();
        assertThat(attributes.getApprovalLimit()).isNull();

        access().setUserAccessAttributes(branchUser.id(),
                new SetUserAccessAttributesRequest().freightModes(null));
        UserAccessAttributes cleared = access().getUserAccessAttributes(branchUser.id());
        assertThat(cleared.getRegion()).isNull();
        assertThat(cleared.getFreightModes()).isNullOrEmpty();
    }

    @Then("a malformed region, a negative limit and a partner that does not exist are 400 problems that change nothing")
    public void invalidAttributes() {
        access().setUserAccessAttributes(branchUser.id(),
                new SetUserAccessAttributesRequest().region(region).freightModes(null));

        refusedWith(400, "invalid-argument", () -> access().setUserAccessAttributes(branchUser.id(),
                new SetUserAccessAttributesRequest().region("not a valid region").freightModes(null)));
        refusedWith(400, "invalid-argument", () -> access().setUserAccessAttributes(branchUser.id(),
                new SetUserAccessAttributesRequest().region("OK-" + world.salt).freightModes(null)
                        .approvalLimit(new BigDecimal("-1"))));
        refusedWith(400, "invalid-argument", () -> access().setUserAccessAttributes(branchUser.id(),
                new SetUserAccessAttributesRequest().region("OK-" + world.salt).freightModes(null)
                        .partnerId(UUID.randomUUID())));

        UserAccessAttributes attributes = access().getUserAccessAttributes(branchUser.id());
        assertThat(attributes.getRegion()).as("the earlier setting stands").isEqualTo(region);
        assertThat(attributes.getApprovalLimit()).isNull();
        assertThat(attributes.getPartnerId()).isNull();
    }

    @Then("attributes of an unknown user and of another tenant's user are 404 problems for both reading and setting")
    public void attributesOfStrangers() {
        UUID theirs = cast.otherAdmin().getUserId();
        SetUserAccessAttributesRequest request =
                new SetUserAccessAttributesRequest().region("NOPE").freightModes(null);
        refusedWith(404, "not-found", () -> access().getUserAccessAttributes(UUID.randomUUID()));
        refusedWith(404, "not-found", () -> access().getUserAccessAttributes(theirs));
        refusedWith(404, "not-found", () -> access().setUserAccessAttributes(UUID.randomUUID(), request));
        refusedWith(404, "not-found", () -> access().setUserAccessAttributes(theirs, request));
        assertThat(world.client(cast.otherAdmin().getAccessToken(), AccessPoliciesApi.class)
                .getUserAccessAttributes(theirs).getRegion()).as("their attributes were not touched").isNull();
    }

    @Then("reading or setting attributes is refused as 403 to users without the access-policy permission")
    public void attributesNeedPermission() {
        SetUserAccessAttributesRequest request =
                new SetUserAccessAttributesRequest().region("DENIED").freightModes(null);
        for (String token : List.of(cast.reader().getAccessToken(), cast.nobody().getAccessToken(),
                branchSession.getAccessToken())) {
            AccessPoliciesApi api = world.client(token, AccessPoliciesApi.class);
            refusedWith(403, "access-denied", () -> api.getUserAccessAttributes(branchUser.id()));
            refusedWith(403, "access-denied", () -> api.setUserAccessAttributes(branchUser.id(), request));
            refusedWith(403, "access-denied", () -> api.listAccessPolicies(null, null, null));
        }
        assertThat(access().getUserAccessAttributes(branchUser.id()).getRegion()).isNotEqualTo("DENIED");
    }

    // ------------------------------------------------------ the rule's bite

    @When("two rules are written for partner registration: one denying anyone in the branch user's region, one switched off")
    public void writeRules() {
        policies.add(access().createAccessPolicy(new CreateAccessPolicyRequest()
                .code(policyCode("a")).action("PARTNER_CREATE")
                .effect(CreateAccessPolicyRequest.EffectEnum.DENY)
                .description("End-to-end: nobody in region " + region + " registers a partner")
                .condition(Map.of("attr", "subject.region", "op", "eq", "value", region)), null).getId());
        policies.add(access().createAccessPolicy(new CreateAccessPolicyRequest()
                .code(policyCode("b")).action("PARTNER_CREATE")
                .effect(CreateAccessPolicyRequest.EffectEnum.DENY)
                .description("End-to-end: a rule that is off")
                .condition(Map.of("attr", "subject.region", "op", "eq", "value", "NOWHERE-" + world.salt))
                .enabled(false), null).getId());
    }

    private String policyCode(String suffix) {
        return "e2e-dir-" + tag + "-" + suffix;
    }

    @Then("the rules are listed in code order with their action, effect, condition and switch, and filterable by action")
    public void rulesListed() {
        AccessPoliciesApi api = access();
        String cursor = "e2e-dir-" + tag;
        var firstPage = api.listAccessPolicies(null, cursor, 1);
        assertThat(firstPage.getItems()).extracting(AccessPolicy::getId).containsExactly(policies.get(0));
        assertThat(firstPage.getHasMore()).isTrue();
        assertThat(firstPage.getNextCursor()).isEqualTo(policyCode("a"));
        var secondPage = api.listAccessPolicies(null, firstPage.getNextCursor(), 1);
        assertThat(secondPage.getItems()).extracting(AccessPolicy::getId).containsExactly(policies.get(1));

        AccessPolicy denying = firstPage.getItems().get(0);
        assertThat(denying.getCode()).isEqualTo(policyCode("a"));
        assertThat(denying.getAction()).isEqualTo("PARTNER_CREATE");
        assertThat(denying.getEffect()).isEqualTo(AccessPolicy.EffectEnum.DENY);
        assertThat(denying.getEnabled()).isTrue();
        assertThat(denying.getCondition()).containsEntry("attr", "subject.region")
                .containsEntry("op", "eq").containsEntry("value", region);
        assertThat(denying.getCreatedAt()).isNotNull();
        assertThat(secondPage.getItems().get(0).getEnabled()).isFalse();

        // The tenant's built-in rules for the action come back too; ours keep their order among them.
        assertThat(api.listAccessPolicies("PARTNER_CREATE", cursor, 5).getItems())
                .extracting(AccessPolicy::getId).containsSubsequence(policies.get(0), policies.get(1));
        assertThat(api.listAccessPolicies("INVOICE_APPROVE", cursor, 5).getItems())
                .extracting(AccessPolicy::getId).doesNotContainAnyElementsOf(policies);
    }

    private UUID branchRegistersPartner(String token) {
        PartnersApi partners = world.client(token, PartnersApi.class);
        String code = "BRP-" + world.salt + "-" + (++registrations);
        UUID id = partners.registerPartner(new RegisterPartnerRequest().orgUnitId(branchUnit).code(code)
                .legalName(code + " Ltd").partnerType(RegisterPartnerRequest.PartnerTypeEnum.CUSTOMER), null)
                .getId();
        assertThat(partners.getPartner(id).getCode()).isEqualTo(code);
        return id;
    }

    private void branchIsRefused(String token) {
        PartnersApi partners = world.client(token, PartnersApi.class);
        String code = "BRP-" + world.salt + "-refused-" + (++registrations);
        refusedWith(403, "access-denied", () -> partners.registerPartner(new RegisterPartnerRequest()
                .orgUnitId(branchUnit).code(code).legalName(code + " Ltd")
                .partnerType(RegisterPartnerRequest.PartnerTypeEnum.CUSTOMER), null));
        assertThat(world.api(PartnersApi.class).listPartners(null, null, code.substring(0, code.length() - 1), 5)
                .getItems()).extracting(Partner::getCode).doesNotContain(code);
    }

    @Then("the branch user, who carries no region yet, registers a partner under its own unit")
    public void branchRegistersBeforeRestriction() {
        branchRegistersPartner(branchSession.getAccessToken());
    }

    @When("the administrator puts the branch user in that region")
    public void putInRegion() {
        access().setUserAccessAttributes(branchUser.id(),
                new SetUserAccessAttributesRequest().region(region).freightModes(null));
    }

    @Then("the token the branch user already holds still registers a partner because attributes apply from the next token")
    public void existingTokenUnaffected() {
        branchRegistersPartner(branchSession.getAccessToken());
    }

    @Then("after a refresh the branch user is refused as forbidden, while the administrator and the branch user's reads are not")
    public void refreshedTokenRefused() {
        AuthResult refreshed = world.client(null, AuthenticationApi.class)
                .refreshToken(new RefreshRequest().refreshToken(branchSession.getRefreshToken()));
        branchSession = refreshed;
        branchIsRefused(refreshed.getAccessToken());

        // The rule bites on the action and the region, not on the person's other rights.
        assertThat(world.client(refreshed.getAccessToken(), PartnersApi.class)
                .listPartners(null, null, "BRP-" + world.salt, 3).getItems()).isNotEmpty();
        assertThat(world.partner("ADMINOK", RegisterPartnerRequest.PartnerTypeEnum.CUSTOMER)).isNotNull();
    }

    @When("the administrator switches the denying rule off")
    public void switchRuleOff() {
        AccessPolicy rule = access().getAccessPolicy(policies.get(0));
        access().reviseAccessPolicy(policies.get(0), new ReviseAccessPolicyRequest()
                .effect(ReviseAccessPolicyRequest.EffectEnum.DENY).description(rule.getDescription())
                .condition(rule.getCondition()).enabled(false));
    }

    @Then("the very same token registers a partner again, and switching the rule back on refuses it again")
    public void ruleIsData() {
        branchRegistersPartner(branchSession.getAccessToken());

        AccessPolicy rule = access().getAccessPolicy(policies.get(0));
        assertThat(rule.getEnabled()).isFalse();
        access().reviseAccessPolicy(policies.get(0), new ReviseAccessPolicyRequest()
                .effect(ReviseAccessPolicyRequest.EffectEnum.DENY).description(rule.getDescription())
                .condition(rule.getCondition()).enabled(true));
        branchIsRefused(branchSession.getAccessToken());
    }

    @Then("clearing the branch user's region and refreshing lifts the refusal")
    public void clearingLifts() {
        access().setUserAccessAttributes(branchUser.id(), new SetUserAccessAttributesRequest().freightModes(null));
        AuthResult refreshed = world.client(null, AuthenticationApi.class)
                .refreshToken(new RefreshRequest().refreshToken(branchSession.getRefreshToken()));
        assertThat(DirectorySupport.claims(refreshed.getAccessToken()).has("region")).isFalse();
        branchRegistersPartner(refreshed.getAccessToken());
    }

    // ---------------------------------------------------- token lifecycle

    private AuthenticationApi auth() {
        return world.client(null, AuthenticationApi.class);
    }

    @When("the branch user signs in")
    public void signIn() {
        first = support.login(HttpWorld.DEMO_TENANT, branchUser.email());
        assertThat(first.getAccessToken()).isNotBlank();
        assertThat(first.getRefreshToken()).isNotBlank().isNotEqualTo(branchSession.getRefreshToken());
        assertThat(first.getUserId()).isEqualTo(branchUser.id());
        assertThat(first.getTenantId()).isEqualTo(cast.demo().getTenantId());
        assertThat(first.getOrgUnitId()).isEqualTo(branchUnit);
        assertThat(first.getOrgPath()).isEqualTo(branchPath);
        assertThat(first.getPermissions()).containsExactlyInAnyOrder("PARTNER_CREATE", "PARTNER_READ");
        assertThat(first.getAccessTokenExpiresAt()).isAfter(OffsetDateTime.now());
        assertThat(first.getRefreshTokenExpiresAt()).isAfter(first.getAccessTokenExpiresAt());
        JsonNode claims = DirectorySupport.claims(first.getAccessToken());
        assertThat(claims.get("sub").asString()).isEqualTo(branchUser.id().toString());
        assertThat(claims.get("tenant").asString()).isEqualTo(cast.demo().getTenantId().toString());
        assertThat(claims.get("org").asString()).isEqualTo(branchPath);
    }

    @Then("refreshing exchanges the refresh token for a new pair that works")
    public void refreshWorks() {
        second = auth().refreshToken(new RefreshRequest().refreshToken(first.getRefreshToken()));
        assertThat(second.getRefreshToken()).as("rotated").isNotBlank().isNotEqualTo(first.getRefreshToken());
        assertThat(second.getUserId()).isEqualTo(branchUser.id());
        assertThat(second.getTenantId()).isEqualTo(first.getTenantId());
        assertThat(second.getOrgPath()).isEqualTo(branchPath);
        assertThat(second.getPermissions()).containsExactlyInAnyOrderElementsOf(first.getPermissions());
        assertThat(second.getAccessTokenExpiresAt()).isAfter(OffsetDateTime.now());
        assertThat(users(second.getAccessToken()).getUser(branchUser.id()).getEmail())
                .isEqualTo(branchUser.email());
    }

    @Then("the refresh token that was exchanged is refused as unauthorised, while its successor still works")
    public void oldTokenRefused() {
        JsonNode problem = support.rawRefresh(first.getRefreshToken())
                .refused(401, "authentication-failed").body();
        assertThat(problem.get("detail").asString()).containsIgnoringCase("invalid or expired");

        third = auth().refreshToken(new RefreshRequest().refreshToken(second.getRefreshToken()));
        assertThat(third.getRefreshToken()).isNotEqualTo(second.getRefreshToken());
        support.rawRefresh(second.getRefreshToken()).refused(401, "authentication-failed");
    }

    @Then("a refresh token that never existed is unauthorised and a blank one is a 400 problem naming the field")
    public void garbageRefresh() {
        support.rawRefresh("never-issued-" + world.salt).refused(401, "authentication-failed");
        JsonNode problem = refusedWith(400, "validation-failed",
                () -> auth().refreshToken(new RefreshRequest().refreshToken("")));
        assertThat(problem.get("errors").has("refreshToken")).isTrue();
        problem = refusedWith(400, "validation-failed",
                () -> world.client(first.getAccessToken(), AuthenticationApi.class)
                        .logout(new RefreshRequest().refreshToken(" ")));
        assertThat(problem.get("errors").has("refreshToken")).isTrue();
    }

    @Then("logging out revokes that refresh token, leaves other sessions alone and is harmless to repeat")
    public void logoutRevokes() {
        AuthResult other = support.login(HttpWorld.DEMO_TENANT, branchUser.email());

        AuthenticationApi signedIn = world.client(third.getAccessToken(), AuthenticationApi.class);
        signedIn.logout(new RefreshRequest().refreshToken(third.getRefreshToken()));
        support.rawRefresh(third.getRefreshToken()).refused(401, "authentication-failed");

        signedIn.logout(new RefreshRequest().refreshToken(third.getRefreshToken()));
        signedIn.logout(new RefreshRequest().refreshToken("never-issued-" + world.salt));

        // Logging out is itself an authenticated call.
        assertThat(support.rawPost(null, "/api/v1/auth/logout",
                "{\"refreshToken\":\"" + other.getRefreshToken() + "\"}").status()).isEqualTo(401);

        AuthResult stillGood = auth().refreshToken(new RefreshRequest().refreshToken(other.getRefreshToken()));
        assertThat(stillGood.getUserId()).isEqualTo(branchUser.id());

        // A signed access token is not revoked: it simply runs out.
        assertThat(users(third.getAccessToken()).getUser(branchUser.id()).getEmail())
                .isEqualTo(branchUser.email());
    }

    @Then("suspending the branch user refuses its refresh token and its sign-in, and reinstating restores both")
    public void suspension() {
        AuthResult session = support.login(HttpWorld.DEMO_TENANT, branchUser.email());
        support.setStatus(cast.demo().getTenantId(), cast.demo().getOrgPath(), branchUser.id(), "SUSPENDED");
        try {
            assertThat(users(cast.demo().getAccessToken()).getUser(branchUser.id()).getStatus())
                    .isEqualTo(User.StatusEnum.SUSPENDED);
            support.rawRefresh(session.getRefreshToken()).refused(401, "authentication-failed");
            support.rawLogin(HttpWorld.DEMO_TENANT, branchUser.email(), DirectorySupport.PASSWORD)
                    .refused(401, "authentication-failed");
        } finally {
            support.setStatus(cast.demo().getTenantId(), cast.demo().getOrgPath(), branchUser.id(), "ACTIVE");
        }
        assertThat(users(cast.demo().getAccessToken()).getUser(branchUser.id()).getStatus())
                .isEqualTo(User.StatusEnum.ACTIVE);
        assertThat(support.login(HttpWorld.DEMO_TENANT, branchUser.email()).getUserId())
                .isEqualTo(branchUser.id());
    }

    @Then("five wrong passwords lock the branch user out even against the right one, and the refusal never says which part was wrong")
    public void lockout() {
        String generic = null;
        for (int attempt = 1; attempt <= 5; attempt++) {
            generic = support.rawLogin(HttpWorld.DEMO_TENANT, branchUser.email(), "wrong password")
                    .refused(401, "authentication-failed").body().get("detail").asString();
        }
        JsonNode locked = support.rawLogin(HttpWorld.DEMO_TENANT, branchUser.email(), DirectorySupport.PASSWORD)
                .refused(401, "authentication-failed").body();
        assertThat(locked.get("detail").asString()).isEqualTo(generic);

        JsonNode unknownEmail = support.rawLogin(HttpWorld.DEMO_TENANT, "nobody-" + tag + "@demo.local",
                "anything").refused(401, "authentication-failed").body();
        JsonNode unknownTenant = support.rawLogin("no-such-tenant-" + tag, branchUser.email(), "anything")
                .refused(401, "authentication-failed").body();
        assertThat(unknownEmail.get("detail").asString()).isEqualTo(generic);
        assertThat(unknownTenant.get("detail").asString()).isEqualTo(generic);

        // Other people are unaffected by one account's lockout.
        assertThat(support.login(HttpWorld.DEMO_TENANT, cast.readerAccount().email()).getUserId())
                .isEqualTo(cast.readerAccount().id());
    }
}
