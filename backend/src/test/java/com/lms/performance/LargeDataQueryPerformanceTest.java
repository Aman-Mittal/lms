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
import com.lms.controltower.query.ControlTowerQueryService;
import com.lms.controltower.query.ExceptionView;
import com.lms.execution.command.TripCommandService;
import com.lms.finance.command.SettlementHolds;
import com.lms.masterdata.command.FleetCommandService;
import com.lms.masterdata.command.TerminalCommandService;
import com.lms.shared.query.Slice;
import com.lms.shared.tenant.TenantContext;
import com.lms.telematics.api.VehicleFixPort;
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
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The hot queries, timed against a database with real volume in it.
 *
 * <p>{@code QueryPlanTest} proves an index <em>exists</em> by asking the planner
 * on empty tables. This proves the queries stay fast once there is something to
 * be slow about: hundreds of thousands of rows, split across two tenants so
 * that row-level security is doing real filtering, statistics gathered, and
 * every query run through the real services, under RLS, as the application
 * runs it.
 *
 * <p>Absolute budgets are deliberately loose -- CI runners and laptops differ
 * by a factor of several. The assertion that carries the weight is relative:
 * a page deep into the board must cost about what the first page costs. That
 * is the property keyset pagination exists to provide, it is independent of
 * the machine, and an accidental OFFSET or missing index fails it by orders of
 * magnitude rather than by a noisy margin.
 *
 * <p>Tagged {@code performance}: excluded from the default build, run with
 * {@code ./mvnw verify -Pperformance} and by the Performance workflow.
 */
