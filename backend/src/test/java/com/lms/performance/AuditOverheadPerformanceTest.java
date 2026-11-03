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

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import com.lms.acceptance.TestFixtures;
import com.lms.masterdata.command.FleetCommandService;
import com.lms.masterdata.command.RegisterTerminalCommand;
import com.lms.masterdata.command.TerminalCommandService;
import com.lms.masterdata.command.domain.BusinessPartner;
import com.lms.masterdata.command.domain.Terminal;
import com.lms.order.api.MaterialClass;
import com.lms.order.command.OrderCommandService;
import com.lms.order.command.domain.SalesOrder;
import com.lms.planning.api.LoadLifecyclePort;
import com.lms.planning.command.PlanningCommandService;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Auditing a transition must cost about one statement, and a bulk transition
 * must not cost one per row.
 *
 * <p>Counted by the database itself ({@code pg_stat_statements}), as
 * {@link LifecycleBulkPerformanceTest} does, and never timed: a lifecycle
 * transition writes one {@code audit_log} row, so
 * <ul>
 *   <li>one transition (awarding a load) issues exactly one audit insert, for
 *       one row;</li>
 *   <li>a cascade over {@code n} consignments (cancelling an order, completing
 *       a load) writes {@code n + 2} rows -- one per consignment, plus the load
 *       and the order -- in a number of insert statements that does not grow
 *       with {@code n}: the rows of one kind go in together;</li>
 *   <li>so the statements per extra consignment stay what they were before
 *       auditing existed, about one (its own update).</li>
 * </ul>
 * A per-row audit insert would show here as an audit statement count that
 * doubles when the size does.
 *
 * <p>Tagged {@code performance}: excluded from the default build, run with
 * {@code ./mvnw verify -Pperformance}.
 */
