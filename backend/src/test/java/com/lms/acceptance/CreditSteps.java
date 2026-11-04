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

import com.lms.clientbilling.command.ClientContractService;
import com.lms.clientbilling.command.domain.ClientContract;
import com.lms.identity.access.SubjectAttributes;
import com.lms.masterdata.command.CreditLimitChangeService;
import com.lms.masterdata.command.CreditOverrideService;
import com.lms.masterdata.command.FleetCommandService;
import com.lms.masterdata.command.domain.BusinessPartner;
import com.lms.masterdata.command.domain.CreditOverride;
import com.lms.masterdata.query.CreditPositionView;
import com.lms.masterdata.query.CreditQueryService;
import com.lms.order.api.MaterialClass;
import com.lms.order.command.OrderCommandService;
import com.lms.order.command.OrderLineSpec;
import com.lms.order.command.SalesOrderRepository;
import com.lms.shared.error.BusinessRuleViolationException;
import com.lms.shared.tenant.TenantContext;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Step definitions for {@code credit.feature}: maker-checker credit limits,
 * the credit gate on orders, overrides, and customer standing.
 *
 * <p>Named people ("asha", "ravi") sign in with every permission under their
 * own name, because maker and checker are told apart by who they are, not by
 * what they hold. Orders, lines and depots use {@link PlanningSteps}.
 */
public class CreditSteps {

    @Autowired
    private ScenarioWorld world;
    @Autowired
    private ClientContractService contracts;
    @Autowired
    private CreditLimitChangeService changes;
    @Autowired
    private CreditOverrideService overrides;
    @Autowired
    private CreditQueryService credit;
    @Autowired
    private FleetCommandService fleet;
    @Autowired
    private OrderCommandService orderCommands;
    @Autowired
    private SalesOrderRepository orders;
    @Autowired
    private JdbcClient jdbc;
    @Autowired
    private PlatformTransactionManager transactionManager;

    private RuntimeException failure;
    private UUID lastChange;

    @Given("a client contract for {string} charging {int} per kg")
    public void aClientContract(String customerCode, int ratePerKg) {
        contracts.publishContract(world.orgUnitId(), world.uniqueCode("CC-" + customerCode),
                world.ref(customerCode), "INR", LocalDate.now(ZoneOffset.UTC).minusMonths(1),
                new ClientContract.Rates(BigDecimal.valueOf(ratePerKg), BigDecimal.ZERO,
                        BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE), List.of(), false);
    }

    @When("{string} asks for a credit limit of {int} with a soft limit of {int} for {string}")
    public void asksForLimit(String who, int limit, int soft, String customerCode) {
        as(who);
        attempt(() -> lastChange = changes.request(world.ref(customerCode),
                BigDecimal.valueOf(limit), BigDecimal.valueOf(soft), "annual review"));
    }

    @When("{string} approves the credit limit change for {string}")
    public void approves(String who, String customerCode) {
        as(who);
        attempt(() -> changes.approve(lastChange, "agreed"));
    }

    @When("{string} rejects the credit limit change for {string}")
    public void rejects(String who, String customerCode) {
        as(who);
        attempt(() -> changes.reject(lastChange, "not now"));
    }

    @Given("{string} has an approved credit limit of {int} with a soft limit of {int}")
    public void approvedLimit(String customerCode, int limit, int soft) {
        asksForLimit("maker", limit, soft, customerCode);
        approves("checker", customerCode);
        assertThat(failure).as("setting the limit").isNull();
        as("acceptance-user");
    }

    @Given("customer {string} is suspended")
    public void suspended(String customerCode) {
        UUID id = world.ref(customerCode);
        fleet.transitionPartner(id, BusinessPartner.PartnerStatus.PENDING_VERIFICATION);
        fleet.transitionPartner(id, BusinessPartner.PartnerStatus.ACTIVE);
        fleet.transitionPartner(id, BusinessPartner.PartnerStatus.SUSPENDED);
    }

