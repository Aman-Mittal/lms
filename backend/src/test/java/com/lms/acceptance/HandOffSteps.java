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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import com.lms.execution.command.TripCommandService;
import com.lms.finance.query.FinanceQueryService;
import com.lms.finance.query.FreightBillView;
import com.lms.milkrun.events.CircuitRunCompleted;
import com.lms.planning.command.ConsignmentRepository;
import com.lms.planning.command.PlanningCommandService;
import com.lms.planning.command.domain.Consignment;
import com.lms.planning.query.ConsignmentView;
import com.lms.planning.query.PlanningQueryService;
import com.lms.shared.query.Slice;
import com.lms.shared.tenant.TenantContext;
import com.lms.warehouse.command.HandlingUnitRepository;
import com.lms.warehouse.command.OutboundCommandService;
import com.lms.warehouse.command.domain.HandlingUnit;
import com.lms.warehouse.events.HandlingUnitDispatched;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Steps for the two hand-offs between modules: a completed milk-run circuit
 * becoming the carrier's payable, and a dispatched warehouse handling unit
 * becoming a planning consignment.
 *
 * <p>Redelivery is simulated the way the outbox does it after a restart: the
 * very event that was announced, published again inside a transaction so the
 * after-commit listeners are the ones that hear it.
 */
public class HandOffSteps {

    @Autowired
    private ScenarioWorld world;
    @Autowired
    private MilkRunEventLog milkRunEvents;
    @Autowired
    private WarehouseEventProbe warehouseEvents;
    @Autowired
    private ApplicationEventPublisher publisher;
    @Autowired
    private FinanceQueryService finance;
    @Autowired
    private TripCommandService tripCommands;
    @Autowired
    private OutboundCommandService outbound;
    @Autowired
    private PlanningCommandService planning;
    @Autowired
    private PlanningQueryService planningQueries;
    @Autowired
    private ConsignmentRepository consignments;
    @Autowired
    private HandlingUnitRepository handlingUnits;
    @Autowired
    private JdbcClient jdbc;
    @Autowired
    private PlatformTransactionManager transactionManager;

    // ------------------------------------------------------ milk-run payable

    @When("the completion of the circuit on route {string} is delivered again")
    public void completionRedelivered(String code) {
        List<CircuitRunCompleted> completions = milkRunEvents.completionsOf(circuit(code));
        assertThat(completions).as("the circuit was never completed").isNotEmpty();
        redeliver(completions.get(0));
    }

    /** Registers the bill as {@code bill:<alias>}, so the finance steps can read it. */
    @Then("the circuit on route {string} has exactly one carrier payable, known as {string}")
    public void exactlyOnePayable(String code, String alias) {
        List<UUID> bills = inTransaction(() -> jdbc.sql("""
                        SELECT id FROM freight_bill
                         WHERE tenant_id = :tenantId AND circuit_run_id = :runId
                        """)
                .param("tenantId", TenantContext.requireTenantId())
                .param("runId", circuit(code))
                .query(UUID.class)
                .list());
        assertThat(bills).as("payables for the circuit on %s", code).hasSize(1);
        world.putRef("bill:" + alias, bills.get(0));
    }

    @Then("the circuit on route {string} has no carrier payable")
    public void noPayable(String code) {
        long count = inTransaction(() -> jdbc.sql("""
                        SELECT count(*) FROM freight_bill
                         WHERE tenant_id = :tenantId AND circuit_run_id = :runId
                        """)
                .param("tenantId", TenantContext.requireTenantId())
                .param("runId", circuit(code))
                .query(Long.class)
                .single());
        assertThat(count).isZero();
    }

    /** Read from the same paged listing a settlement screen reads. */
    @Then("bill {string} is listed as a milk-run bill for route {string}")
    public void listedAsMilkRun(String alias, String code) {
        UUID billId = world.ref("bill:" + alias);
        FreightBillView listed = allBills().stream()
                .filter(bill -> bill.id().equals(billId))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Bill " + alias + " is not listed"));
        assertThat(listed.source()).isEqualTo("MILK_RUN");
        assertThat(listed.routeCode()).isEqualTo(code);
        assertThat(listed.circuitRunId()).isEqualTo(circuit(code));
        assertThat(listed.tariffCode()).isNull();
    }

    /**
     * Brings the carrying trip to its end through execution, which bills it
     * on its own account -- the second path to a payable for the same lorry.
     */
    @When("trip {string} reaches its destination and is completed")
    public void tripArrivesAndCompletes(String tripNo) {
        UUID tripId = world.ref("trip:" + tripNo);
        tripCommands.beginTransit(tripId);
        tripCommands.arrive(tripId, Instant.now().minusSeconds(60));
        tripCommands.complete(tripId, Instant.now());
    }

