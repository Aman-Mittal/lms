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
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

import com.lms.order.api.MaterialClass;
import com.lms.order.command.OrderCommandService;
import com.lms.order.command.domain.SalesOrder;
import com.lms.planning.api.LoadLifecyclePort;
import com.lms.planning.command.ConsignmentRepository;
import com.lms.planning.command.PlanningCommandService;
import com.lms.shared.audit.AuditTrail;
import com.lms.shared.config.Json;
import com.lms.shared.tenant.TenantContext;
import com.lms.sourcing.command.RoutingGuideEntryRepository;
import com.lms.sourcing.command.RoutingGuideRepository;
import com.lms.sourcing.command.domain.RoutingGuide;
import com.lms.sourcing.command.domain.RoutingGuideEntry;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Step definitions for {@code audit_trail.feature}.
 *
 * <p>Reads {@code audit_log} directly, as a regulator's query would: by the
 * resource a row concerns, in the order it happened. The rows are written by
 * the services under test, never by a step.
 */
public class AuditTrailSteps {

    private static final String LORRY = "MH-01-AU-0001";

    @Autowired
    private ScenarioWorld world;
    @Autowired
    private OrderCommandService orderCommands;
    @Autowired
    private PlanningCommandService planning;
    @Autowired
    private LoadLifecyclePort loads;
    @Autowired
    private ConsignmentRepository consignments;
    @Autowired
    private RoutingGuideRepository guides;
    @Autowired
    private RoutingGuideEntryRepository guideEntries;
    @Autowired
    private AuditTrail auditTrail;
    @Autowired
    private JdbcClient jdbc;

    private UUID probeId;
    private Throwable probeFailure;
    @Autowired
    private PlatformTransactionManager transactionManager;

    // ------------------------------------------------------------------ given

    @Given("a planned and awarded load {string} carrying order {string} of {int} kg")
    public void plannedAndAwarded(String loadNo, String orderNo, int weightKg) {
        plannedLoad(loadNo, orderNo, weightKg);
        loads.recordAwarded(world.ref("load:" + loadNo), world.ref("HAULIER"));
    }

    /**
     * A planned load with a guide ranking VENDOR-1 then VENDOR-2 on its lane,
     * ready to be offered with the allocation steps {@link SourcingSteps}
     * defines.
     */
    @Given("a planned and ranked load {string} carrying order {string} of {int} kg")
    public void plannedAndRanked(String loadNo, String orderNo, int weightKg) {
        plannedLoad(loadNo, orderNo, weightKg);
        UUID guideId = inTransaction(() -> guides.save(RoutingGuide.define(UUID.randomUUID(),
                TenantContext.requireTenantId(), world.orgUnitId(), world.ref("ORIGIN"),
                world.ref("DEST-A"), "RIGID", RoutingGuide.Strategy.CONTRACTUAL)).id());
        int rank = 1;
        for (String vendor : List.of("VENDOR-1", "VENDOR-2")) {
            int rankNo = rank++;
            inTransaction(() -> guideEntries.save(RoutingGuideEntry.at(UUID.randomUUID(),
                    TenantContext.requireTenantId(), guideId, rankNo, world.ref(vendor), 60,
                    new BigDecimal("10000"))));
        }
    }

    // ------------------------------------------------------------------- then

    @Then("the audit trail of order {string} reads {string}")
    public void orderTrail(String orderNo, String expected) {
        assertTrail(List.of(world.ref("order:" + orderNo)), expected);
    }

    @Then("the audit trail of load {string} reads {string}")
    public void loadTrail(String loadNo, String expected) {
        assertTrail(List.of(world.ref("load:" + loadNo)), expected);
    }

    @Then("the audit trail of the shipment of order {string} for {string} reads {string}")
    public void shipmentTrail(String orderNo, String consigneeCode, String expected) {
        assertTrail(List.of(shipmentId(orderNo, consigneeCode)), expected);
    }

    @Then("the audit trail of the allocation of load {string} reads {string}")
    public void allocationTrail(String loadNo, String expected) {
        assertTrail(allocationIds(loadNo), expected);
    }

    @Then("the audit entry {string} of load {string} moved status from {string} to {string}")
    public void loadMoved(String action, String loadNo, String from, String to) {
        assertMoved(action, world.ref("load:" + loadNo), from, to);
    }

    @Then("the audit entry {string} of order {string} moved status from {string} to {string}")
    public void orderMoved(String action, String orderNo, String from, String to) {
        assertMoved(action, world.ref("order:" + orderNo), from, to);
    }

    @Then("the audit entry {string} of the allocation of load {string} moved status from {string} to {string}")
    public void allocationMoved(String action, String loadNo, String from, String to) {
        assertMoved(action, allocationIds(loadNo).get(0), from, to);
    }

    @Then("the audit entry {string} of load {string} has no before state")
    public void noBefore(String action, String loadNo) {
        Map<String, Object> row = single(action, world.ref("load:" + loadNo));
        assertThat(row.get("before_state")).isNull();
        assertThat(String.valueOf(row.get("after_state"))).contains("status");
    }

