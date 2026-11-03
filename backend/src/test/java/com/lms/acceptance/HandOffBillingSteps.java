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

import com.lms.clientbilling.query.ClientBillingQueryService;
import com.lms.clientbilling.query.ClientInvoiceLineView;
import com.lms.shared.tenant.TenantContext;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Assertions for {@code handoff_billing.feature}: the charges a warehouse
 * hand-off made, and the invoice of the period they fell in.
 */
public class HandOffBillingSteps {

    @Autowired
    private ScenarioWorld world;
    @Autowired
    private ClientBillingQueryService queries;
    @Autowired
    private JdbcClient jdbc;
    @Autowired
    private PlatformTransactionManager transactionManager;

    /**
     * A billing run refuses a period that has not ended, and a hand-off is
     * charged on the day it happens, so the charge is moved into last month
     * the way a hand-off made then would have been dated.
     */
    @When("the hand-off charges of {string} are dated day {int} of last month")
    public void dateLastMonth(String client, int day) {
        LocalDate on = LocalDate.now(ZoneOffset.UTC).minusMonths(1).withDayOfMonth(day);
        inTransaction(() -> jdbc.sql("""
                        UPDATE client_charge
                           SET occurred_on = :on, billing_period = :period
                         WHERE tenant_id = :tenantId AND client_partner_id = :client
                           AND source_key LIKE 'HANDOFF:%'
                        """)
                .param("on", on)
                .param("period", on.withDayOfMonth(1))
                .param("tenantId", TenantContext.requireTenantId())
                .param("client", world.ref(client))
                .update());
    }

    @Then("{string} was charged {bigdecimal} {string} for handling unit {string}")
    public void chargedForUnit(String client, BigDecimal quantity, String type, String unit) {
        List<BigDecimal> quantities = inTransaction(() -> jdbc.sql("""
                        SELECT quantity FROM client_charge
                         WHERE tenant_id = :tenantId AND client_partner_id = :client
                           AND service_type = :type AND source_key LIKE :key
                        """)
                .param("tenantId", TenantContext.requireTenantId())
                .param("client", world.ref(client))
                .param("type", type)
                .param("key", unitKey(unit))
                .query(BigDecimal.class)
                .list());
        assertThat(quantities).as("%s charges of %s for %s", type, client, unit).hasSize(1);
        assertThat(quantities.get(0)).isEqualByComparingTo(quantity);
    }

    @Then("{string} has {int} charges for handling unit {string}")
    public void chargeCount(String client, int expected, String unit) {
        assertThat(count(world.ref(client), unit)).isEqualTo(expected);
    }

    @Then("no client was charged for handling unit {string}")
    public void noCharge(String unit) {
        assertThat(count(null, unit)).isZero();
    }

    @Then("the invoice of {string} has a {string} line of {bigdecimal}")
    public void invoiceLine(String client, String type, BigDecimal amount) {
        UUID invoiceId = world.ref("client-invoice:" + client);
        List<ClientInvoiceLineView> lines = inTransaction(() -> queries.linesOf(invoiceId));
        List<ClientInvoiceLineView> matching = lines.stream()
                .filter(line -> line.serviceType().equals(type)).toList();
        assertThat(matching).as("%s lines", type).hasSize(1);
        assertThat(matching.get(0).amount()).isEqualByComparingTo(amount);
    }

    private long count(UUID client, String unit) {
        return inTransaction(() -> jdbc.sql("""
                        SELECT count(*) FROM client_charge
                         WHERE tenant_id = :tenantId AND source_key LIKE :key
                           AND (CAST(:client AS uuid) IS NULL OR client_partner_id = :client)
                        """)
                .param("tenantId", TenantContext.requireTenantId())
                .param("client", client)
                .param("key", unitKey(unit))
                .query(Long.class)
                .single());
    }

    private String unitKey(String unit) {
        return "HANDOFF:" + world.ref("hu:" + unit) + ":%";
    }

    private <T> T inTransaction(Supplier<T> action) {
        return new TransactionTemplate(transactionManager).execute(status -> action.get());
    }
}
