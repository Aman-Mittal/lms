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
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import com.lms.clientbilling.command.ClientContractLifecycleService;
import com.lms.clientbilling.command.ClientContractService;
import com.lms.clientbilling.command.LaneTerm;
import com.lms.clientbilling.command.domain.ClientContract;
import com.lms.clientbilling.command.domain.ContractLane;
import com.lms.order.command.OrderCommandService;
import com.lms.order.command.OrderSettingDefinitions;
import com.lms.settings.api.Resolved;
import com.lms.settings.api.ScopeType;
import com.lms.settings.api.SettingContext;
import com.lms.settings.api.Settings;
import com.lms.settings.command.SettingProfileService;
import com.lms.settings.command.SettingRegistry;
import com.lms.shared.error.BusinessRuleViolationException;
import io.cucumber.java.Before;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Step definitions for {@code contract_lanes.feature}: a contract with a lane
 * in force, an order raised and validated under it, and what the order records
 * or is refused with. Partners, depots, orders and their lines use the steps
 * {@link PlanningSteps} already defines.
 */
public class ContractLaneSteps {

    @Autowired
    private ScenarioWorld world;
    @Autowired
    private ClientContractLifecycleService lifecycle;
    @Autowired
    private OrderCommandService orderCommands;
    @Autowired
    private SettingProfileService profiles;
    @Autowired
    private Settings settings;
    @Autowired
    private JdbcClient jdbc;
    @Autowired
    private PlatformTransactionManager transactionManager;

    private RuntimeException failure;

    @Before
    public void resetContractLaneState() {
        failure = null;
    }

    // ------------------------------------------------------------------ given

    @Given("contract {string} for {string} is in force with a lane {string} from {string} to {string} at {int} per tonne in {int} days")
    public void inForce(String code, String clientCode, String laneCode, String from, String to, int perTonne,
                        int days) {
        activate(code, clientCode, true, laneCode, from, to, perTonne, days);
    }

    @Given("contract {string} for {string} refusing direct orders is in force with a lane {string} from {string} to {string} at {int} per tonne in {int} days")
    public void inForceRefusingDirectOrders(String code, String clientCode, String laneCode, String from, String to,
                                            int perTonne, int days) {
        activate(code, clientCode, false, laneCode, from, to, perTonne, days);
    }

    @Given("a contract profile for {string} sets the close tolerance to {int} percent")
    public void contractProfile(String code, int percent) {
        profiles.create(new SettingProfileService.Scope(ScopeType.CONTRACT, family(code), null, null),
                world.uniqueCode(code), List.of(new SettingRegistry.Requested(
                        OrderSettingDefinitions.CLOSE_TOLERANCE.name(), null, BigDecimal.valueOf(percent))));
    }

    // ------------------------------------------------------------------- when

    @When("order {string} is checked against its contract")
    public void checked(String orderNo) {
        failure = null;
        try {
            orderCommands.validate(world.ref("order:" + orderNo));
        } catch (RuntimeException e) {
            failure = e;
        }
    }

    // ------------------------------------------------------------------- then

    @Then("the contract check is refused with {string}")
    public void refused(String code) {
        assertThat(failure).as("the check should have been refused").isInstanceOf(BusinessRuleViolationException.class);
        assertThat(((BusinessRuleViolationException) failure).code()).isEqualTo(code);
    }

    @Then("order {string} was checked and passed")
    public void passed(String orderNo) {
        assertThat(failure).as("the check should not have been refused").isNull();
        assertThat(order(orderNo, "status")).isEqualTo("VALIDATED");
    }

    @Then("order {string} was resolved against contract {string}")
    public void resolvedAgainst(String orderNo, String code) {
        assertThat(inTransaction(() -> jdbc.sql("""
                        SELECT k.code FROM sales_order o JOIN client_contract k ON k.id = o.contract_id
                         WHERE o.id = :id
                        """).param("id", world.ref("order:" + orderNo)).query(String.class).optional().orElse(null)))
                .isEqualTo(world.uniqueCode(code));
    }

    @Then("order {string} has no contract")
    public void noContract(String orderNo) {
        assertThat(order(orderNo, "contract_id::text")).isNull();
    }

    @Then("order {string} travels on lane {string}")
    public void travelsOn(String orderNo, String laneCode) {
        assertThat(inTransaction(() -> jdbc.sql("""
                        SELECT l.lane_code FROM sales_order o JOIN client_contract_lane l ON l.id = o.contract_lane_id
                         WHERE o.id = :id
                        """).param("id", world.ref("order:" + orderNo)).query(String.class).optional().orElse(null)))
                .isEqualTo(laneCode);
    }

    @Then("order {string} travels on no lane")
    public void travelsOnNoLane(String orderNo) {
        assertThat(order(orderNo, "contract_lane_id::text")).isNull();
    }

    @Then("order {string} is promised in {int} days")
    public void promised(String orderNo, int days) {
        assertThat(order(orderNo, "transit_days::text")).isEqualTo(String.valueOf(days));
    }

    @Then("the close tolerance for contract {string} is {int} percent from {string}")
    public void toleranceFor(String code, int percent, String label) {
        Resolved<BigDecimal> resolved = inTransaction(() -> settings.resolve(OrderSettingDefinitions.CLOSE_TOLERANCE,
                SettingContext.tenant().withContract(family(code))));
        assertThat(resolved.value()).isEqualByComparingTo(BigDecimal.valueOf(percent));
        assertThat(resolved.source().label()).isEqualTo(label.replace("{unique}", world.uniqueCode(code)));
    }

    @Then("the close tolerance for no contract is {int} percent")
    public void toleranceForNone(int percent) {
        assertThat(inTransaction(() -> settings.effective(OrderSettingDefinitions.CLOSE_TOLERANCE,
                SettingContext.tenant()))).isEqualByComparingTo(BigDecimal.valueOf(percent));
    }

    // ----------------------------------------------------------------- helpers

    private void activate(String code, String clientCode, boolean direct, String laneCode, String from, String to,
                          int perTonne, int days) {
        LaneTerm lane = new LaneTerm(laneCode, world.ref(from), null, world.ref(to), null, null, null,
                ContractLane.RateBasis.PER_TONNE, BigDecimal.valueOf(perTonne), days, List.of(), List.of(), List.of());
        UUID id = lifecycle.createDraft(world.orgUnitId(), world.uniqueCode(code), world.ref(clientCode), "INR",
                LocalDate.now(ZoneOffset.UTC).minusDays(30), null,
                new ClientContract.Rates(BigDecimal.valueOf(5), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                        BigDecimal.ZERO),
                List.<ClientContractService.ServiceRateTerm>of(), false,
                new ClientContract.Allowances(direct, null), List.of(lane));
        long version = inTransaction(() -> jdbc.sql("SELECT version FROM client_contract WHERE id = :id")
                .param("id", id).query(Long.class).single());
        lifecycle.requestVerification(id, version);
        world.putRef("contract-version:" + code, id);
    }

    private UUID family(String code) {
        UUID id = world.ref("contract-version:" + code);
        return inTransaction(() -> jdbc.sql("SELECT coalesce(family_id, id) FROM client_contract WHERE id = :id")
                .param("id", id).query(UUID.class).single());
    }

    private String order(String orderNo, String column) {
        return inTransaction(() -> jdbc.sql("SELECT " + column + " FROM sales_order WHERE id = :id")
                .param("id", world.ref("order:" + orderNo)).query(String.class).optional().orElse(null));
    }

    private <T> T inTransaction(Supplier<T> action) {
        return new TransactionTemplate(transactionManager).execute(status -> action.get());
    }
}
