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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

import com.lms.order.api.MaterialClass;
import com.lms.order.command.OrderCommandService;
import com.lms.order.command.OrderHeaderChange;
import com.lms.order.command.OrderLineRepository;
import com.lms.order.command.OrderLineSpec;
import com.lms.order.command.SalesOrderRepository;
import com.lms.order.command.domain.OrderLine;
import com.lms.order.command.domain.SalesOrder;
import com.lms.shared.error.BusinessRuleViolationException;
import com.lms.shared.tenant.TenantContext;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Step definitions for {@code order_editing.feature}: editing a draft order,
 * amending a validated one, and the version guard on both.
 *
 * <p>"The editor" is whoever read the order last; their edits carry the version
 * they read, exactly as a client's {@code If-Match} does. "A second editor"
 * always reads the live version first, which is what lets a scenario stage a
 * lost update. Orders, lines, validation and the audit trail use the steps
 * {@link PlanningSteps}, {@link LifecycleSteps} and {@link AuditTrailSteps}
 * already define.
 */
public class OrderEditingSteps {

    @Autowired
    private ScenarioWorld world;
    @Autowired
    private OrderCommandService orderCommands;
    @Autowired
    private SalesOrderRepository orders;
    @Autowired
    private OrderLineRepository orderLines;
    @Autowired
    private JdbcClient jdbc;
    @Autowired
    private PlatformTransactionManager transactionManager;

    /** The version each order had when the editor last read it. */
    private final Map<String, Long> read = new HashMap<>();
    private RuntimeException failure;

    // ------------------------------------------------------------------ given

    @Given("the editor reads order {string}")
    public void editorReads(String orderNo) {
        read.put(orderNo, current(orderNo).version());
    }

    @Given("order {string} has been delivered")
    public void orderDelivered(String orderNo) {
        inTransaction(() -> {
            SalesOrder order = current(orderNo);
            for (SalesOrder.OrderStatus next : List.of(SalesOrder.OrderStatus.VALIDATED,
                    SalesOrder.OrderStatus.FULLY_PLANNED, SalesOrder.OrderStatus.FULFILLED)) {
                order = orders.save(order.transitionTo(next));
            }
            return order;
        });
    }

    // ------------------------------------------------------------------- when

    @When("the editor changes the customer of order {string} to {string}")
    public void editorChangesCustomer(String orderNo, String customerCode) {
        attempt(() -> {
            orderCommands.reviseDraft(world.ref("order:" + orderNo), read.get(orderNo),
                    headerWithCustomer(orderNo, customerCode));
            return null;
        });
    }

    @When("a second editor changes the customer of order {string} to {string}")
    public void secondEditorChangesCustomer(String orderNo, String customerCode) {
        attempt(() -> {
            orderCommands.reviseDraft(world.ref("order:" + orderNo), current(orderNo).version(),
                    headerWithCustomer(orderNo, customerCode));
            return null;
        });
    }

    @When("the editor replaces the lines of order {string} with line {int} of {int} kg of {word} "
            + "for {string} at {string}")
    public void editorReplacesLines(String orderNo, int lineNo, int weightKg, String materialClass,
                                    String consigneeCode, String destinationCode) {
        attempt(() -> {
            orderCommands.replaceLines(world.ref("order:" + orderNo), read.get(orderNo),
                    List.of(spec(lineNo, weightKg, materialClass, null, consigneeCode, destinationCode)));
            return null;
        });
    }

    @When("the editor removes line {int} of order {string}")
    public void editorRemovesLine(int lineNo, String orderNo) {
        attempt(() -> {
            UUID lineId = inTransaction(() -> orderLines
                    .findByOrder(TenantContext.requireTenantId(), world.ref("order:" + orderNo)).stream()
                    .filter(l -> l.lineNo() == lineNo).findFirst()
                    .orElseThrow(() -> new IllegalStateException("No line " + lineNo)).id());
            orderCommands.removeLine(world.ref("order:" + orderNo), lineId, read.get(orderNo));
            return null;
        });
    }

    @When("the editor amends order {string} to carry only line {int} of {int} kg of {word} "
            + "for {string} at {string}")
    public void editorAmendsLines(String orderNo, int lineNo, int weightKg, String materialClass,
                                  String consigneeCode, String destinationCode) {
        attempt(() -> {
            orderCommands.amend(world.ref("order:" + orderNo), read.get(orderNo), null,
                    List.of(spec(lineNo, weightKg, materialClass, null, consigneeCode, destinationCode)));
            return null;
        });
    }

    @When("the editor amends the customer of order {string} to {string}")
    public void editorAmendsCustomer(String orderNo, String customerCode) {
        attempt(() -> {
            orderCommands.amend(world.ref("order:" + orderNo), read.get(orderNo),
                    headerWithCustomer(orderNo, customerCode), null);
            return null;
        });
    }