    @Then("the audit entry {string} of order {string} carries the reason {string}")
    public void carriesReason(String action, String orderNo, String reason) {
        Map<String, Object> row = single(action, world.ref("order:" + orderNo));
        assertThat(String.valueOf(row.get("after_state"))).contains(reason);
    }


    // ------------------------------------------------------- atomic entries

    @When("an atomic audit entry is written and its transaction is rolled back")
    public void atomicEntryRolledBack() {
        probeId = UUID.randomUUID();
        TenantContext.requireTenantId();
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            auditTrail.recordAtomically("PROBE_ATOMIC", "PROBE", probeId, null,
                    Json.object("k", "v"));
            status.setRollbackOnly();
        });
    }

    @When("an atomic audit entry is written outside any transaction")
    public void atomicEntryOutsideTransaction() {
        probeFailure = null;
        try {
            auditTrail.recordAtomically("PROBE_ATOMIC", "PROBE", UUID.randomUUID(), null,
                    Json.object("k", "v"));
        } catch (IllegalTransactionStateException e) {
            probeFailure = e;
        }
    }

    @Then("the audit log holds no entry for that change")
    public void noProbeEntry() {
        assertThat(assertTrailActions(List.of(probeId))).isEmpty();
    }

    @Then("the audit write is refused for want of a transaction")
    public void probeRefused() {
        assertThat(probeFailure).isInstanceOf(IllegalTransactionStateException.class);
    }

    private List<String> assertTrailActions(List<UUID> resources) {
        return inTransaction(() -> jdbc.sql(
                        "SELECT action FROM audit_log WHERE tenant_id = :tenant"
                                + " AND resource_id IN (:ids)")
                .param("tenant", TenantContext.requireTenantId())
                .param("ids", resources)
                .query(String.class).list());
    }

    // ---------------------------------------------------------------- helpers

    private void plannedLoad(String loadNo, String orderNo, int weightKg) {
        UUID orderId = orderCommands.raiseOrder(world.orgUnitId(), world.ref("CUST"),
                world.uniqueCode(orderNo), world.ref("ORIGIN"), null, null,
                SalesOrder.OrderSource.MANUAL);
        world.putRef("order:" + orderNo, orderId);
        orderCommands.addLine(orderId, 1, "MAT-1", "General goods", MaterialClass.GENERAL, null,
                BigDecimal.ONE, "EA", BigDecimal.valueOf(weightKg), null, null, null, null,
                world.ref("CONS-A"), world.ref("DEST-A"));
        orderCommands.validate(orderId);
        UUID loadId = planning.openLoad(world.orgUnitId(), world.uniqueCode(loadNo),
                world.ref("ORIGIN"), world.ref("vehicle:" + LORRY));
        world.putRef("load:" + loadNo, loadId);
        for (UUID consignmentId : planning.generateConsignments(orderId)) {
            planning.assignConsignment(loadId, consignmentId);
        }
        planning.planLoad(loadId);
    }

    private UUID shipmentId(String orderNo, String consigneeCode) {
        UUID orderId = world.ref("order:" + orderNo);
        UUID consigneeId = world.ref(consigneeCode);
        return inTransaction(() -> consignments
                .findByOrder(TenantContext.requireTenantId(), orderId).stream()
                .filter(c -> c.consigneePartnerId().equals(consigneeId))
                .findFirst().orElseThrow().id());
    }

    private List<UUID> allocationIds(String loadNo) {
        UUID loadId = world.ref("load:" + loadNo);
        return inTransaction(() -> jdbc.sql(
                        "SELECT id FROM allocation WHERE load_id = :load ORDER BY created_at")
                .param("load", loadId).query(UUID.class).list());
    }

    private void assertTrail(List<UUID> resources, String expected) {
        List<String> actions = inTransaction(() -> jdbc.sql("""
                        SELECT action FROM audit_log
                         WHERE tenant_id = :tenant AND resource_id IN (:ids)
                         ORDER BY occurred_at, id
                        """)
                .param("tenant", TenantContext.requireTenantId())
                .param("ids", resources)
                .query(String.class).list());
        assertThat(actions).containsExactly(expected.split(",\\s*"));
    }

    private void assertMoved(String action, UUID resourceId, String from, String to) {
        Map<String, Object> row = single(action, resourceId);
        assertThat(String.valueOf(row.get("before_state"))).contains(from);
        assertThat(String.valueOf(row.get("after_state"))).contains(to);
    }

    private Map<String, Object> single(String action, UUID resourceId) {
        List<Map<String, Object>> rows = inTransaction(() -> jdbc.sql("""
                        SELECT before_state::text AS before_state, after_state::text AS after_state
                          FROM audit_log
                         WHERE tenant_id = :tenant AND resource_id = :id AND action = :action
                        """)
                .param("tenant", TenantContext.requireTenantId())
                .param("id", resourceId)
                .param("action", action)
                .query().listOfRows());
        assertThat(rows).as("audit entries %s for %s", action, resourceId).hasSize(1);
        return rows.get(0);
    }

    private <T> T inTransaction(Supplier<T> action) {
        TenantContext.requireTenantId();
        return new TransactionTemplate(transactionManager).execute(status -> action.get());
    }
}
