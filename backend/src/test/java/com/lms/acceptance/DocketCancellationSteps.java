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
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import com.lms.finance.command.DocketReceivableRepository;
import com.lms.finance.command.domain.DocketReceivable;
import com.lms.planning.command.ConsignmentRepository;
import com.lms.planning.events.ConsignmentsCancelled;
import com.lms.shared.tenant.TenantContext;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/** Step definitions for {@code docket_cancellation.feature}. */
public class DocketCancellationSteps {

    @Autowired
    private ScenarioWorld world;
    @Autowired
    private ConsignmentRepository consignments;
    @Autowired
    private DocketReceivableRepository receivables;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private ApplicationEventPublisher events;
    @Autowired
    private JdbcClient jdbc;

    /**
     * The event the registry would redeliver after a restart, published again
     * in a transaction so that its after-commit listener runs as it does for
     * the original.
     */
    @When("the cancellation of the dockets of order {string} is delivered again")
    public void deliveredAgain(String orderNo) {
        UUID orderId = world.ref("order:" + orderNo);
        List<UUID> dockets = inTransaction(() -> consignments
                .findByOrder(TenantContext.requireTenantId(), orderId).stream()
                .map(c -> c.id()).toList());
        publish(orderId, dockets);
    }

    @When("a cancellation naming an unknown docket is delivered")
    public void unknownDocket() {
        publish(UUID.randomUUID(), List.of(UUID.randomUUID()));
    }

    @Then("the receivable for order {string} and {string} was cancelled in the audit trail exactly once")
    public void auditedOnce(String orderNo, String consigneeCode) {
        UUID receivableId = receivable(orderNo, consigneeCode).id();
        Integer rows = inTransaction(() -> jdbc.sql("""
                        SELECT count(*) FROM audit_log
                         WHERE tenant_id = :tenant AND resource_id = :id
                           AND action = 'DOCKET_RECEIVABLE_CANCELLED'
                        """)
                .param("tenant", TenantContext.requireTenantId()).param("id", receivableId)
                .query(Integer.class).single());
        assertThat(rows).isEqualTo(1);
    }

    @Then("no receivable is cancelled")
    public void noneCancelled() {
        Integer cancelled = inTransaction(() -> jdbc.sql(
                        "SELECT count(*) FROM docket_receivable WHERE status = 'CANCELLED'")
                .query(Integer.class).single());
        assertThat(cancelled).isZero();
    }

    private void publish(UUID orderId, List<UUID> dockets) {
        UUID tenantId = TenantContext.requireTenantId();
        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                events.publishEvent(new ConsignmentsCancelled(tenantId, orderId, dockets, dockets,
                        Instant.now())));
    }

    @Then("the receivable for order {string} and {string} is cancelled because {string}")
    public void cancelled(String orderNo, String consigneeCode, String fragment) {
        DocketReceivable receivable = receivable(orderNo, consigneeCode);
        assertThat(receivable.status().name()).isEqualTo("CANCELLED");
        assertThat(receivable.reason()).contains(fragment);
    }

    @Then("the cancelled receivable for order {string} and {string} still records the quoted {double}")
    public void stillRecords(String orderNo, String consigneeCode, double total) {
        DocketReceivable receivable = receivable(orderNo, consigneeCode);
        assertThat(receivable.status().name()).isEqualTo("CANCELLED");
        assertThat(receivable.totalAmount()).isEqualByComparingTo(BigDecimal.valueOf(total));
    }

    private DocketReceivable receivable(String orderNo, String consigneeCode) {
        UUID orderId = world.ref("order:" + orderNo);
        UUID consigneeId = world.ref(consigneeCode);
        return inTransaction(() -> {
            UUID tenantId = TenantContext.requireTenantId();
            UUID docketId = consignments.findByOrder(tenantId, orderId).stream()
                    .filter(c -> c.consigneePartnerId().equals(consigneeId))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("no consignment for " + consigneeCode))
                    .id();
            return receivables.findByDocket(tenantId, docketId)
                    .orElseThrow(() -> new AssertionError("no receivable for the docket"));
        });
    }

    private <T> T inTransaction(Supplier<T> action) {
        TenantContext.requireTenantId();
        return new TransactionTemplate(transactionManager).execute(status -> action.get());
    }
}
