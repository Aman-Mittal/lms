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
import java.util.UUID;
import java.util.function.Supplier;

import com.lms.execution.command.TripCommandService;
import com.lms.order.command.OrderCommandService;
import com.lms.order.command.domain.SalesOrder;
import com.lms.order.api.MaterialClass;
import com.lms.planning.api.LoadLifecyclePort;
import com.lms.planning.command.PlanningCommandService;
import com.lms.shared.error.BusinessRuleViolationException;
import com.lms.shared.tenant.TenantContext;
import com.lms.sourcing.command.AllocationService;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Step definitions for {@code sourcing_lifecycle.feature}: what cancelling an
 * award or an order does to sourcing work.
 *
 * <p>Allocation, offer and load-status assertions are the ones
 * {@link SourcingSteps} and {@link PlanningSteps} already define; those work
 * on the allocation the latest "is allocated" step created. This class adds
 * what they cannot say: composite set-up, cancelling a chosen allocation, and
 * the history that cancelling leaves.
 */
public class SourcingLifecycleSteps {

    /** The lorry every scenario of the feature registers in its Background. */
    private static final String LORRY = "MH-01-SL-0001";

    @Autowired
    private ScenarioWorld world;
    @Autowired
    private OrderCommandService orderCommands;
    @Autowired
    private PlanningCommandService planning;
    @Autowired
    private AllocationService allocationService;
    @Autowired
    private LoadLifecyclePort loads;
    @Autowired
    private TripCommandService trips;
    @Autowired
    private JdbcClient jdbc;
    @Autowired
    private PlatformTransactionManager transactionManager;

    private RuntimeException failure;

    // ------------------------------------------------------------------ given

    @Given("a planned load {string} carrying order {string} of {int} kg")
    public void aPlannedLoad(String loadNo, String orderNo, int weightKg) {
        UUID orderId = orderOf(orderNo, weightKg);
        UUID loadId = planning.openLoad(world.orgUnitId(), world.uniqueCode(loadNo),
                world.ref("ORIGIN"), world.ref("vehicle:" + LORRY));
        world.putRef("load:" + loadNo, loadId);
        putOnLoad(orderId, loadId);
        planning.planLoad(loadId);
    }

    @Given("order {string} of {int} kg is added to planned load {string}")
    public void anotherOrderOnTheLoad(String orderNo, int weightKg, String loadNo) {
        putOnLoad(orderOf(orderNo, weightKg), world.ref("load:" + loadNo));
    }

    @Given("trip {string} is raised on awarded load {string}")
    public void aTripIsRaised(String tripNo, String loadNo) {
        world.putRef("trip:" + tripNo, trips.raiseTrip(world.uniqueCode(tripNo),
                world.ref("load:" + loadNo), Instant.now()));
    }

    @Given("trip {string} has been cancelled")
    public void tripCancelled(String tripNo) {
        trips.cancel(world.ref("trip:" + tripNo), "Cancelled for the scenario");
    }

    @Given("{string} has been tallied {int} awards this month")
    public void vendorTallied(String vendorCode, int expected) {
        tallyIs(vendorCode, expected);
    }

    // ------------------------------------------------------------------- when

    @When("the allocation of load {string} is cancelled")
    public void cancelAllocation(String loadNo) {
        UUID allocationId = allocationOf(loadNo, "DESC");
        attempt(() -> {
            allocationService.cancel(allocationId);
            return null;
        });
    }

    @When("the first allocation of load {string} is cancelled again")
    public void cancelFirstAllocationAgain(String loadNo) {
        UUID allocationId = allocationOf(loadNo, "ASC");
        attempt(() -> {
            allocationService.cancel(allocationId);
            return null;
        });
    }

    // ------------------------------------------------------------------- then

    @Then("cancelling the allocation is refused with {string}")
    public void cancellationRefused(String code) {
        assertThat(failure)
                .as("expected the cancellation to be refused with '%s' but it succeeded", code)
                .isInstanceOf(BusinessRuleViolationException.class);
        assertThat(((BusinessRuleViolationException) failure).code()).isEqualTo(code);
    }

    @Then("load {string} has no awarded vendor")
    public void noAwardedVendor(String loadNo) {
        assertThat(loads.summarise(world.ref("load:" + loadNo)).orElseThrow().awardedVendorPartnerId())
                .isNull();
    }

    @Then("load {string} has {int} allocations, of which {int} are {string}")
    public void allocationCounts(String loadNo, int total, int inStatus, String status) {
        UUID loadId = world.ref("load:" + loadNo);
        assertThat(count("SELECT count(*) FROM allocation WHERE load_id = :load", loadId, null))
                .isEqualTo(total);
        assertThat(count("SELECT count(*) FROM allocation WHERE load_id = :load AND status = :status",
                loadId, status)).isEqualTo(inStatus);
    }

    @Then("a further acceptance by {string} on load {string} is refused mentioning {string}")
    public void furtherAcceptanceRefused(String vendorCode, String loadNo, String fragment) {
        UUID allocationId = allocationOf(loadNo, "DESC");
        attempt(() -> {
            allocationService.accept(allocationId, world.ref(vendorCode));
            return null;
        });
        assertThat(failure).isInstanceOf(BusinessRuleViolationException.class);
        assertThat(failure.getMessage()).contains(fragment);
    }

    // ---------------------------------------------------------------- helpers

    private UUID orderOf(String orderNo, int weightKg) {
        UUID orderId = orderCommands.raiseOrder(world.orgUnitId(), world.ref("CUST"),
                world.uniqueCode(orderNo), world.ref("ORIGIN"), null, null,
                SalesOrder.OrderSource.MANUAL);
        world.putRef("order:" + orderNo, orderId);
        orderCommands.addLine(orderId, 1, "MAT-1", "General goods", MaterialClass.GENERAL, null,
                BigDecimal.ONE, "EA", BigDecimal.valueOf(weightKg), null, null, null, null,
                world.ref("CONS-A"), world.ref("DEST-A"));
        orderCommands.validate(orderId);
        return orderId;
    }

    private void putOnLoad(UUID orderId, UUID loadId) {
        for (UUID consignmentId : planning.generateConsignments(orderId)) {
            planning.assignConsignment(loadId, consignmentId);
        }
    }

    private UUID allocationOf(String loadNo, String direction) {
        UUID loadId = world.ref("load:" + loadNo);
        return inTransaction(() -> jdbc.sql("SELECT id FROM allocation WHERE load_id = :load "
                        + "ORDER BY created_at " + direction + " LIMIT 1")
                .param("load", loadId).query(UUID.class).single());
    }

    private int count(String sql, UUID loadId, String status) {
        return inTransaction(() -> {
            JdbcClient.StatementSpec spec = jdbc.sql(sql).param("load", loadId);
            if (status != null) {
                spec = spec.param("status", status);
            }
            return spec.query(Integer.class).single();
        });
    }

    private void tallyIs(String vendorCode, int expected) {
        UUID vendorId = world.ref(vendorCode);
        Integer awards = inTransaction(() -> jdbc.sql("""
                        SELECT coalesce(sum(awarded_count), 0)::int FROM vendor_award_tally
                         WHERE vendor_partner_id = :vendor
                        """).param("vendor", vendorId).query(Integer.class).single());
        assertThat(awards).isEqualTo(expected);
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
