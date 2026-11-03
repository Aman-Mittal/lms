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

import java.util.function.Supplier;

import com.lms.order.command.OrderCommandService;
import com.lms.planning.api.LoadLifecyclePort;
import com.lms.shared.error.BusinessRuleViolationException;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Step definitions for {@code lifecycle.feature}: what a trip's completion and
 * an order's cancellation do to the freight. Orders, loads, trips and
 * shipment assertions are the ones {@link PlanningSteps} and
 * {@link FreightModeSteps} already define.
 */
public class LifecycleSteps {

    @Autowired
    private ScenarioWorld world;
    @Autowired
    private OrderCommandService orderCommands;
    @Autowired
    private LoadLifecyclePort loads;

    private RuntimeException failure;

    @When("order {string} is cancelled")
    public void orderIsCancelled(String orderNo) {
        attempt(() -> {
            orderCommands.cancel(world.ref("order:" + orderNo), null);
            return null;
        });
    }

    @When("order {string} is cancelled with the reason {string}")
    public void orderIsCancelledWithReason(String orderNo, String reason) {
        attempt(() -> {
            orderCommands.cancel(world.ref("order:" + orderNo), reason);
            return null;
        });
    }

    @When("the completion of load {string} is recorded again")
    public void completionRecordedAgain(String loadNo) {
        loads.recordTripCompleted(world.ref("load:" + loadNo));
    }

    @Then("cancelling the order is refused with {string}")
    public void cancellationRefused(String code) {
        assertThat(failure)
                .as("expected the cancellation to be refused with '%s' but it succeeded", code)
                .isInstanceOf(BusinessRuleViolationException.class);
        assertThat(((BusinessRuleViolationException) failure).code()).isEqualTo(code);
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
}
