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
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import com.lms.order.command.OrderCloseService;
import com.lms.order.command.OrderCloseSettings;
import com.lms.order.command.OrderLineRepository;
import com.lms.order.command.SalesOrderRepository;
import com.lms.order.command.domain.CloseKind;
import com.lms.order.command.domain.OrderLine;
import com.lms.order.command.domain.SalesOrder;
import com.lms.planning.command.ConsignmentRepository;
import com.lms.planning.command.ConsignmentSplitService;
import com.lms.planning.command.PlanningCommandService;
import com.lms.planning.command.ProofOfDeliveryService;
import com.lms.planning.command.domain.Consignment;
import com.lms.shared.error.BusinessRuleViolationException;
import com.lms.shared.tenant.TenantContext;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Step definitions for {@code order_close.feature}: closing an order or a line,
 * the tenant's close policy, and a split shipment delivered a part at a time.
 * Orders, lines, planning, loads and trips use the steps of
 * {@link PlanningSteps}, {@link FreightModeSteps} and {@link LifecycleSteps}.
 */
public class OrderCloseSteps {

    @Autowired
    private ScenarioWorld world;
    @Autowired
    private OrderCloseService closes;
    @Autowired
    private OrderCloseSettings settings;
    @Autowired
    private SalesOrderRepository orders;
    @Autowired
    private OrderLineRepository lines;
    @Autowired
    private ConsignmentRepository consignments;
    @Autowired
    private ConsignmentSplitService splits;
    @Autowired
    private PlanningCommandService planning;
    @Autowired
    private ProofOfDeliveryService proofs;
    @Autowired
    private PlatformTransactionManager transactionManager;

    private RuntimeException failure;

    // ----------------------------------------------------------------- policy

    @Given("the tenant's close tolerance is {int} percent")
    public void toleranceIs(int percent) {
        settings.update(BigDecimal.valueOf(percent), true);
    }

    @Given("the tenant's orders are not fulfilled automatically")
    public void autoFulfilOff() {
        settings.update(BigDecimal.ZERO, false);
    }

    @When("the tenant's close tolerance is set to {int} percent")
    public void toleranceSetTo(int percent) {
        attempt(() -> settings.update(BigDecimal.valueOf(percent), true));
    }

    // ---------------------------------------------------------------- closing

    @When("order {string} is closed {word}")
    public void closeOrder(String orderNo, String kind) {
        attempt(() -> closes.close(order(orderNo), CloseKind.valueOf(kind), null));
    }

    @When("order {string} is closed {word} because {string}")
    public void closeOrderBecause(String orderNo, String kind, String reason) {
        attempt(() -> closes.close(order(orderNo), CloseKind.valueOf(kind), reason));
    }

    @When("line {int} of order {string} is closed {word} because {string}")
    public void closeLineBecause(int lineNo, String orderNo, String kind, String reason) {
        UUID lineId = line(orderNo, lineNo).id();
        attempt(() -> closes.closeLine(order(orderNo), lineId, CloseKind.valueOf(kind), reason));
    }

    @Then("the close is refused with {string} mentioning {string}")
    public void closeRefused(String code, String fragment) {
        assertThat(failure).as("the close should have been refused")
                .isInstanceOf(BusinessRuleViolationException.class);
        assertThat(((BusinessRuleViolationException) failure).code()).isEqualTo(code);
        assertThat(failure.getMessage()).contains(fragment);
    }

    @Then("the close is denied for want of a right")
    public void closeDenied() {
        assertThat(failure).isInstanceOf(AccessDeniedException.class);
    }

    @Then("order {string} reads closed {word}")
    public void orderReadsClosed(String orderNo, String kind) {
        SalesOrder order = inTransaction(() -> orders.findById(order(orderNo)).orElseThrow());
        assertThat(order.status()).isEqualTo(SalesOrder.OrderStatus.CLOSED);
        assertThat(order.closeKind()).isEqualTo(CloseKind.valueOf(kind));
        assertThat(order.closedAt()).isNotNull();
    }

    @Then("order {string} reads closed {word} because {string}")
    public void orderReadsClosedBecause(String orderNo, String kind, String reason) {
        orderReadsClosed(orderNo, kind);
        SalesOrder order = inTransaction(() -> orders.findById(order(orderNo)).orElseThrow());
        assertThat(order.closeReason()).isEqualTo(reason);
    }

    @Then("line {int} of order {string} reads closed {word}")
    public void lineReadsClosed(int lineNo, String orderNo, String kind) {
        OrderLine line = line(orderNo, lineNo);
        assertThat(line.isClosed()).isTrue();
        assertThat(line.closeKind()).isEqualTo(CloseKind.valueOf(kind));
    }

    // ------------------------------------------------------- split deliveries

    @Given("the shipment of order {string} is split into parts of {int} kg and {int} kg")
    public void split(String orderNo, int firstKg, int secondKg) {
        Consignment whole = shipments(orderNo).getFirst();
        splits.split(whole.id(), List.of(
                new Consignment.PartSpec(BigDecimal.valueOf(firstKg), BigDecimal.ZERO),
                new Consignment.PartSpec(BigDecimal.valueOf(secondKg), BigDecimal.ZERO)));
    }

    @Given("both parts of the shipment of order {string} are put on load {string}")
    public void partsOnLoad(String orderNo, String loadNo) {
        planning.assignConsignments(world.ref("load:" + loadNo),
                parts(orderNo).stream().map(Consignment::id).toList());
    }

    @When("the {int} kg part of order {string} is delivered on proof")
    public void partDelivered(int kg, String orderNo) {
        assertThat(proofs.deliverOnProof(part(orderNo, kg).id(), Instant.now())).isTrue();
    }

    @Then("the {int} kg part of order {string} is still {string}")
    public void partStill(int kg, String orderNo, String status) {
        assertThat(part(orderNo, kg).status().name()).isEqualTo(status);
    }

    // ---------------------------------------------------------------- helpers

    private void attempt(Runnable action) {
        failure = null;
        try {
            action.run();
        } catch (BusinessRuleViolationException | AccessDeniedException e) {
            failure = e;
        }
    }

    private UUID order(String orderNo) {
        return world.ref("order:" + orderNo);
    }

    private OrderLine line(String orderNo, int lineNo) {
        return inTransaction(() -> lines.findByOrder(TenantContext.requireTenantId(), order(orderNo))).stream()
                .filter(l -> l.lineNo() == lineNo).findFirst()
                .orElseThrow(() -> new AssertionError("No line " + lineNo + " on order " + orderNo));
    }

    private List<Consignment> shipments(String orderNo) {
        return inTransaction(() -> consignments.findByOrder(TenantContext.requireTenantId(), order(orderNo)));
    }

    private List<Consignment> parts(String orderNo) {
        return shipments(orderNo).stream().filter(c -> c.parentConsignmentId() != null)
                .sorted(Comparator.comparing(Consignment::trackingRef)).toList();
    }

    private Consignment part(String orderNo, int kg) {
        return parts(orderNo).stream()
                .filter(c -> c.totalDeadWeightKg().compareTo(BigDecimal.valueOf(kg)) == 0)
                .findFirst().orElseThrow(() -> new AssertionError("No " + kg + " kg part on order " + orderNo));
    }

    private <T> T inTransaction(Supplier<T> action) {
        return new TransactionTemplate(transactionManager).execute(status -> action.get());
    }
}
