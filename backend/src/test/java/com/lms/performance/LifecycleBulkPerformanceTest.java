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
 * Completing a load and cancelling an order must cost in proportion to the
 * consignments they touch.
 *
 * <p>A bulk operation that issues a query per consignment on top of the work
 * per consignment, or that re-reads the load once for each of them, is fine at
 * ten consignments and a stall at four hundred. So this runs each operation
 * over 40 and over 400 consignments and compares them, never against a
 * wall-clock threshold, which varies by machine:
 * <ul>
 *   <li><b>statements</b>, counted by the database itself
 *       ({@code pg_stat_statements}): the extra statements per extra
 *       consignment must be about one -- its own update -- not a lookup per
 *       consignment on top, and not growing with the size of the load;</li>
 *   <li><b>time</b>: the cost per consignment at 400 must stay within a small
 *       factor of the cost per consignment at 40. A quadratic step (a scan of
 *       the load for each consignment) multiplies it by ten.</li>
 * </ul>
 *
 * <p>Tagged {@code performance}: excluded from the default build, run with
 * {@code ./mvnw verify -Pperformance}.
 */
@Tag("performance")
@SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LifecycleBulkPerformanceTest {

    private static final Logger log = LoggerFactory.getLogger(LifecycleBulkPerformanceTest.class);

    private static final int SMALL = 40;
    private static final int LARGE = 400;

    /** Cost per consignment may grow this much between the two sizes; linear work stays near 1. */
    private static final double MAX_PER_CONSIGNMENT_GROWTH = 4.0;

    /** Extra statements per extra consignment: its own update, with a little slack. */
    private static final double MAX_MARGINAL_STATEMENTS = 1.5;

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

    /** An order of {@code size} consignments, all on one load that has not left. */
    private record Built(UUID tenantId, String orgPath, UUID orderId, UUID loadId, UUID partnerId,
                         int size) {
    }

    private record Measured(long statements, long nanos) {
        double nanosPerConsignment(int size) {
            return (double) nanos / size;
        }
    }

    @BeforeAll
    void statementCounting() {
        jdbc.sql("CREATE EXTENSION IF NOT EXISTS pg_stat_statements").update();
        // Warm the code paths and the plans once, so that the first measured
        // size does not pay for class loading the second never sees.
        Built warm = build(10);
        start(warm);
        measure(warm, () -> loadPort.recordTripCompleted(warm.loadId()));
        Built warmCancel = build(10);
        measure(warmCancel, () -> orders.cancel(warmCancel.orderId(), null));
    }

    @AfterEach
    void signOut() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("completing a load costs in proportion to its consignments")
    void completingALoadIsLinear() {
        Built small = build(SMALL);
        Built large = build(LARGE);
        start(small);
        start(large);

        Measured atSmall = measure(small, () -> loadPort.recordTripCompleted(small.loadId()));
        Measured atLarge = measure(large, () -> loadPort.recordTripCompleted(large.loadId()));

        assertThat(statuses(small)).containsOnly("DELIVERED").hasSize(SMALL);
        assertThat(statuses(large)).containsOnly("DELIVERED").hasSize(LARGE);
        assertThat(orderStatus(large)).isEqualTo("FULFILLED");
        report("complete load", atSmall, atLarge);
        assertLinear(atSmall, atLarge);
    }

    @Test
    @DisplayName("cancelling an order costs in proportion to its consignments")
    void cancellingAnOrderIsLinear() {
        Built small = build(SMALL);
        Built large = build(LARGE);

        Measured atSmall = measure(small, () -> orders.cancel(small.orderId(), "perf"));
        Measured atLarge = measure(large, () -> orders.cancel(large.orderId(), "perf"));

        assertThat(statuses(small)).containsOnly("CANCELLED").hasSize(SMALL);
        assertThat(statuses(large)).containsOnly("CANCELLED").hasSize(LARGE);
        assertThat(loadStatus(large)).isEqualTo("CANCELLED");
        report("cancel order", atSmall, atLarge);
        assertLinear(atSmall, atLarge);
    }

    private void assertLinear(Measured atSmall, Measured atLarge) {
        double marginal = (double) (atLarge.statements() - atSmall.statements()) / (LARGE - SMALL);
        assertThat(marginal)
                .as("extra statements per extra consignment (%d at %d, %d at %d)",
                        atSmall.statements(), SMALL, atLarge.statements(), LARGE)
                .isLessThanOrEqualTo(MAX_MARGINAL_STATEMENTS);

        double growth = atLarge.nanosPerConsignment(LARGE) / atSmall.nanosPerConsignment(SMALL);
        assertThat(growth)
                .as("cost per consignment at %d relative to %d", LARGE, SMALL)
                .isLessThanOrEqualTo(MAX_PER_CONSIGNMENT_GROWTH);
    }

    private void report(String what, Measured atSmall, Measured atLarge) {
        log.info("{}: {} consignments {} statements {} ms ({} us each); {} consignments "
                        + "{} statements {} ms ({} us each); cost per consignment x{}",
                what, SMALL, atSmall.statements(), atSmall.nanos() / 1_000_000,
                Math.round(atSmall.nanosPerConsignment(SMALL) / 1000), LARGE, atLarge.statements(),
                atLarge.nanos() / 1_000_000, Math.round(atLarge.nanosPerConsignment(LARGE) / 1000),
                String.format("%.2f", atLarge.nanosPerConsignment(LARGE)
                        / atSmall.nanosPerConsignment(SMALL)));
    }

    // ------------------------------------------------------------ measuring

    /** Runs the operation in the tenant and counts the statements the database saw for it. */
    private Measured measure(Built built, Runnable operation) {
        jdbc.sql("SELECT pg_stat_statements_reset()").query().singleRow();
        long started = System.nanoTime();
        inTenant(built, () -> {
            operation.run();
            return null;
        });
        long nanos = System.nanoTime() - started;
        long statements = jdbc.sql("""
                        SELECT coalesce(sum(calls), 0)::bigint FROM pg_stat_statements
                         WHERE dbid = (SELECT oid FROM pg_database WHERE datname = current_database())
                           AND query NOT LIKE '%pg_stat_statements%'
                        """).query(Long.class).single();
        return new Measured(statements, nanos);
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

    // --------------------------------------------------------- reading back

    private List<String> statuses(Built built) {
        return jdbc.sql("SELECT status FROM consignment WHERE order_id = :o")
                .param("o", built.orderId()).query(String.class).list();
    }

    private String orderStatus(Built built) {
        return jdbc.sql("SELECT status FROM sales_order WHERE id = :o")
                .param("o", built.orderId()).query(String.class).single();
    }

    private String loadStatus(Built built) {
        return jdbc.sql("SELECT status FROM load_unit WHERE id = :l")
                .param("l", built.loadId()).query(String.class).single();
    }

    /** Scope first, transaction second, signed in -- the request path. */
    private <T> T inTenant(Built built, Supplier<T> action) {
        Set<SimpleGrantedAuthority> authorities = AUTHORITIES.stream()
                .map(SimpleGrantedAuthority::new).collect(Collectors.toSet());
        SecurityContextHolder.getContext().setAuthentication(
                new TestingAuthenticationToken("bulk-perf", null, authorities));
        return TenantContext.callWith(built.tenantId(), built.orgPath(), action);
    }
}
