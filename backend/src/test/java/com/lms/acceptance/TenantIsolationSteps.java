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

import java.util.UUID;
import java.util.function.Function;

import com.lms.shared.tenant.TenantContext;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Step definitions for {@code tenant_isolation.feature}.
 *
 * <p>Every statement runs inside a transaction opened through the
 * application's own tenant-aware transaction manager, with the scope bound
 * first -- the same order production uses -- so what is asserted is what a
 * request handler could do, not what a superuser connection can.
 */
public class TenantIsolationSteps {

    /**
     * Seeded once and shared: building a trip's worth of rows for two tenants
     * costs more than every assertion that follows, and the rows are only
     * ever read, probed and left as they were.
     */
    private static volatile TenantSeeds.Seeded acme;
    private static volatile TenantSeeds.Seeded globex;

    @Autowired
    private TenantSeeds seeds;
    @Autowired
    private JdbcClient jdbc;
    @Autowired
    private PlatformTransactionManager transactionManager;

    @Given("two tenants {string} and {string} each holding one row of everything")
    public void twoSeededTenants(String first, String second) {
        synchronized (TenantIsolationSteps.class) {
            if (acme == null) {
                acme = seeds.seed(0.0);
                globex = seeds.seed(1.0);
            }
        }
        assertThat(first).isEqualTo("acme");
        assertThat(second).isEqualTo("globex");
    }

    @Given("the session role is the unprivileged application role")
    public void theRoleIsUnprivileged() {
        // Not an assumption: a superuser or owner would sail past every
        // policy, and every assertion after this would pass for the wrong reason.
        String role = scoped(globex, db -> db.sql("SELECT current_user").query(String.class).single());
        assertThat(role).isEqualTo("lms_app");
    }

    @Then("a session scoped to {string} finds no {word} row with acme's id")
    public void findsNothingById(String tenant, String table) {
        long found = scoped(tenantOf(tenant), db -> db.sql(
                        "SELECT count(*) FROM " + table + " WHERE id = :id")
                .param("id", acme.id(table)).query(Long.class).single());
        assertThat(found).isZero();
    }

    @Then("a session scoped to {string} sees only its own {word} rows")
    public void seesOnlyItsOwn(String tenant, String table) {
        TenantSeeds.Seeded own = tenantOf(tenant);
        // No WHERE clause at all: the database alone must do the confining.
        long total = scoped(own, db -> db.sql("SELECT count(*) FROM " + table)
                .query(Long.class).single());
        long ours = scoped(own, db -> db.sql(
                        "SELECT count(*) FROM " + table + " WHERE tenant_id = :t")
                .param("t", own.tenantId()).query(Long.class).single());
        assertThat(total).isPositive().isEqualTo(ours);
    }

    @Then("a session scoped to {string} updating acme's {word} row changes nothing")
    public void updateChangesNothing(String tenant, String table) {
        int changed = scoped(tenantOf(tenant), db -> db.sql(
                        "UPDATE " + table + " SET tenant_id = tenant_id WHERE id = :id")
                .param("id", acme.id(table)).update());
        assertThat(changed).isZero();
    }

    @Then("a session scoped to {string} deleting acme's {word} row removes nothing")
    public void deleteRemovesNothing(String tenant, String table) {
        int removed = scoped(tenantOf(tenant), db -> db.sql(
                        "DELETE FROM " + table + " WHERE id = :id")
                .param("id", acme.id(table)).update());
        assertThat(removed).isZero();
    }

    @Then("a session scoped to {string} cannot reassign its own {word} row to {string}")
    public void cannotHijack(String tenant, String table, String victim) {
        TenantSeeds.Seeded own = tenantOf(tenant);
        UUID victimTenant = tenantOf(victim).tenantId();
        // WITH CHECK: a row may not be written out of the scope that wrote it.
        assertThatThrownBy(() -> scoped(own, db -> db.sql(
                        "UPDATE " + table + " SET tenant_id = CAST(:victim AS uuid) WHERE id = :id")
                .param("victim", victimTenant).param("id", own.id(table)).update()))
                .isInstanceOf(DataAccessException.class)
                .rootCause().hasMessageContaining("row-level security");
    }

    @Then("acme's {word} row is untouched")
    public void rowIsUntouched(String table) {
        // Read as acme: the row is still there and still acme's.
        UUID owner = scoped(acme, db -> db.sql(
                        "SELECT tenant_id FROM " + table + " WHERE id = :id")
                .param("id", acme.id(table)).query(UUID.class).single());
        assertThat(owner).isEqualTo(acme.tenantId());
    }

    @Then("a session with no tenant scope sees no {word} rows")
    public void unscopedSeesNothing(String table) {
        Long visible = new TransactionTemplate(transactionManager).execute(status ->
                jdbc.sql("SELECT count(*) FROM " + table).query(Long.class).single());
        assertThat(visible).isZero();
    }

    private TenantSeeds.Seeded tenantOf(String name) {
        return switch (name) {
            case "acme" -> acme;
            case "globex" -> globex;
            default -> throw new IllegalArgumentException("Unknown tenant " + name);
        };
    }

    private <T> T scoped(TenantSeeds.Seeded tenant, Function<JdbcClient, T> work) {
        return TenantContext.callWith(tenant.tenantId(), tenant.orgPath(),
                () -> new TransactionTemplate(transactionManager).execute(status -> work.apply(jdbc)));
    }
}