    @When("order {string} is put through validation")
    public void validate(String orderNo) {
        attempt(() -> orderCommands.validate(order(orderNo)));
    }

    @When("a line of {int} kg is offered to order {string}")
    public void offerLine(int weightKg, String orderNo) {
        attempt(() -> orderCommands.addLine(order(orderNo), 99, "MAT-X", "Extra",
                MaterialClass.GENERAL, null, BigDecimal.ONE, "EA", BigDecimal.valueOf(weightKg),
                null, null, null, null, world.ref("CONS-A"), world.ref("DEST-A")));
    }

    @When("order {string} is amended to one line of {int} kg")
    public void amendTo(String orderNo, int weightKg) {
        UUID id = order(orderNo);
        long version = inTransaction(() -> orders.findById(id).orElseThrow().version());
        attempt(() -> orderCommands.amend(id, version, null, List.of(new OrderLineSpec(1, "MAT-1",
                "General cargo", MaterialClass.GENERAL, null, BigDecimal.ONE, "EA",
                BigDecimal.valueOf(weightKg), null, null, null, null, world.ref("CONS-A"),
                world.ref("DEST-A")))));
    }

    @When("a credit override up to {int} is granted for order {string}")
    public void override(int upTo, String orderNo) {
        UUID id = order(orderNo);
        UUID customer = inTransaction(() -> orders.findById(id).orElseThrow().customerPartnerId());
        attempt(() -> overrides.grant(customer, CreditOverride.SubjectType.ORDER, id, null,
                BigDecimal.valueOf(upTo), "strategic shipment"));
        assertThat(failure).as("granting the override").isNull();
    }

    @Then("the credit action succeeds")
    public void succeeds() {
        assertThat(failure).as("the action should have gone through").isNull();
    }

    @Then("the credit action is refused with {string}")
    public void refused(String code) {
        assertThat(failure).isInstanceOfSatisfying(BusinessRuleViolationException.class,
                e -> assertThat(e.code()).isEqualTo(code));
    }

    @Then("{string} has no credit limit yet")
    public void noLimit(String customerCode) {
        assertThat(position(customerCode).creditLimit()).isNull();
    }

    @Then("the credit limit of {string} is {int} with a soft limit of {int}")
    public void limitIs(String customerCode, int limit, int soft) {
        CreditPositionView position = position(customerCode);
        assertThat(position.creditLimit()).isEqualByComparingTo(BigDecimal.valueOf(limit));
        assertThat(position.softLimit()).isEqualByComparingTo(BigDecimal.valueOf(soft));
    }

    @Then("the credit position of {string} is {string} with exposure {int}")
    public void positionIs(String customerCode, String state, int exposure) {
        CreditPositionView position = position(customerCode);
        assertThat(position.limitState().name()).isEqualTo(state);
        assertThat(position.exposure().total()).isEqualByComparingTo(BigDecimal.valueOf(exposure));
    }

    @Then("{string} has a {string} audit entry")
    public void auditEntry(String customerCode, String action) {
        long count = jdbc.sql("SELECT count(*) FROM audit_log WHERE resource_id = :id AND action = :a")
                .param("id", world.ref(customerCode)).param("a", action)
                .query(Long.class).single();
        assertThat(count).isPositive();
    }

    private CreditPositionView position(String customerCode) {
        as("acceptance-user");
        return inTransaction(() -> credit.position(world.ref(customerCode)));
    }

    private UUID order(String orderNo) {
        return world.ref("order:" + orderNo);
    }

    private static void as(String who) {
        AccessSteps.signInWith(who, AuthorityHooks.ALL_PERMISSIONS, SubjectAttributes.NONE);
    }

    private void attempt(Runnable action) {
        failure = null;
        try {
            action.run();
        } catch (BusinessRuleViolationException | IllegalArgumentException e) {
            failure = e;
        }
    }

    private <T> T inTransaction(java.util.function.Supplier<T> action) {
        TenantContext.requireTenantId();
        return new TransactionTemplate(transactionManager).execute(status -> action.get());
    }
}
