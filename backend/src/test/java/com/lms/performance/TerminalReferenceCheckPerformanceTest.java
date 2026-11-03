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
package com.lms.performance;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import com.lms.acceptance.SpineFixtures;
import com.lms.acceptance.TestFixtures;
import com.lms.execution.command.TripCommandService;
import com.lms.masterdata.command.FleetCommandService;
import com.lms.masterdata.command.RegisterTerminalCommand;
import com.lms.masterdata.command.TerminalCommandService;
import com.lms.masterdata.command.TerminalReferenceCheck;
import com.lms.masterdata.command.domain.Terminal;
import com.lms.shared.geo.LatLon;
import com.lms.shared.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Asking "is this terminal still used" must not get dearer as the tables
 * behind the answer grow.
 *
 * <p>The check is one statement of {@code EXISTS} probes, each on an index.
 * A probe that quietly became a scan -- a new foreign key with no index, a
 * rewrite that counts instead of testing existence -- is invisible at ten
 * rows and a stall at a million, so this runs the check against the same
 * tenant at 40 and then at 400 referencing rows (with ten times as many rows
 * for other terminals beside them, so the tables themselves grow tenfold) and
 * compares, never against a wall-clock threshold:
 * <ul>
 *   <li><b>statements</b>, counted by the database ({@code pg_stat_statements}):
 *       the same at both sizes -- one, however many rows there are;</li>
 *   <li><b>buffers touched</b> by the statement ({@code EXPLAIN (ANALYZE,
 *       BUFFERS)}): a probe reads a few index pages, so the total must not
 *       follow the row count, for a hit (stops at the first referencing row)
 *       or a miss (every probe comes back empty);</li>
 *   <li><b>plan</b>: no sequential scan at either size;</li>
 *   <li><b>time</b>: the median call at 400 within a small factor of 40.</li>
 * </ul>
 *
 * <p>Tagged {@code performance}: excluded from the default build, run with
 * {@code ./mvnw verify -Pperformance}.
 */
