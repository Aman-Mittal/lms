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
package com.lms.identity.command;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.lms.acceptance.TenantSeeds;
import com.lms.acceptance.TenantSeeds.Seeded;
import com.lms.shared.tenant.TenantContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A demo reset leaves nothing of the tenant behind, in any table, and touches
 * no other tenant.
 *
 * <p>This is the guard against a table being forgotten: every table with a
 * {@code tenant_id} is read from the catalog here, as the reset itself reads
 * it, and each must hold no row of the reset tenant afterwards unless
 * {@link DemoResetPlan#KEPT} names it with a reason. Counting runs as the
 * container's superuser, which row-level security does not filter, so a row
 * the reset could not see would still be counted.
 */
@SpringBootTest
class DemoResetCoverageIntegrationTest {

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17.7-alpine")
            .withDatabaseName("lms").withUsername("lms").withPassword("lms");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    private DemoResetService resets;
    @Autowired
    private TenantSeeds seeds;
    @Autowired
    private JdbcClient jdbc;
    @Autowired
    private PlatformTransactionManager transactionManager;

    private List<String> tenantTables() {
        return jdbc.sql("""
                        SELECT c.table_name
                          FROM information_schema.columns c
                          JOIN information_schema.tables t
                            ON t.table_schema = c.table_schema AND t.table_name = c.table_name
                         WHERE c.table_schema = 'public'
                           AND c.column_name = 'tenant_id'
                           AND t.table_type = 'BASE TABLE'
                         ORDER BY c.table_name
                        """)
                .query(String.class)
                .list();
    }

    private Map<String, Long> rowsOf(UUID tenantId) {
        Map<String, Long> rows = new LinkedHashMap<>();
        for (String table : tenantTables()) {
            rows.put(table, jdbc.sql("SELECT count(*) FROM \"" + table + "\" WHERE tenant_id = :t")
                    .param("t", tenantId)
                    .query(Long.class)
                    .single());
        }
        return rows;
    }

    private long wipe(Seeded tenant) {
        TransactionTemplate transactions = new TransactionTemplate(transactionManager);
        Long deleted = TenantContext.callWith(tenant.tenantId(), tenant.orgPath(),
                () -> transactions.execute(status -> resets.wipe(tenant.tenantId())));
        return deleted == null ? 0 : deleted;
    }

    @Test
    @DisplayName("every table with a tenant_id is cleared of the demo tenant, and another tenant keeps every row")
    void everyTenantTableIsCleared() {
        Seeded demo = seeds.seed(0.0);
        Seeded other = seeds.seed(1.0);
        jdbc.sql("UPDATE tenant SET is_demo = true WHERE id = :t").param("t", demo.tenantId()).update();

        Map<String, Long> before = rowsOf(demo.tenantId());
        Map<String, Long> otherBefore = rowsOf(other.tenantId());
        assertThat(before.values().stream().mapToLong(Long::longValue).sum())
                .as("the seeded tenant has rows to clear").isPositive();

        long deleted = wipe(demo);

        Map<String, Long> after = rowsOf(demo.tenantId());
        after.keySet().removeAll(DemoResetPlan.KEPT.keySet());
        assertThat(after).as("tables still holding the demo tenant's rows")
                .allSatisfy((table, rows) -> assertThat(rows).as(table).isZero());
        assertThat(deleted).isEqualTo(before.values().stream().mapToLong(Long::longValue).sum());
        assertThat(rowsOf(other.tenantId())).as("the other tenant").isEqualTo(otherBefore);
        assertThat(jdbc.sql("SELECT count(*) FROM tenant WHERE id = :t").param("t", demo.tenantId())
                .query(Long.class).single()).as("the tenant row itself stays").isOne();

        assertThat(wipe(demo)).as("a second reset finds nothing").isZero();
    }

    @Test
    @DisplayName("the database refuses to clear a tenant that is not marked as the demo")
    void realTenantRefused() {
        Seeded real = seeds.seed(2.0);
        Map<String, Long> before = rowsOf(real.tenantId());

        assertThatThrownBy(() -> wipe(real)).isInstanceOf(DataAccessException.class)
                .hasMessageContaining("not the demo tenant");
        assertThat(rowsOf(real.tenantId())).isEqualTo(before);
    }

    @Test
    @DisplayName("every table the reset keeps exists and is a tenant table")
    void keptTablesAreReal() {
        assertThat(tenantTables()).containsAll(DemoResetPlan.KEPT.keySet());
    }
}
