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
 */package com.lms.acceptance;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import com.lms.order.query.OrderQueryService;
import com.lms.shared.tenant.TenantContext;
import io.cucumber.java.en.Then;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The few assertions the end-to-end walk needs that no single context owns.
 *
 * <p>Everything else in {@code spine.feature} is composed from the step
 * definitions the individual features already use, which is the point of the
 * scenario: if the walk needed its own vocabulary it would be testing a
 * parallel implementation rather than the one the other features describe.
 */
public class SpineSteps {

    @Autowired
    private OrderQueryService orders;
    @Autowired
    private JdbcClient jdbc;
    @Autowired
    private PlatformTransactionManager transactionManager;

    /**
     * What another tenant can see of this one's work, which should be nothing.
     *
     * <p>Asserted at the end of the walk rather than the beginning, because by
     * then the first tenant has an order, a load, a trip and a freight bill --
     * so a leak has something to leak.
     */
    @Then("tenant {string} sees {int} orders")
    public void tenantSeesOrders(String tenantCode, int expected) {
        assertThat(inTransaction(() -> orders.list(null, null, null)).items())
                .as("orders visible while scoped to %s", tenantCode)
                .hasSize(expected);
    }

    /**
     * A mutation recorded with both sides of the change.
     *
     * <p>"Before and after" is the whole value of an audit row. An entry saying
     * only that an invoice was approved answers "was it approved" -- which
     * nobody asks. What an investigation wants is what it was approved
     * <em>from</em>, and against what figure.
     */
    @Then("the audit log records {string} with before and after snapshots")
    public void auditLogRecords(String action) {
        List<Map<String, Object>> rows = inTransaction(() -> jdbc.sql("""
                        SELECT action, before_state, after_state, correlation_id
                          FROM audit_log
                         WHERE tenant_id = :tenantId AND action = :action
                         ORDER BY occurred_at DESC
                        """)
                .param("tenantId", TenantContext.requireTenantId())
                .param("action", action)
                .query()
                .listOfRows());

        assertThat(rows).as("audit entries for %s", action).isNotEmpty();

        Map<String, Object> latest = rows.get(0);
        assertThat(String.valueOf(latest.get("before_state")))
                .as("the state before %s", action)
                .contains("status");
        assertThat(String.valueOf(latest.get("after_state")))
                .as("the state after %s", action)
                .contains("status");
    }

    /** An audit entry exists, whatever it carries. */
    @Then("the audit log records {string}")
    public void auditLogHas(String action) {
        Integer count = inTransaction(() -> jdbc.sql(
                        "SELECT count(*) FROM audit_log WHERE tenant_id = :tenantId AND action = :action")
                .param("tenantId", TenantContext.requireTenantId())
                .param("action", action)
                .query(Integer.class)
                .single());

        assertThat(count).as("audit entries for %s", action).isPositive();
    }

    private <T> T inTransaction(Supplier<T> action) {
        return new TransactionTemplate(transactionManager).execute(status -> action.get());
    }
}