@Tag("performance")
@SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TerminalReferenceCheckPerformanceTest {

    private static final Logger log = LoggerFactory.getLogger(TerminalReferenceCheckPerformanceTest.class);

    private static final int SMALL = 40;
    private static final int LARGE = 400;
    private static final int NOISE_PER_ROW = 10;
    private static final int CALLS = 25;

    /** Median call time may grow this much between the sizes; an index probe stays near 1. */
    private static final double MAX_TIME_GROWTH = 3.0;

    /** Buffers touched may grow this much, plus a little slack for a deeper index. */
    private static final double MAX_BUFFER_GROWTH = 1.5;
    private static final long BUFFER_SLACK = 8;

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17.7-alpine")
            .withDatabaseName("lms").withUsername("lms").withPassword("lms")
            .withCommand("postgres", "-c", "shared_preload_libraries=pg_stat_statements",
                    "-c", "pg_stat_statements.track=all", "-c", "shared_buffers=128MB");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    private TestFixtures fixtures;
    @Autowired
    private SpineFixtures spine;
    @Autowired
    private FleetCommandService fleet;
    @Autowired
    private TerminalCommandService terminals;
    @Autowired
    private TripCommandService trips;
    @Autowired
    private TerminalReferenceCheck check;
    @Autowired
    private JdbcClient jdbc;
    @Autowired
    private PlatformTransactionManager transactionManager;

    private TransactionTemplate transactions;
    private TripScaffold.Built scaffold;
    private UUID tripId;
    private UUID referenced;
    private UUID unreferenced;
    private UUID bystander;

    private record Measured(long statements, long buffers, long medianNanos, boolean seqScan) {
    }

    @BeforeAll
    void aTenantWithTerminals() {
        transactions = new TransactionTemplate(transactionManager);
        jdbc.sql("CREATE EXTENSION IF NOT EXISTS pg_stat_statements").update();
        scaffold = TripScaffold.build(fixtures, spine, fleet, terminals, trips, 20.0);
        tripId = scaffold.tripId();
        bystander = jdbc.sql("SELECT origin_terminal_id FROM trip WHERE id = :id")
                .param("id", tripId).query(UUID.class).single();
        referenced = register("REF", 77.0);
        unreferenced = register("FREE", 77.5);
    }

    @AfterEach
    void signOut() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("the reference check costs the same at 400 referencing rows as at 40")
    void referenceCheckDoesNotGrowWithTheTables() {
        // Warm the code path and the catalog read once, outside the measurement.
        inTenant(() -> check.usedBy(scaffold.tenantId(), referenced));

        fill(SMALL);
        Measured hitSmall = measure(referenced);
        Measured missSmall = measure(unreferenced);
        fill(LARGE - SMALL);
        Measured hitLarge = measure(referenced);
        Measured missLarge = measure(unreferenced);

        assertThat(inTenant(() -> check.usedBy(scaffold.tenantId(), referenced)))
                .as("the referenced terminal is reported").contains("gate events");
        assertThat(inTenant(() -> check.usedBy(scaffold.tenantId(), unreferenced))).isEmpty();
        log.info("hit: {} rows {} statements {} buffers {} us; {} rows {} statements {} buffers {} us",
                SMALL, hitSmall.statements(), hitSmall.buffers(), hitSmall.medianNanos() / 1000,
                LARGE, hitLarge.statements(), hitLarge.buffers(), hitLarge.medianNanos() / 1000);
        log.info("miss: {} rows {} statements {} buffers {} us; {} rows {} statements {} buffers {} us",
                SMALL, missSmall.statements(), missSmall.buffers(), missSmall.medianNanos() / 1000,
                LARGE, missLarge.statements(), missLarge.buffers(), missLarge.medianNanos() / 1000);

        assertNotDearer("hit", hitSmall, hitLarge);
        assertNotDearer("miss", missSmall, missLarge);
    }

    private void assertNotDearer(String what, Measured small, Measured large) {
        assertThat(small.statements()).as("statements for one check at %d rows", SMALL).isEqualTo(1);
        assertThat(large.statements()).as("statements for one check at %d rows", LARGE).isEqualTo(1);
        assertThat(small.seqScan() || large.seqScan())
                .as("%s: the plan has a sequential scan", what).isFalse();
        assertThat(large.buffers())
                .as("%s: buffers touched at %d rows relative to %d", what, LARGE, SMALL)
                .isLessThanOrEqualTo((long) (small.buffers() * MAX_BUFFER_GROWTH) + BUFFER_SLACK);
        assertThat((double) large.medianNanos())
                .as("%s: median call at %d rows relative to %d", what, LARGE, SMALL)
                .isLessThanOrEqualTo(small.medianNanos() * MAX_TIME_GROWTH + 2_000_000);
    }

    // ------------------------------------------------------------ measuring

    private Measured measure(UUID terminalId) {
        jdbc.sql("SELECT pg_stat_statements_reset()").query().singleRow();
        List<Long> nanos = new ArrayList<>();
        for (int i = 0; i < CALLS; i++) {
            long started = System.nanoTime();
            inTenant(() -> check.usedBy(scaffold.tenantId(), terminalId));
            nanos.add(System.nanoTime() - started);
        }
        long calls = jdbc.sql("""
                        SELECT coalesce(sum(calls), 0)::bigint FROM pg_stat_statements
                         WHERE dbid = (SELECT oid FROM pg_database WHERE datname = current_database())
                           AND query LIKE '%UNION ALL%'
                        """).query(Long.class).single();
        Collections.sort(nanos);

        String sql = check.existenceQuery()
                .replace(":tenantId", "'" + scaffold.tenantId() + "'")
                .replace(":terminalId", "'" + terminalId + "'");
        // The planner is told to avoid sequential scans: on tables this small it
        // would sometimes pick one, legitimately, and what is asked here is how
        // an index probe scales, not where the planner's crossover lies. Scope
        // first, transaction second, so row-level security shows the tenant's rows.
        String plan = inTenant(() -> transactions.execute(status -> {
            jdbc.sql("SET LOCAL enable_seqscan = off").update();
            return jdbc.sql("EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON) " + sql)
                    .query(String.class).single();
        }));
        JsonNode root = JSON.readTree(plan).get(0).get("Plan");
        long buffers = root.path("Shared Hit Blocks").asLong() + root.path("Shared Read Blocks").asLong();
        return new Measured(calls / CALLS, buffers, nanos.get(CALLS / 2),
                plan.contains("\"Seq Scan\""));
    }

    // --------------------------------------------------------------- set-up

    /**
     * {@code rows} gate events for the referenced terminal and ten times as
     * many for the bystander, in one statement each, so the table the probe
     * reads is ten times larger than the part of it that matters.
     */
    private void fill(int rows) {
        insertGateEvents(referenced, rows);
        insertGateEvents(bystander, rows * NOISE_PER_ROW);
        jdbc.sql("ANALYZE gate_event").update();
    }

    private void insertGateEvents(UUID terminalId, int rows) {
        jdbc.sql("""
                        INSERT INTO gate_event (id, tenant_id, trip_id, terminal_id, event_type,
                                                occurred_at, created_at)
                        SELECT gen_random_uuid(), :tenant, :trip, :terminal, 'GATE_IN',
                               now() - make_interval(secs => g), now()
                          FROM generate_series(1, :rows) g
                        """)
                .param("tenant", scaffold.tenantId()).param("trip", tripId)
                .param("terminal", terminalId).param("rows", rows).update();
    }

    private UUID register(String code, double lat) {
        return inTenant(() -> terminals.register(new RegisterTerminalCommand.PointRadius(
                scaffold.orgUnitId(), code + "-" + tripId, code, Terminal.FunctionalCategory.CUSTOMER_SITE,
                new LatLon(lat, 10.0), 300, 1, null, null, 30, List.of())));
    }

    /** Scope first, transaction second, signed in -- the request path. */
    private <T> T inTenant(Supplier<T> action) {
        Set<SimpleGrantedAuthority> authorities = List.of("TERMINAL_CREATE").stream()
                .map(SimpleGrantedAuthority::new).collect(Collectors.toSet());
        SecurityContextHolder.getContext().setAuthentication(
                new TestingAuthenticationToken("terminal-perf", null, authorities));
        return TenantContext.callWith(scaffold.tenantId(), scaffold.orgPath(), action);
    }
}
