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

import java.util.Map;
import java.util.UUID;

import com.lms.client.api.AccessPoliciesApi;
import com.lms.client.model.CreateAccessPolicyRequest;
import com.lms.client.model.RegisterPartnerRequest;
import com.lms.client.model.ReviseAccessPolicyRequest;
import feign.FeignException;
import io.cucumber.java.After;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import org.springframework.beans.factory.annotation.Value;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Attribute-based access control over HTTP: a tenant administrator writes a
 * rule as data and the next request obeys it, with no restart and no deploy.
 *
 * <p>The demo tenant is shared by every end-to-end scenario, so the rule this
 * one writes is removed in an {@code @After} hook whatever the scenario's
 * outcome; a rule left behind would refuse every later scenario's first
 * partner.
 */
public class AccessPolicySteps {

    private static final Map<String, Object> ANYONE_WITH_AN_ORG_PATH =
            Map.of("attr", "subject.orgPath", "op", "present");

    @Value("${local.server.port}")
    private int port;

    private HttpWorld world;
    private UUID policyId;

    @After("@abac")
    public void removePolicy() {
        if (policyId != null && world != null) {
            try {
                world.api(AccessPoliciesApi.class).deleteAccessPolicy(policyId);
            } catch (FeignException e) {
                // Already deleted by the scenario itself.
            }
        }
    }

    @Given("the demo administrator is signed in to manage access policies")
    public void signIn() {
        world = new HttpWorld(port);
        world.signInAsDemoAdministrator();
    }

    @When("a rule is added denying partner registration to everyone who has an organisational path")
    public void addDenyRule() {
        policyId = world.api(AccessPoliciesApi.class).createAccessPolicy(
                new CreateAccessPolicyRequest()
                        .code("e2e-deny-" + world.salt.toLowerCase())
                        .action("PARTNER_CREATE")
                        .effect(CreateAccessPolicyRequest.EffectEnum.DENY)
                        .description("End-to-end: nobody registers a partner")
                        .condition(ANYONE_WITH_AN_ORG_PATH), null).getId();
    }

    @Then("registering a partner is refused as forbidden")
    public void registrationRefused() {
        assertThatThrownBy(this::registerPartner)
                .isInstanceOfSatisfying(FeignException.class,
                        e -> assertThat(e.status()).isEqualTo(403));
    }

    @When("the rule is switched off")
    public void switchOff() {
        var rule = world.api(AccessPoliciesApi.class).getAccessPolicy(policyId);
        world.api(AccessPoliciesApi.class).reviseAccessPolicy(policyId,
                new ReviseAccessPolicyRequest()
                        .effect(ReviseAccessPolicyRequest.EffectEnum.DENY)
                        .description(rule.getDescription())
                        .condition(ANYONE_WITH_AN_ORG_PATH)
                        .enabled(false));
    }

    @Then("a partner can be registered again")
    public void registrationWorks() {
        assertThat(registerPartner()).isNotNull();
    }

    @When("the rule is deleted")
    public void delete() {
        world.api(AccessPoliciesApi.class).deleteAccessPolicy(policyId);
    }

    @Then("the rule can no longer be found")
    public void gone() {
        assertThatThrownBy(() -> world.api(AccessPoliciesApi.class).getAccessPolicy(policyId))
                .isInstanceOfSatisfying(FeignException.class,
                        e -> assertThat(e.status()).isEqualTo(404));
    }

    @Then("a rule naming a permission that does not exist is refused")
    public void unknownPermission() {
        assertThatThrownBy(() -> world.api(AccessPoliciesApi.class).createAccessPolicy(
                new CreateAccessPolicyRequest()
                        .code("e2e-bogus-" + world.salt.toLowerCase())
                        .action("NO_SUCH_PERMISSION")
                        .effect(CreateAccessPolicyRequest.EffectEnum.DENY)
                        .description("End-to-end: a typo")
                        .condition(ANYONE_WITH_AN_ORG_PATH), null))
                .isInstanceOfSatisfying(FeignException.class,
                        e -> assertThat(e.status()).isBetween(400, 409));
    }

    private UUID registerPartner() {
        return world.partner("ABAC", RegisterPartnerRequest.PartnerTypeEnum.CUSTOMER);
    }
}
