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
import java.util.UUID;

import com.lms.sourcing.command.RoutingGuideCommandService;
import com.lms.sourcing.command.domain.RoutingGuide;
import com.lms.sourcing.query.RoutingGuideView;
import com.lms.sourcing.query.SourcingQueryService;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/** Step definitions for {@code routing_guides.feature}. */
public class RoutingGuideSteps {

    @Autowired
    private ScenarioWorld world;
    @Autowired
    private RoutingGuideCommandService guides;
    @Autowired
    private SourcingQueryService sourcing;
    @Autowired
    private PlatformTransactionManager transactionManager;

    private UUID guideId;
    private RuntimeException failure;

    @When("the lane from {string} to {string} is given a routing guide")
    public void defineGuide(String origin, String destination) {
        attempt(() -> {
            UUID id = guides.define(world.orgUnitId(), world.ref(origin), world.ref(destination),
                    "RIGID", RoutingGuide.Strategy.CONTRACTUAL);
            guideId = id;
        });
    }

    @When("{string} is ranked {int} on that guide")
    public void rank(String vendor, int rank) {
        attempt(() -> guides.addVendor(guideId, world.ref(vendor), rank, 30,
                new BigDecimal("20000")));
    }

    @When("that guide is retired")
    public void retire() {
        attempt(() -> guides.retire(guideId));
    }

    @Then("that guide lists {string} at rank {int} and {string} at rank {int}")
    public void lists(String first, int firstRank, String second, int secondRank) {
        RoutingGuideView view = new TransactionTemplate(transactionManager)
                .execute(status -> sourcing.findGuide(guideId).orElseThrow());
        assertThat(view.entries()).extracting(RoutingGuideView.Entry::rankNo)
                .containsExactly(firstRank, secondRank);
        assertThat(view.entries()).extracting(RoutingGuideView.Entry::vendorPartnerId)
                .containsExactly(world.ref(first), world.ref(second));
    }

    @Then("the routing guide desk refuses mentioning {string}")
    public void refusedMentioning(String fragment) {
        assertThat(failure).as("the routing guide desk should have refused").isNotNull();
        assertThat(failure.getMessage()).contains(fragment);
    }

    @Then("the routing guide desk refuses it as unauthorised")
    public void refusedAsUnauthorised() {
        assertThat(failure).isInstanceOf(AccessDeniedException.class);
    }

    private void attempt(Runnable action) {
        failure = null;
        try {
            action.run();
        } catch (RuntimeException e) {
            failure = e;
        }
    }
}
