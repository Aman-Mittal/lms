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
package com.lms.masterdata;

import java.util.UUID;

import com.lms.masterdata.command.TerminalReferenceCheck;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * "Is this terminal still used" must be an index probe per referencing table,
 * and must keep being one as the schema grows.
 *
 * <p>The list of referencing tables comes from the database's own foreign
 * keys, so a migration that adds a table with a key to {@code terminal}
 * changes the statement this test inspects. If that table has no index on
 * {@code (tenant_id, column)} the plan falls back to a sequential scan and
 * this fails -- the fix is a migration adding the index, as V34 did for the
 * keys that had none, not a change here. The planner is told to avoid
 * sequential scans because on near-empty tables it would always choose one;
 * what is asked is whether an index is there to be chosen.
 */
@SpringBootTest
class TerminalReferencePlanTest {

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17.7-alpine")
            .withDatabaseName("lms").withUsername("lms").withPassword("lms");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    private TerminalReferenceCheck check;
    @Autowired
    private JdbcClient jdbc;
    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    @DisplayName("the check covers every table that refers to a terminal, and reads each through an index")
    void everyReferenceIsIndexServed() {
        String sql = check.existenceQuery()
                .replace(":tenantId", "'00000000-0000-0000-0000-000000000001'")
                .replace(":terminalId", "'00000000-0000-0000-0000-000000000002'");

        assertThat(sql).as("it names the tables it probes")
                .contains("\"trip\"", "\"load_unit\"", "\"consignment\"", "\"gate_event\"",
                        "\"warehouse_bin\"", "\"rail_tariff\"", "\"circuit_run_stop\"");
        // ON DELETE CASCADE keys hang off the terminal and are not references.
        assertThat(sql).doesNotContain("\"geofence_presence\"").doesNotContain("\"ltl_zone_terminal\"");

        String plan = new TransactionTemplate(transactionManager).execute(status -> {
            jdbc.sql("SET LOCAL enable_seqscan = off").update();
            return String.join("\n", jdbc.sql("EXPLAIN " + sql).query(String.class).list());
        });

        assertThat(plan)
                .as("a referencing table with no usable index on (tenant_id, column):%n%s", plan)
                .doesNotContain("Seq Scan");
        assertThat(plan).contains("Index");
    }

    @Test
    @DisplayName("nothing is reported for a terminal nothing refers to")
    void unreferenced() {
        assertThat(check.usedBy(UUID.randomUUID(), UUID.randomUUID())).isEmpty();
    }
}
