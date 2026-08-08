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
package com.lms.query;

import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Asserts that the hot queries are served by indexes.
 *
 * <p>Query plans are invisible in ordinary tests: against a handful of rows a
 * sequential scan is not merely acceptable, it is what the planner will
 * correctly choose, and the query looks fine right up to the point where a
 * tenant has a million rows and a 0.1-CPU instance has to walk all of them.
 *
 * <p>So rather than measuring speed, this inspects the plan directly, with the
 * planner told to prefer indexes where it has the choice. What it is really
 * checking is that a supporting index <em>exists</em> — if none does, no amount
 * of coaxing produces an index scan.
 *
 * <p>When this fails, the fix is a migration adding the index, not a change
 * here.
 */
@SpringBootTest
class QueryPlanTest {

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
    private JdbcClient jdbc;

    @BeforeAll
    static void note() {
        // Documented rather than enforced here: with an empty table the planner
        // rightly prefers a sequential scan, so enable_seqscan is disabled below
        // to reveal whether an index is even available to be chosen.
    }

    @Test
    @DisplayName("user lookup by tenant and email is index-served")
    void userByTenantAndEmail() {
        assertIndexed("""
                SELECT * FROM app_user
                 WHERE tenant_id = '00000000-0000-0000-0000-000000000001'
                   AND lower(email) = 'someone@example.test'
                """, "app_user");
    }

    @Test
    @DisplayName("organisational subtree prefix match is index-served")
    void orgSubtreePrefix() {
        // The reason org_unit_path_prefix_idx uses text_pattern_ops: without it
        // a LIKE 'prefix%' cannot use the index under a non-C collation.
        assertIndexed("""
                SELECT * FROM org_unit
                 WHERE tenant_id = '00000000-0000-0000-0000-000000000001'
                   AND path LIKE '/acme/north/' || '%'
                """, "org_unit");
    }

    @Test
    @DisplayName("terminal bounding-box candidate filter is index-served")
    void terminalBoundingBox() {
        // This is the substitute for a spatial index (DOCS/adr/0004) and runs on
        // every GPS ping. If it ever degrades to a sequential scan, telematics
        // ingest degrades with it.
        assertIndexed("""
                SELECT * FROM terminal
                 WHERE tenant_id = '00000000-0000-0000-0000-000000000001'
                   AND min_lat <= 19.5 AND max_lat >= 19.5
                   AND min_lon <= 72.8 AND max_lon >= 72.8
                """, "terminal");
    }

    @Test
    @DisplayName("compliance document expiry lookup is index-served")
    void complianceDocumentExpiry() {
        assertIndexed("""
                SELECT * FROM compliance_document
                 WHERE tenant_id = '00000000-0000-0000-0000-000000000001'
                   AND owner_type = 'VEHICLE'
                   AND owner_id = '00000000-0000-0000-0000-000000000002'
                """, "compliance_document");
    }

    @Test
    @DisplayName("active refresh token lookup by hash is index-served")
    void refreshTokenByHash() {
        assertIndexed("""
                SELECT * FROM refresh_token WHERE token_hash = 'deadbeef'
                """, "refresh_token");
    }

    @Test
    @DisplayName("the certificate expiry list is served by the partial index")
    void complianceExpiryList() {
        // compliance_document_expiry_idx is partial on `expires_on IS NOT NULL`.
        // The predicate below has to match that partiality or the planner
        // cannot use it -- which is the kind of mismatch that only shows up as
        // a slow screen months later.
        assertIndexed("""
                SELECT * FROM compliance_document
                 WHERE tenant_id = '00000000-0000-0000-0000-000000000001'
                   AND expires_on IS NOT NULL
                   AND expires_on <= current_date + 30
                 ORDER BY expires_on
                """, "compliance_document");
    }

    @Test
    @DisplayName("the fleet listing pages by registration without a sort")
    void vehicleListingIsKeysetSeekable() {
        // The keyset predicate and the ORDER BY have to ride the same unique
        // index, or every page costs a sort of the whole fleet.
        assertIndexed("""
                SELECT * FROM vehicle
                 WHERE tenant_id = '00000000-0000-0000-0000-000000000001'
                   AND registration_no > 'MH-01-AB-0000'
                 ORDER BY registration_no
                 LIMIT 50
                """, "vehicle");
    }

    @Test
    @DisplayName("the rate card in force on a date is index-served")
    void tariffInForceOnADate() {
        // Runs once per completed trip, and the shape matters as much as the
        // presence: effective_from descending is what lets the plan stop at the
        // first row instead of reading a lane's whole rate history to sort it.
        assertIndexed("""
                SELECT * FROM tariff
                 WHERE tenant_id = '00000000-0000-0000-0000-000000000001'
                   AND vendor_partner_id = '00000000-0000-0000-0000-000000000002'
                   AND origin_terminal_id = '00000000-0000-0000-0000-000000000003'
                   AND destination_terminal_id = '00000000-0000-0000-0000-000000000004'
                   AND vehicle_type = 'RIGID'
                   AND effective_from <= DATE '2026-01-15'
                   AND (effective_to IS NULL OR effective_to >= DATE '2026-01-15')
                 ORDER BY effective_from DESC
                 LIMIT 1
                """, "tariff");
    }

    @Test
    @DisplayName("the settlement queue is index-served")
    void freightBillsByStatus() {
        assertIndexed("""
                SELECT * FROM freight_bill
                 WHERE tenant_id = '00000000-0000-0000-0000-000000000001'
                   AND status = 'DISPUTED'
                 ORDER BY created_at DESC
                """, "freight_bill");
    }

    @Test
    @DisplayName("every tenant-scoped table leads its primary lookup index with tenant_id")
    void indexesLeadWithTenantId() {
        // Row-level security adds `tenant_id = …` to every query, so an index
        // that does not lead with it cannot serve the combined predicate.
        List<String> offenders = jdbc.sql("""
                        SELECT t.relname || '.' || i.relname
                          FROM pg_index x
                          JOIN pg_class i ON i.oid = x.indexrelid
                          JOIN pg_class t ON t.oid = x.indrelid
                          JOIN pg_namespace n ON n.oid = t.relnamespace
                         WHERE n.nspname = 'public'
                           AND NOT x.indisprimary
                           AND NOT x.indisunique
                           AND EXISTS (SELECT 1 FROM information_schema.columns c
                                        WHERE c.table_name = t.relname
                                          AND c.column_name = 'tenant_id')
                           -- Cross-tenant maintenance indexes are the one
                           -- exemption, and they must say so in their name.
                           -- Housekeeping sweeps run platform-wide, so leading
                           -- with tenant_id would defeat their only purpose.
                           AND i.relname NOT LIKE '%\\_sweep\\_idx'
                           AND (SELECT a.attname FROM pg_attribute a
                                 WHERE a.attrelid = t.oid AND a.attnum = x.indkey[0]) <> 'tenant_id'
                        """)
                .query(String.class)
                .list();

        assertThat(offenders)
                .as("non-unique indexes on tenant-scoped tables must lead with tenant_id")
                .isEmpty();
    }

    private void assertIndexed(String sql, String table) {
        // Disabling seqscan is a probe, not a recommendation: on an empty table
        // the planner would otherwise always choose one, and the question here
        // is whether a usable index exists at all.
        jdbc.sql("SET LOCAL enable_seqscan = off").update();

        String plan = String.join("\n",
                jdbc.sql("EXPLAIN " + sql).query(String.class).list());

        assertThat(plan)
                .as("query over %s should not fall back to a sequential scan:%n%s", table, plan)
                .doesNotContain("Seq Scan on " + table);
    }
}
