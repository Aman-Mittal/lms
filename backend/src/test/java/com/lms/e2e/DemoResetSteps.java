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

import java.util.UUID;

import com.lms.client.api.DemoDataApi;
import com.lms.client.api.PartnersApi;
import com.lms.client.model.DemoReset;
import com.lms.client.model.DemoResetRequest;
import com.lms.client.model.RegisterPartnerRequest;
import feign.FeignException;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import org.springframework.beans.factory.annotation.Value;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Resetting the demo over HTTP.
 *
 * <p>The suite runs against the demo tenant, so this scenario clears what the
 * scenarios before it left behind. None of them depends on another's data --
 * each builds its own under a salted code -- which is what makes that safe.
 */
public class DemoResetSteps {

    @Value("${local.server.port}")
    private int port;

    private HttpWorld world;
    private UUID partner;
    private UUID administrator;
    private DemoReset reset;

    @Given("the demo administrator is signed in to reset the demo")
    public void signIn() {
        world = new HttpWorld(port);
        world.signInAsDemoAdministrator();
        administrator = world.userId();
    }

    @Given("a visitor has registered a partner in the demo")
    public void visitorPartner() {
        partner = world.partner("RST", RegisterPartnerRequest.PartnerTypeEnum.CUSTOMER);
    }

    @When("the administrator resets the demo")
    public void resetDemo() {
        reset = world.api(DemoDataApi.class).resetDemoData(new DemoResetRequest().confirm(HttpWorld.DEMO_TENANT));
    }

    @Then("the reset reports rows deleted and asks the administrator to sign in again")
    public void reported() {
        assertThat(reset.getTenantCode()).isEqualTo(HttpWorld.DEMO_TENANT);
        assertThat(reset.getRowsDeleted()).isPositive();
        assertThat(reset.getSignInAgain()).isTrue();
    }

    @Then("the administrator signs in again with the same password")
    public void signInAgain() {
        world.signInAsDemoAdministrator();
        assertThat(world.userId()).as("the administrator was recreated").isNotEqualTo(administrator);
    }

    @Then("the visitor's partner is gone")
    public void partnerGone() {
        assertThatThrownBy(() -> world.api(PartnersApi.class).getPartner(partner))
                .isInstanceOfSatisfying(FeignException.class, e -> assertThat(e.status()).isEqualTo(404));
    }
}