@Tag("performance")
@SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LargeDataQueryPerformanceTest {

    private static final Logger log = LoggerFactory.getLogger(LargeDataQueryPerformanceTest.class);

    /** Rows per tenant. Two tenants, so twice this in each table. */
    private static final int TICKETS = 150_000;
    private static final int PINGS = 300_000;
    private static final int HOLDS = 100_000;

    private static final int WARMUP = 5;
    private static final int RUNS = 30;

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17.7-alpine")
            .withDatabaseName("lms").withUsername("lms").withPassword("lms")
            .withCommand("postgres", "-c", "shared_buffers=128MB", "-c", "work_mem=8MB");

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
    private JdbcClient jdbc;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private ControlTowerQueryService controlTower;
    @Autowired
    private VehicleFixPort fixes;
    @Autowired
    private SettlementHolds holds;

    private TripScaffold.Built measured;

    @BeforeAll
    void loadVolume() {
        measured = TripScaffold.build(fixtures, spine, fleet, terminals, trips, 0.0);
        TripScaffold.Built noise = TripScaffold.build(fixtures, spine, fleet, terminals, trips, 1.0);

        long started = System.nanoTime();
        for (TripScaffold.Built tenant : List.of(measured, noise)) {
            bulkLoad(tenant);
        }
        jdbc.sql("ANALYZE").update();
        log.info("Bulk-loaded {} tickets, {} pings and {} holds per tenant in {} ms",
                TICKETS, PINGS, HOLDS, (System.nanoTime() - started) / 1_000_000);
    }

    @AfterEach
    void signOut() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("the exception board's first page is fast at volume")
    void boardFirstPage() {
        Timing first = time(() -> controlTower.list(null, null, null, 50));
        log.info("board first page: {}", first);
        assertThat(first.p95Millis()).as("first page p95").isLessThan(250);
    }

    @Test
    @DisplayName("a page deep into the board costs about what the first page costs")
    void boardDeepPageIsKeyset() {
        String deepCursor = cursorAtRow(TICKETS - 100);
        Timing first = time(() -> controlTower.list(null, null, null, 50));
        Timing deep = time(() -> controlTower.list(null, null, deepCursor, 50));
        log.info("board first page: {} / deep page: {}", first, deep);

        // OFFSET would make the deep page ~3000x the work of the first. Keyset
        // makes them the same seek; the floor absorbs timer noise at tiny values.
        assertThat(deep.p95Millis())
                .as("deep page p95 %s against first page p95 %s", deep, first)
                .isLessThanOrEqualTo(Math.max(first.p95Millis() * 5, 25));
    }

    @Test
    @DisplayName("the board narrowed to one status is fast at volume")
    void boardByStatus() {
        Timing timing = time(() -> controlTower.list("UNDER_INVESTIGATION", null, null, 50));
        log.info("board by status: {}", timing);
        assertThat(timing.p95Millis()).isLessThan(250);
    }

    @Test
    @DisplayName("the latest fix for a vehicle is found quickly among hundreds of thousands of pings")
    void latestFix() {
        Timing timing = time(() -> fixes.latestFix(measured.vehicleId()));
        log.info("latest vehicle fix: {}", timing);
        assertThat(timing.p95Millis()).isLessThan(50);
    }

    @Test
    @DisplayName("the settlement hold check is fast with a hundred thousand holds")
    void holdCheck() {
        Timing timing = time(() -> holds.holdingTickets(measured.tripId()));
        log.info("settlement hold check: {}", timing);
        assertThat(timing.p95Millis()).isLessThan(50);
    }

    @Test
    @DisplayName("the board summary stays inside a second at volume")
    void summary() {
        // Counts every open ticket, so it is linear in open tickets by design;
        // the budget says how far that can go before it needs a rollup table.
        Timing timing = time(() -> controlTower.summary());
        log.info("board summary over {} open tickets: {}", TICKETS * 4 / 5, timing);
        assertThat(timing.p95Millis()).isLessThan(1_000);
    }

    // ------------------------------------------------------------- loading

    /**
     * Writes the volume with set-based SQL as the container's superuser.
     *
     * <p>Bypassing the services and row-level security here is deliberate: the
     * subject is how the read side behaves on a large table, and generating
     * 150 000 tickets through the command path would take minutes to test
     * nothing new. The reads below go back through the services, under RLS.
     */
    private void bulkLoad(TripScaffold.Built tenant) {
        jdbc.sql("""
                        INSERT INTO exception_ticket (id, tenant_id, ticket_no, trip_id, trip_no,
                               vehicle_id, category, severity, status, description, source,
                               position_source, suspends_settlement, raised_by, raised_at,
                               resolution, version)
                        SELECT gen_random_uuid(), :tenantId, 'PX-' || substr(md5(CAST(:tenantId AS text)), 1, 6) || '-' || g,
                               :tripId, 'TR', :vehicleId,
                               (ARRAY['ACCIDENT','ROUTE_DEVIATION','ETA_DELAY','DOCUMENT_MISMATCH'])[g % 4 + 1],
                               (ARRAY['P1','P2','P3','P3'])[g % 4 + 1],
                               (ARRAY['RAISED','ASSIGNED','UNDER_INVESTIGATION','RESOLVED','CLOSED'])[g % 5 + 1],
                               'generated', 'MANUAL', 'NONE', (g % 4) < 2, 'perf',
                               now() - make_interval(secs => g),
                               CASE WHEN g % 5 IN (3, 4) THEN 'done' END, 0
                          FROM generate_series(1, :count) g
                        """)
                .param("tenantId", tenant.tenantId()).param("tripId", tenant.tripId())
                .param("vehicleId", tenant.vehicleId()).param("count", TICKETS)
                .update();

        jdbc.sql("""
                        INSERT INTO gps_ping (id, tenant_id, vehicle_id, recorded_at, lat, lon,
                                              speed_kph, source)
                        SELECT gen_random_uuid(), :tenantId, :vehicleId,
                               now() - make_interval(secs => g * 30),
                               19.1 + (g % 1000) * 0.0001, 72.9 + (g % 1000) * 0.0001, 50, 'DEVICE'
                          FROM generate_series(1, :count) g
                        """)
                .param("tenantId", tenant.tenantId()).param("vehicleId", tenant.vehicleId())
                .param("count", PINGS)
                .update();

        jdbc.sql("""
                        INSERT INTO settlement_hold (tenant_id, exception_id, trip_id, ticket_no,
                                                     placed_at, released_at)
                        SELECT :tenantId, gen_random_uuid(), gen_random_uuid(), 'H-' || g,
                               now() - make_interval(secs => g),
                               CASE WHEN g % 2 = 0 THEN now() END
                          FROM generate_series(1, :count) g
                        """)
                .param("tenantId", tenant.tenantId()).param("count", HOLDS)
                .update();
    }

    private String cursorAtRow(int row) {
        return inTenant(() -> jdbc.sql("""
                        SELECT raised_at, id FROM exception_ticket
                         ORDER BY raised_at DESC, id DESC
                         LIMIT 1 OFFSET :row
                        """)
                .param("row", row)
                .query((rs, n) -> rs.getTimestamp("raised_at").toInstant() + "_"
                        + rs.getObject("id", UUID.class))
                .single());
    }

    // -------------------------------------------------------------- timing

    record Timing(long p50Millis, long p95Millis, long maxMillis) {
        @Override
        public String toString() {
            return "p50=" + p50Millis + "ms p95=" + p95Millis + "ms max=" + maxMillis + "ms";
        }
    }

    private Timing time(Supplier<?> query) {
        for (int i = 0; i < WARMUP; i++) {
            inTenant(query);
        }
        List<Long> samples = new ArrayList<>();
        for (int i = 0; i < RUNS; i++) {
            long started = System.nanoTime();
            Object result = inTenant(query);
            samples.add((System.nanoTime() - started) / 1_000_000);
            if (result instanceof Slice<?> slice) {
                assertThat(slice.items()).as("a page at volume should not be empty").isNotEmpty();
                assertThat(slice.items()).allSatisfy(item ->
                        assertThat(((ExceptionView) item).tripId()).isEqualTo(measured.tripId()));
            }
        }
        Collections.sort(samples);
        return new Timing(samples.get(samples.size() / 2),
                samples.get((int) Math.ceil(samples.size() * 0.95) - 1),
                samples.get(samples.size() - 1));
    }

    /** Scope first, transaction second, signed in to read -- exactly the request path. */
    private <T> T inTenant(Supplier<T> action) {
        Set<SimpleGrantedAuthority> authorities = Set.of("EXCEPTION_READ", "INVOICE_READ").stream()
                .map(SimpleGrantedAuthority::new).collect(Collectors.toSet());
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("perf", "n/a", authorities));
        TransactionTemplate transactions = new TransactionTemplate(transactionManager);
        return TenantContext.callWith(measured.tenantId(), measured.orgPath(),
                () -> transactions.execute(status -> action.get()));
    }
}
