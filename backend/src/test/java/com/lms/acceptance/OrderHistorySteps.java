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

import java.time.Year;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import com.lms.order.command.OrderCommandService;
import com.lms.order.command.SalesOrderRepository;
import com.lms.order.command.domain.SalesOrder;
import com.lms.order.events.OrderCancelled;
import com.lms.order.events.OrderRaised;
import com.lms.shared.audit.AuditHistoryQueryService;
import com.lms.shared.audit.HistoryEntryView;
import com.lms.shared.error.BusinessRuleViolationException;
import com.lms.shared.query.Slice;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Step definitions for {@code order_history.feature}: the numbers the platform
 * assigns, the events an order's lifecycle publishes, and reading a record's
 * history back. Orders, lines, validation, planning, trips and cancellation
 * use the steps {@link PlanningSteps}, {@link ExecutionSteps} and
 * {@link LifecycleSteps} already define.
 */
public class OrderHistorySteps {

    @Autowired
    private ScenarioWorld world;
    @Autowired
    private OrderCommandService orderCommands;
    @Autowired
    private SalesOrderRepository orders;
    @Autowired
    private AuditHistoryQueryService history;
    @Autowired
    private OrderEventProbe probe;
    @Autowired
    private PlatformTransactionManager transactionManager;

    private RuntimeException failure;

    // ---------------------------------------------------------------- numbers

    @Given("an order {string} raised without a number for {string} from {string}")
    public void raisedWithoutNumber(String ref, String customerCode, String originCode) {
        raise(ref, customerCode, originCode, null);
    }

    @Given("an order {string} raised with a blank number for {string} from {string}")
    public void raisedWithBlankNumber(String ref, String customerCode, String originCode) {
        raise(ref, customerCode, originCode, "   ");
    }

    @Given("an order {string} raised by hand as this year's number {int} for {string} from {string}")
    public void raisedByHandAs(String ref, int counter, String customerCode, String originCode) {
        raise(ref, customerCode, originCode, assigned(counter));
    }

    @When("another order is raised by hand under the number of order {string}")
    public void raisedUnderTakenNumber(String ref) {
        failure = null;
        try {
            orderCommands.raiseOrder(world.orgUnitId(), world.ref("CUST"), numberOf(ref),
                    world.ref("ORIGIN"), null, null, SalesOrder.OrderSource.MANUAL);
        } catch (BusinessRuleViolationException e) {
            failure = e;
        }
    }

    @Then("order {string} is numbered as this year's number {int}")
    public void numberedAs(String ref, int counter) {
        assertThat(numberOf(ref)).isEqualTo(assigned(counter));
    }

    @Then("the second raise is refused with {string}")
    public void secondRaiseRefused(String code) {
        assertThat(failure).as("the raise should have been refused").isNotNull();
        assertThat(((BusinessRuleViolationException) failure).code()).isEqualTo(code);
    }

    // ----------------------------------------------------------------- events

    @Then("order {string} announced {string}")
    public void announced(String orderNo, String expected) {
        List<String> heard = probe.about(world.ref("order:" + orderNo)).stream()
                .map(OrderEventProbe.Heard::type).toList();
        assertThat(heard).containsExactlyElementsOf(split(expected));
    }

    @Then("the raising of order {string} announced the number it was given")
    public void raisingAnnouncedNumber(String ref) {
        OrderRaised raised = (OrderRaised) probe.about(world.ref("order:" + ref)).getFirst().event();
        assertThat(raised.orderNo()).isEqualTo(numberOf(ref));
        assertThat(raised.numberAssigned()).isTrue();
        assertThat(raised.tenantId()).isEqualTo(world.tenantId("acme"));
    }

    @Then("the cancellation of order {string} announced the reason {string} and the status it left")
    public void cancellationAnnounced(String orderNo, String reason) {
        OrderCancelled cancelled = probe.about(world.ref("order:" + orderNo)).stream()
                .filter(h -> h.event() instanceof OrderCancelled)
                .map(h -> (OrderCancelled) h.event()).findFirst().orElseThrow();
        assertThat(cancelled.reason()).isEqualTo(reason);
        assertThat(cancelled.previousStatus()).isEqualTo("VALIDATED");
    }

    // ---------------------------------------------------------------- history

    @Then("the history of order {string} reads newest first {string}")
    public void historyNewestFirst(String orderNo, String expected) {
        assertThat(walk("SalesOrder", world.ref("order:" + orderNo), 200))
                .containsExactlyElementsOf(split(expected));
    }

    @Then("the history of load {string} reads newest first {string}")
    public void loadHistoryNewestFirst(String loadNo, String expected) {
        assertThat(walk("Load", world.ref("load:" + loadNo), 200))
                .containsExactlyElementsOf(split(expected));
    }

    @Then("walking the history of order {string} one entry at a time reads the same")
    public void walkOneAtATime(String orderNo) {
        UUID id = world.ref("order:" + orderNo);
        assertThat(walk("SalesOrder", id, 1)).isEqualTo(walk("SalesOrder", id, 200));
    }

    @Then("the newest history entry of order {string} moved status from {string} to {string}")
    public void newestMoved(String orderNo, String from, String to) {
        HistoryEntryView newest = inTransaction(() -> history.historyOf("SalesOrder",
                world.ref("order:" + orderNo), null, 1)).items().getFirst();
        assertThat(newest.before()).containsEntry("status", from);
        assertThat(newest.after()).containsEntry("status", to);
    }

    @Then("tenant {string} sees no history for order {string}")
    public void otherTenantSeesNothing(String tenantCode, String orderNo) {
        UUID id = world.ref("order:" + orderNo);
        Slice<HistoryEntryView> seen = com.lms.shared.tenant.TenantContext.callWith(
                world.tenantId(tenantCode), "/" + tenantCode + "/",
                () -> inTransaction(() -> history.historyOf("SalesOrder", id, null, 200)));
        assertThat(seen.items()).isEmpty();
    }

    // ---------------------------------------------------------------- helpers

    private List<String> walk(String type, UUID id, int pageSize) {
        List<String> actions = new ArrayList<>();
        String cursor = null;
        for (int guard = 0; guard < 1000; guard++) {
            String from = cursor;
            Slice<HistoryEntryView> page = inTransaction(() -> history.historyOf(type, id, from, pageSize));
            page.items().forEach(entry -> actions.add(entry.action()));
            if (!page.hasMore()) {
                return actions;
            }
            cursor = page.nextCursor();
        }
        throw new AssertionError("the history walk never ended");
    }

    private void raise(String ref, String customerCode, String originCode, String orderNo) {
        world.putRef("order:" + ref, orderCommands.raiseOrder(world.orgUnitId(), world.ref(customerCode),
                orderNo, world.ref(originCode), null, null, SalesOrder.OrderSource.MANUAL));
    }

    private String numberOf(String ref) {
        return inTransaction(() -> orders.findById(world.ref("order:" + ref)).orElseThrow()).orderNo();
    }

    private static String assigned(int counter) {
        return "ORD-%d-%06d".formatted(Year.now(ZoneOffset.UTC).getValue(), counter);
    }

    private static List<String> split(String csv) {
        return Arrays.stream(csv.split(",")).map(String::strip).toList();
    }

    private <T> T inTransaction(Supplier<T> action) {
        return new TransactionTemplate(transactionManager).execute(status -> action.get());
    }
}