    @When("a second editor amends the customer of order {string} to {string}")
    public void secondEditorAmendsCustomer(String orderNo, String customerCode) {
        attempt(() -> {
            orderCommands.amend(world.ref("order:" + orderNo), current(orderNo).version(),
                    headerWithCustomer(orderNo, customerCode), null);
            return null;
        });
    }

    // ------------------------------------------------------------------- then

    @Then("the edit succeeds")
    public void editSucceeds() {
        assertThat(failure)
                .as("the edit should have succeeded but failed with: %s",
                        failure == null ? "" : failure.getMessage())
                .isNull();
    }

    @Then("the edit is refused with {string}")
    public void editRefused(String code) {
        assertThat(failure).as("the edit should have been refused").isNotNull();
        String actual = switch (failure) {
            case BusinessRuleViolationException rule -> rule.code();
            case OptimisticLockingFailureException e -> "concurrent-modification";
            case AccessDeniedException e -> "access-denied";
            default -> failure.getClass().getSimpleName();
        };
        assertThat(actual).isEqualTo(code);
    }

    @Then("the customer of order {string} is {string}")
    public void customerIs(String orderNo, String customerCode) {
        assertThat(current(orderNo).customerPartnerId()).isEqualTo(world.ref(customerCode));
    }

    @Then("order {string} carries {int} lines")
    public void carriesLines(String orderNo, int expected) {
        assertThat(linesOf(orderNo)).hasSize(expected);
    }

    @Then("order {string} carries line {int} of {int} kg")
    public void carriesLine(String orderNo, int lineNo, int weightKg) {
        assertThat(linesOf(orderNo))
                .filteredOn(l -> l.lineNo() == lineNo)
                .singleElement()
                .satisfies(l -> assertThat(l.deadWeightKg()).isEqualByComparingTo(BigDecimal.valueOf(weightKg)));
    }

    @Then("the version of order {string} has moved past the one the editor read")
    public void versionMoved(String orderNo) {
        assertThat(current(orderNo).version()).isGreaterThan(read.get(orderNo));
    }

    @Then("the version of order {string} is still the one the editor read")
    public void versionKept(String orderNo) {
        assertThat(current(orderNo).version()).isEqualTo(read.get(orderNo));
    }

    @Then("the audit entry {string} of order {string} records {int} lines before and {int} after")
    public void auditRecordsLines(String action, String orderNo, int before, int after) {
        Map<String, Object> row = inTransaction(() -> jdbc.sql("""
                        SELECT before_state::text AS before_state, after_state::text AS after_state
                          FROM audit_log
                         WHERE tenant_id = :tenant AND resource_id = :id AND action = :action
                        """)
                .param("tenant", TenantContext.requireTenantId())
                .param("id", world.ref("order:" + orderNo))
                .param("action", action)
                .query().singleRow());
        // jsonb renders with a space after each colon.
        assertThat(String.valueOf(row.get("before_state")).replace("\": ", "\":"))
                .contains("\"lineCount\":\"" + before + "\"");
        assertThat(String.valueOf(row.get("after_state")).replace("\": ", "\":"))
                .contains("\"lineCount\":\"" + after + "\"");
    }

    // ---------------------------------------------------------------- helpers

    private OrderHeaderChange headerWithCustomer(String orderNo, String customerCode) {
        SalesOrder order = current(orderNo);
        return new OrderHeaderChange(world.ref(customerCode), order.originTerminalId(),
                order.requestedPickupAt(), order.requestedDeliveryAt(),
                order.originHubTerminalId(), order.destinationHubTerminalId());
    }

    private OrderLineSpec spec(int lineNo, int weightKg, String materialClass, String unCode,
                               String consigneeCode, String destinationCode) {
        return new OrderLineSpec(lineNo, "MAT-" + lineNo, materialClass + " goods",
                MaterialClass.valueOf(materialClass), unCode, BigDecimal.ONE, "EA",
                BigDecimal.valueOf(weightKg), null, null, null, null,
                world.ref(consigneeCode), world.ref(destinationCode));
    }

    private SalesOrder current(String orderNo) {
        return inTransaction(() -> orders.findById(world.ref("order:" + orderNo)).orElseThrow());
    }

    private List<OrderLine> linesOf(String orderNo) {
        return inTransaction(() -> orderLines.findByOrder(TenantContext.requireTenantId(),
                world.ref("order:" + orderNo)));
    }

    private <T> T attempt(Supplier<T> action) {
        failure = null;
        try {
            return action.get();
        } catch (BusinessRuleViolationException | IllegalArgumentException
                 | OptimisticLockingFailureException | AccessDeniedException e) {
            failure = e;
            return null;
        }
    }

    private <T> T inTransaction(Supplier<T> action) {
        return new TransactionTemplate(transactionManager).execute(status -> action.get());
    }
}