@Tag("performance")
@SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AuditOverheadPerformanceTest {

    private static final Logger log = LoggerFactory.getLogger(AuditOverheadPerformanceTest.class);

    private static final int SMALL = 40;
    private static final int LARGE = 400;

    /** Extra statements per extra consignment: its own update, with a little slack. */
    private static final double MAX_MARGINAL_STATEMENTS = 1.5;

    /** Audit insert statements one cascade may issue: a handful, never one per row. */
    private static final int MAX_AUDIT_STATEMENTS_PER_CASCADE = 5;

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

    private static final List<String> AUTHORITIES = List.of(
            "PARTNER_CREATE", "PARTNER_UPDATE", "VEHICLE_CREATE", "TERMINAL_CREATE",
            "ORDER_CREATE", "ORDER_UPDATE", "LOAD_CREATE");

    @Autowired
    private TestFixtures fixtures;
    @Autowired
    private FleetCommandService fleet;
    @Autowired
    private TerminalCommandService terminals;
    @Autowired
    private OrderCommandService orders;
    @Autowired
    private PlanningCommandService planning;
    @Autowired
    private LoadLifecyclePort loadPort;
    @Autowired
    private JdbcClient jdbc;

    private record Built(UUID tenantId, String orgPath, UUID orderId, UUID loadId, UUID partnerId,
                         int size) {
    }

    private record Measured(long statements, long auditStatements, long auditRows) {
    }

    @BeforeAll
    void statementCounting() {
        jdbc.sql("CREATE EXTENSION IF NOT EXISTS pg_stat_statements").update();
        // Warm the code paths once so plan and class loading are not counted.
        Built warm = build(10);
        measure(warm, () -> loadPort.recordAwarded(warm.loadId(), warm.partnerId()));
        Built warmCancel = build(10);
        measure(warmCancel, () -> orders.cancel(warmCancel.orderId(), null));
    }

    @AfterEach
    void signOut() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("one transition costs one audit statement and one row")
    void oneTransitionCostsOneStatement() {
        Built built = build(SMALL);

        Measured award = measure(built, () -> loadPort.recordAwarded(built.loadId(), built.partnerId()));

        log.info("award load: {} statements of which {} audit ({} rows)", award.statements(),
                award.auditStatements(), award.auditRows());
        assertThat(award.auditStatements()).as("audit inserts for one award").isEqualTo(1);
        assertThat(award.auditRows()).as("audit rows for one award").isEqualTo(1);
    }

    @Test
    @DisplayName("cancelling an order audits n + 2 rows in a constant number of statements")
    void cancellingAnOrderAuditsInBulk() {
        Built small = build(SMALL);
        Built large = build(LARGE);

        Measured atSmall = measure(small, () -> orders.cancel(small.orderId(), "perf"));
        Measured atLarge = measure(large, () -> orders.cancel(large.orderId(), "perf"));

        report("cancel order", atSmall, atLarge);
        assertThat(atSmall.auditRows()).isEqualTo(SMALL + 2);
        assertThat(atLarge.auditRows()).isEqualTo(LARGE + 2);
        assertBulk(atSmall, atLarge);
    }

    @Test
    @DisplayName("completing a load audits n + 2 rows in a constant number of statements")
    void completingALoadAuditsInBulk() {
        Built small = build(SMALL);
        Built large = build(LARGE);
        start(small);
        start(large);

        Measured atSmall = measure(small, () -> loadPort.recordTripCompleted(small.loadId()));
        Measured atLarge = measure(large, () -> loadPort.recordTripCompleted(large.loadId()));

        report("complete load", atSmall, atLarge);
        // Each consignment delivered, the load completed, the order fulfilled.
        assertThat(atSmall.auditRows()).isEqualTo(SMALL + 2);
        assertThat(atLarge.auditRows()).isEqualTo(LARGE + 2);
        assertBulk(atSmall, atLarge);
    }

    private void assertBulk(Measured atSmall, Measured atLarge) {
        assertThat(atLarge.auditStatements())
                .as("audit statements at %d must not exceed those at %d", LARGE, SMALL)
                .isEqualTo(atSmall.auditStatements());
        assertThat(atLarge.auditStatements())
                .as("audit statements for one cascade")
                .isLessThanOrEqualTo(MAX_AUDIT_STATEMENTS_PER_CASCADE);
        double marginal = (double) (atLarge.statements() - atSmall.statements()) / (LARGE - SMALL);
        assertThat(marginal)
                .as("extra statements per extra consignment (%d at %d, %d at %d)",
                        atSmall.statements(), SMALL, atLarge.statements(), LARGE)
                .isLessThanOrEqualTo(MAX_MARGINAL_STATEMENTS);
    }

    private void report(String what, Measured atSmall, Measured atLarge) {
        log.info("{}: {} consignments {} statements ({} audit, {} rows); {} consignments {} "
                        + "statements ({} audit, {} rows)", what, SMALL, atSmall.statements(),
                atSmall.auditStatements(), atSmall.auditRows(), LARGE, atLarge.statements(),
                atLarge.auditStatements(), atLarge.auditRows());
    }

    // ------------------------------------------------------------ measuring

    /**
     * Runs the operation in the tenant and counts the statements the database
     * saw for it.
     *
     * <p>Foreign-key probes ({@code SELECT 1 FROM ONLY ... FOR KEY SHARE}) are
     * not counted. With {@code track=all} the database reports the lookup it
     * makes itself for every row inserted into a table with a foreign key, so
     * each audit row shows up once more, as its tenant check. That is the
     * database's own work on an indexed primary key and not a statement the
     * application sent; it is exactly one per audit row, which the row count
     * already bounds.
     */
    private Measured measure(Built built, Runnable operation) {
        jdbc.sql("SELECT pg_stat_statements_reset()").query().singleRow();
        inTenant(built, () -> {
            operation.run();
            return null;
        });
        long statements = jdbc.sql("""
                        SELECT coalesce(sum(calls), 0)::bigint FROM pg_stat_statements
                         WHERE dbid = (SELECT oid FROM pg_database WHERE datname = current_database())
                           AND query NOT LIKE '%pg_stat_statements%'
                           AND query NOT LIKE '%FROM ONLY%FOR KEY SHARE%'
                        """).query(Long.class).single();
        var audit = jdbc.sql("""
                        SELECT coalesce(sum(calls), 0)::bigint AS calls,
                               coalesce(sum(rows), 0)::bigint AS rows
                          FROM pg_stat_statements
                         WHERE dbid = (SELECT oid FROM pg_database WHERE datname = current_database())
                           AND query ILIKE '%INSERT INTO audit_log%'
                        """).query().singleRow();
        // What the busiest statements were, so a ratio that grows can be read
        // off the log without re-running under a debugger.
        jdbc.sql("""
                        SELECT calls, left(regexp_replace(query, '\\s+', ' ', 'g'), 110) AS query
                          FROM pg_stat_statements
                         WHERE dbid = (SELECT oid FROM pg_database WHERE datname = current_database())
                           AND query NOT LIKE '%pg_stat_statements%'
                         ORDER BY calls DESC LIMIT 5
                        """).query().listOfRows()
                .forEach(row -> log.info("  {} x {}", row.get("calls"), row.get("query")));
        return new Measured(statements, ((Number) audit.get("calls")).longValue(),
                ((Number) audit.get("rows")).longValue());
    }

    /** Awards and dispatches the load, so that completing it is the next thing that happens. */
    private void start(Built built) {
        inTenant(built, () -> {
            loadPort.recordAwarded(built.loadId(), built.partnerId());
            loadPort.recordDispatched(built.loadId());
            return null;
        });
    }

    // --------------------------------------------------------------- set-up

    private Built build(int size) {
        String salt = UUID.randomUUID().toString().substring(0, 8);
        String orgPath = "/bulk-" + salt + "/";
        UUID tenantId = fixtures.createTenant("bulk-" + salt, "Bulk " + salt);
        UUID orgUnitId = fixtures.createOrgUnit(tenantId, orgPath, "HQ", "HQ", null);
        Built none = new Built(tenantId, orgPath, null, null, null, size);

        UUID[] ids = new UUID[3];
        inTenant(none, () -> {
            UUID customer = fleet.registerPartner(orgUnitId, "C-" + salt, "Customer",
                    BusinessPartner.PartnerType.CUSTOMER, "T-C-" + salt);
            UUID origin = depot(orgUnitId, "O-" + salt, 19.1, 72.9);
            UUID destination = depot(orgUnitId, "D-" + salt, 19.3, 73.1);
            UUID lorry = fleet.registerVehicle(orgUnitId, null, "BK-" + salt, "TRUCK", "RIGID",
                    "2-AXLE", new BigDecimal("5006000"), new BigDecimal("6000"),
                    new BigDecimal("100000"), false, false);

            UUID orderId = orders.raiseOrder(orgUnitId, customer, "SO-" + salt, origin, null, null,
                    SalesOrder.OrderSource.MANUAL);
            // One consignee per line: consignments are cut per consignee and
            // destination, so this is what makes `size` consignments.
            for (int i = 0; i < size; i++) {
                UUID consignee = fleet.registerPartner(orgUnitId, "K" + i + "-" + salt,
                        "Consignee " + i, BusinessPartner.PartnerType.CUSTOMER,
                        "T-K" + i + "-" + salt);
                orders.addLine(orderId, i + 1, "MAT-" + i, "General cargo", MaterialClass.GENERAL,
                        null, BigDecimal.ONE, "EA", BigDecimal.TEN, null, null, null, null,
                        consignee, destination);
            }
            orders.validate(orderId);
            List<UUID> consignments = planning.generateConsignments(orderId);
            assertThat(consignments).hasSize(size);

            UUID loadId = planning.openLoad(orgUnitId, "LD-" + salt, origin, lorry);
            consignments.forEach(id -> planning.assignConsignment(loadId, id));
            planning.planLoad(loadId);
            ids[0] = orderId;
            ids[1] = loadId;
            ids[2] = customer;
            return null;
        });
        return new Built(tenantId, orgPath, ids[0], ids[1], ids[2], size);
    }

    private UUID depot(UUID orgUnitId, String code, double lat, double lon) {
        return terminals.register(new RegisterTerminalCommand.PointRadius(orgUnitId, code, code,
                Terminal.FunctionalCategory.CUSTOMER_SITE, new LatLon(lat, lon), 500, 2, null,
                null, 45, List.of()));
    }

    /** Scope first, transaction second, signed in -- the request path. */
    private <T> T inTenant(Built built, Supplier<T> action) {
        Set<SimpleGrantedAuthority> authorities = AUTHORITIES.stream()
                .map(SimpleGrantedAuthority::new).collect(Collectors.toSet());
        SecurityContextHolder.getContext().setAuthentication(
                new TestingAuthenticationToken("audit-perf", null, authorities));
        return TenantContext.callWith(built.tenantId(), built.orgPath(), action);
    }
}
