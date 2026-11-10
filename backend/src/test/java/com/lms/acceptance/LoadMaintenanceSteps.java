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

import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import com.lms.planning.command.ConsignmentCancellationService;
import com.lms.planning.command.ConsignmentRepository;
import com.lms.planning.command.LoadMaintenanceService;
import com.lms.planning.command.LoadUnitRepository;
import com.lms.shared.error.BusinessRuleViolationException;
import com.lms.shared.tenant.TenantContext;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Step definitions for {@code load_maintenance.feature}.
 *
 * <p>Each change is made at the load's current version, read just before, as
 * a planner who has the load open would; the stale steps send the version
 * before that. A refusal is kept by its code, and a lost optimistic lock as
 * the {@code concurrent-modification} the API answers with.
 */
public class LoadMaintenanceSteps {

    @Autowired
    private ScenarioWorld world;
    @Autowired
    private LoadMaintenanceService maintenance;
    @Autowired
    private ConsignmentCancellationService cancellations;
    @Autowired
    private LoadUnitRepository loads;
    @Autowired
    private ConsignmentRepository consignments;
    @Autowired
    private JdbcClient jdbc;
    @Autowired
    private PlatformTransactionManager transactionManager;

    private String refusal;

    @When("the shipment of order {string} for {string} is taken off load {string}")
    public void takeOff(String orderNo, String consigneeCode, String loadNo) {
        UUID loadId = world.ref("load:" + loadNo);
        UUID consignmentId = shipmentId(orderNo, consigneeCode);
        attempt(() -> maintenance.removeConsignment(loadId, consignmentId, versionOf(loadId)));
    }

    @When("the shipment of order {string} for {string} is taken off load {string} at a stale version")
    public void takeOffStale(String orderNo, String consigneeCode, String loadNo) {
        UUID loadId = world.ref("load:" + loadNo);
        UUID consignmentId = shipmentId(orderNo, consigneeCode);
        attempt(() -> maintenance.removeConsignment(loadId, consignmentId, versionOf(loadId) - 1));
    }

    @When("the shipment of order {string} for {string} is cancelled because {string}")
    public void cancelShipment(String orderNo, String consigneeCode, String reason) {
        UUID consignmentId = shipmentId(orderNo, consigneeCode);
        attempt(() -> cancellations.cancel(consignmentId, reason));
    }

    @When("load {string} is un-planned")
    public void unplan(String loadNo) {
        UUID loadId = world.ref("load:" + loadNo);
        attempt(() -> maintenance.unplanLoad(loadId, versionOf(loadId)));
    }

    @When("load {string} is un-planned at a stale version")
    public void unplanStale(String loadNo) {
        UUID loadId = world.ref("load:" + loadNo);
        attempt(() -> maintenance.unplanLoad(loadId, versionOf(loadId) - 1));
    }

    @When("load {string} is cancelled because {string}")
    public void cancel(String loadNo, String reason) {
        UUID loadId = world.ref("load:" + loadNo);
        attempt(() -> maintenance.cancelLoad(loadId, versionOf(loadId), reason));
    }

    @Then("the load change is refused with {string}")
    public void refused(String code) {
        assertThat(refusal).as("expected the change to be refused with '%s'", code).isEqualTo(code);
    }

    @Then("the drops of load {string} run from {int} to {int}")
    public void dropsRun(String loadNo, int first, int last) {
        UUID loadId = world.ref("load:" + loadNo);
        List<Integer> drops = inTransaction(() -> jdbc.sql("""
                        SELECT drop_sequence FROM load_consignment
                         WHERE tenant_id = :tenant AND load_id = :load ORDER BY drop_sequence
                        """)
                .param("tenant", TenantContext.requireTenantId())
                .param("load", loadId)
                .query(Integer.class).list());
        assertThat(drops).containsExactlyElementsOf(
                java.util.stream.IntStream.rangeClosed(first, last).boxed().toList());
    }

    // ---------------------------------------------------------------- helpers

    private long versionOf(UUID loadId) {
        return inTransaction(() -> loads.findById(loadId).orElseThrow().version());
    }

    private UUID shipmentId(String orderNo, String consigneeCode) {
        UUID orderId = world.ref("order:" + orderNo);
        UUID consigneeId = world.ref(consigneeCode);
        return inTransaction(() -> consignments
                .findByOrder(TenantContext.requireTenantId(), orderId).stream()
                .filter(c -> c.consigneePartnerId().equals(consigneeId))
                .findFirst().orElseThrow().id());
    }

    private void attempt(Runnable change) {
        refusal = null;
        try {
            change.run();
        } catch (BusinessRuleViolationException e) {
            refusal = e.code();
        } catch (OptimisticLockingFailureException e) {
            refusal = "concurrent-modification";
        }
    }

    private <T> T inTransaction(Supplier<T> action) {
        TenantContext.requireTenantId();
        return new TransactionTemplate(transactionManager).execute(status -> action.get());
    }
}