    /** Registers the bill as {@code bill:<alias>}. */
    @Then("trip {string} was billed on its own account, as {string}")
    public void tripBilled(String tripNo, String alias) {
        List<UUID> bills = billsOfTrip(tripNo);
        assertThat(bills).as("trip bills of %s", tripNo).hasSize(1);
        world.putRef("bill:" + alias, bills.get(0));
    }

    @Then("trip {string} was not billed on its own account")
    public void tripNotBilled(String tripNo) {
        assertThat(billsOfTrip(tripNo)).isEmpty();
    }

    @Then("trip {string} has exactly one live bill, {string}")
    public void oneLiveBill(String tripNo, String alias) {
        List<UUID> live = inTransaction(() -> jdbc.sql("""
                        SELECT id FROM freight_bill
                         WHERE tenant_id = :tenantId AND trip_id = :tripId
                           AND status <> 'CANCELLED'
                        """)
                .param("tenantId", TenantContext.requireTenantId())
                .param("tripId", world.ref("trip:" + tripNo))
                .query(UUID.class)
                .list());
        assertThat(live).containsExactly(world.ref("bill:" + alias));
    }

    // ---------------------------------------------------- warehouse hand-off

    @When("handling unit {string} is dispatched as {string} to {string} for {string} "
            + "weighing {int} kg")
    public void dispatchedToTransport(String unit, String reference, String destination,
                                      String consignee, int grossKg) {
        outbound.dispatch(world.ref("hu:" + unit), reference,
                new HandlingUnit.HandOff(world.ref(destination), world.ref(consignee),
                        BigDecimal.valueOf(grossKg), null));
    }

    @When("the hand-off of handling unit {string} is delivered again")
    public void handOffRedelivered(String unit) {
        UUID unitId = world.ref("hu:" + unit);
        List<HandlingUnitDispatched> handOffs = warehouseEvents.heard(HandlingUnitDispatched.class)
                .stream()
                .filter(event -> event.handlingUnitId().equals(unitId))
                .toList();
        assertThat(handOffs).as("handling unit %s was never dispatched", unit).isNotEmpty();
        redeliver(handOffs.get(0));
    }

    @Then("handling unit {string} became one consignment for {string} at {string} "
            + "carrying {int} kg")
    public void becameConsignment(String unit, String consignee, String destination, int kg) {
        List<UUID> made = consignmentsOf(unit);
        assertThat(made).as("consignments made from %s", unit).hasSize(1);
        Consignment consignment = inTransaction(() ->
                consignments.findById(made.get(0)).orElseThrow());
        assertThat(consignment.orderId()).isNull();
        assertThat(consignment.consigneePartnerId()).isEqualTo(world.ref(consignee));
        assertThat(consignment.destinationTerminalId()).isEqualTo(world.ref(destination));
        assertThat(consignment.totalDeadWeightKg()).isEqualByComparingTo(BigDecimal.valueOf(kg));
        assertThat(consignment.chargeableWeightKg()).isEqualByComparingTo(BigDecimal.valueOf(kg));
        assertThat(consignment.status()).isEqualTo(Consignment.ConsignmentStatus.PLANNED);
    }

    /**
     * A second unit shipped against an order that already shipped one -- the
     * back-order after a short shipment.
     *
     * <p>Built below the warehouse service on purpose: this branch packs an
     * order once, and packing its back-order is separate warehouse work. What
     * is under test is planning's side of the hand-off, which must key on the
     * unit and never on the order, so the unit is stored as the warehouse
     * would store it and its hand-off is published exactly as the warehouse
     * publishes one.
     */
    @When("a back-order unit {string} of outbound order {string} is handed off to {string} "
            + "for {string} weighing {int} kg")
    public void backOrderUnitHandedOff(String unit, String orderNo, String destination,
                                       String consignee, int grossKg) {
        UUID orderId = world.ref("outbound:" + orderNo);
        UUID tenantId = TenantContext.requireTenantId();
        UUID warehouseId = inTransaction(() -> jdbc.sql(
                        "SELECT warehouse_id FROM outbound_order WHERE id = :id")
                .param("id", orderId)
                .query(UUID.class)
                .single());
        Instant now = Instant.now();
        HandlingUnit.HandOff handOff = new HandlingUnit.HandOff(world.ref(destination),
                world.ref(consignee), BigDecimal.valueOf(grossKg), null);
        HandlingUnit shipped = inTransaction(() -> handlingUnits.save(
                HandlingUnit.packedFor(tenantId, warehouseId, orderId, now)
                        .dispatch("BACK-ORDER", handOff, now)));
        world.putRef("hu:" + unit, shipped.id());
        redeliver(new HandlingUnitDispatched(shipped.tenantId(), shipped.id(), shipped.huNo(),
                shipped.warehouseId(), orderId, null, null, "BACK-ORDER", List.of(), now,
                shipped.destinationTerminalId(), shipped.consigneePartnerId(),
                shipped.grossWeightKg(), shipped.volumeM3()));
    }

    @Then("outbound order {string} became {int} consignments")
    public void orderBecameConsignments(String orderNo, int expected) {
        long count = inTransaction(() -> jdbc.sql("""
                        SELECT count(*) FROM consignment c
                          JOIN handling_unit hu ON hu.id = c.handling_unit_id
                         WHERE c.tenant_id = :tenantId AND hu.outbound_order_id = :orderId
                        """)
                .param("tenantId", TenantContext.requireTenantId())
                .param("orderId", world.ref("outbound:" + orderNo))
                .query(Long.class)
                .single());
        assertThat(count).isEqualTo(expected);
    }

    @Then("handling unit {string} became no consignment")
    public void becameNoConsignment(String unit) {
        assertThat(consignmentsOf(unit)).isEmpty();
    }

    @Then("the consignment of handling unit {string} is waiting to be loaded")
    public void waitingToBeLoaded(String unit) {
        UUID unitId = world.ref("hu:" + unit);
        List<ConsignmentView> waiting = new ArrayList<>();
        String cursor = null;
        do {
            String after = cursor;
            Slice<ConsignmentView> page = inTransaction(() ->
                    planningQueries.listUnassigned(after, Slice.MAX_LIMIT));
            waiting.addAll(page.items());
            cursor = page.nextCursor();
        } while (cursor != null);
        assertThat(waiting).filteredOn(view -> unitId.equals(view.handlingUnitId()))
                .singleElement()
                .satisfies(view -> {
                    assertThat(view.orderId()).isNull();
                    assertThat(view.handlingUnitNo()).startsWith("HU-");
                    assertThat(view.trackingRef()).startsWith("LR-");
                });
    }

    /** A load starting at the warehouse dock the unit left from. */
    @When("a load {string} is opened at warehouse {string} on lorry {string}")
    public void openLoadAtWarehouse(String loadNo, String warehouse, String registration) {
        world.putRef("load:" + loadNo, planning.openLoad(world.orgUnitId(),
                world.uniqueCode(loadNo), world.ref("warehouse:" + warehouse),
                world.ref("vehicle:" + registration)));
    }

    @When("the consignment of handling unit {string} is put on load {string}")
    public void putOnLoad(String unit, String loadNo) {
        List<UUID> made = consignmentsOf(unit);
        assertThat(made).hasSize(1);
        planning.assignConsignment(world.ref("load:" + loadNo), made.get(0));
    }

    // ----------------------------------------------------------------- helpers

    private void redeliver(Object event) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                publisher.publishEvent(event));
    }

    private UUID circuit(String code) {
        return world.ref("circuit:" + code);
    }

    private List<UUID> consignmentsOf(String unit) {
        return inTransaction(() -> jdbc.sql("""
                        SELECT id FROM consignment
                         WHERE tenant_id = :tenantId AND handling_unit_id = :unitId
                        """)
                .param("tenantId", TenantContext.requireTenantId())
                .param("unitId", world.ref("hu:" + unit))
                .query(UUID.class)
                .list());
    }

    /** The trip's own live bills, not counting a circuit's. */
    private List<UUID> billsOfTrip(String tripNo) {
        return inTransaction(() -> jdbc.sql("""
                        SELECT id FROM freight_bill
                         WHERE tenant_id = :tenantId AND trip_id = :tripId
                           AND circuit_run_id IS NULL
                           AND status <> 'CANCELLED'
                        """)
                .param("tenantId", TenantContext.requireTenantId())
                .param("tripId", world.ref("trip:" + tripNo))
                .query(UUID.class)
                .list());
    }

    private List<FreightBillView> allBills() {
        List<FreightBillView> all = new ArrayList<>();
        String cursor = null;
        do {
            String after = cursor;
            Slice<FreightBillView> page = inTransaction(() ->
                    finance.list(null, after, Slice.MAX_LIMIT));
            all.addAll(page.items());
            cursor = page.nextCursor();
        } while (cursor != null);
        return all;
    }

    private <T> T inTransaction(Supplier<T> action) {
        TenantContext.requireTenantId();
        return new TransactionTemplate(transactionManager).execute(status -> action.get());
    }
}
