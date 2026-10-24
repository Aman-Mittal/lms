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
package com.lms.web;

import java.math.BigDecimal;
import java.util.ArrayList;
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
import com.lms.shared.error.BusinessRuleViolationException;
import com.lms.shared.geo.LatLon;
import com.lms.shared.tenant.TenantContext;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The order lifecycle against a real Postgres, where the Cucumber suite's
 * in-process steps cannot see: that cancelling an order is one transaction
 * across two modules, and that a failure part-way leaves nothing half-done.
 *
 * <p>The failure is injected in the database itself, by a trigger that raises
 * when a load named {@code FAIL-...} is cancelled. That is the last write of
 * the cascade, after the consignments have been cancelled and unlinked, so
 * the rollback has real work to undo -- and the assertions read the rows back
 * as the superuser, not through the services whose behaviour is in question.
 */
@SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OrderLifecycleIntegrationTest {

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

    private UUID tenantId;
    private String orgPath;
    private UUID orgUnitId;
    private UUID customer;
    private UUID origin;
    private UUID destination;
    private final List<UUID> consignees = new ArrayList<>();
    private final List<UUID> lorries = new ArrayList<>();
    private String salt;

    @BeforeAll
    void failureInjection() {
        jdbc.sql("""
                CREATE OR REPLACE FUNCTION lifecycle_test_fail() RETURNS trigger AS $$
                BEGIN
                    IF NEW.status = 'CANCELLED' AND NEW.load_no LIKE 'FAIL-%' THEN
                        RAISE EXCEPTION 'injected failure cancelling load %', NEW.load_no;
                    END IF;
                    RETURN NEW;
                END $$ LANGUAGE plpgsql
                """).update();
        jdbc.sql("""
                CREATE TRIGGER lifecycle_test_fail BEFORE UPDATE ON load_unit
                FOR EACH ROW EXECUTE FUNCTION lifecycle_test_fail()
                """).update();
    }

    @AfterAll
    void removeFailureInjection() {
        jdbc.sql("DROP TRIGGER IF EXISTS lifecycle_test_fail ON load_unit").update();
        jdbc.sql("DROP FUNCTION IF EXISTS lifecycle_test_fail()").update();
    }

    @BeforeEach
    void aTenant() {
        salt = UUID.randomUUID().toString().substring(0, 8);
        orgPath = "/life-" + salt + "/";
        tenantId = fixtures.createTenant("life-" + salt, "Lifecycle " + salt);
        orgUnitId = fixtures.createOrgUnit(tenantId, orgPath, "HQ", "HQ", null);
        consignees.clear();
        lorries.clear();
        inTenant(() -> {
            customer = fleet.registerPartner(orgUnitId, "C-" + salt, "Customer",
                    BusinessPartner.PartnerType.CUSTOMER, "T-C-" + salt);
            for (int i = 0; i < 3; i++) {
                consignees.add(fleet.registerPartner(orgUnitId, "K" + i + "-" + salt, "Consignee " + i,
                        BusinessPartner.PartnerType.CUSTOMER, "T-K" + i + "-" + salt));
            }
            origin = depot("O-" + salt, 19.1, 72.9);
            destination = depot("D-" + salt, 19.3, 73.1);
            for (int i = 0; i < 2; i++) {
                lorries.add(fleet.registerVehicle(orgUnitId, null, "LF" + i + "-" + salt, "TRUCK",
                        "RIGID", "2-AXLE", new BigDecimal("26000"), new BigDecimal("6000"),
                        new BigDecimal("60"), false, false));
            }
            return null;
        });
    }

    @AfterEach
    void signOut() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("cancelling a planned order cancels its consignments and loads in the database")
    void cancellationCascades() {
        UUID orderId = plannedOrder("SO-CASCADE", 3);
        List<UUID> consignments = consignmentsOf(orderId);
        UUID firstLoad = loadWith("LOAD-A-" + salt, lorries.get(0), consignments.subList(0, 2), true);
        UUID emptied = loadWith("LOAD-B-" + salt, lorries.get(1), consignments.subList(2, 3), false);

        inTenant(() -> {
            orders.cancel(orderId, "customer withdrew the indent");
            return null;
        });

        assertThat(statusOfOrder(orderId)).isEqualTo("CANCELLED");
        assertThat(consignmentStatuses(orderId)).containsOnly("CANCELLED");
        assertThat(linksOfOrder(orderId)).as("no consignment is left on any load").isZero();
        assertThat(loadStatus(firstLoad)).as("an emptied load is cancelled").isEqualTo("CANCELLED");
        assertThat(loadStatus(emptied)).isEqualTo("CANCELLED");
        assertThat(loadWeight(firstLoad)).isEqualByComparingTo("0");
        assertThat(jdbc.sql("""
                        SELECT after_state ->> 'reason' FROM audit_log
                         WHERE tenant_id = :t AND action = 'ORDER_CANCELLED' AND resource_id = :o
                        """).param("t", tenantId).param("o", orderId)
                .query(String.class).single()).isEqualTo("customer withdrew the indent");
    }

    @Test
    @DisplayName("a failure while cancelling a load rolls back every consignment and the order")
    void failureRollsBackEverything() {
        UUID orderId = plannedOrder("SO-ROLLBACK", 3);
        List<UUID> consignments = consignmentsOf(orderId);
        // The first load is wound up before the poisoned one fails; both are
        // emptied by the cancellation, so both writes are attempted.
        UUID first = loadWith("LOAD-OK-" + salt, lorries.get(0), consignments.subList(0, 2), false);
        UUID poisoned = loadWith("FAIL-" + salt, lorries.get(1), consignments.subList(2, 3), false);

        assertThatThrownBy(() -> inTenant(() -> {
            orders.cancel(orderId, "will not stick");
            return null;
        })).hasMessageContaining("injected failure");

        assertThat(statusOfOrder(orderId)).as("the order is untouched").isEqualTo("FULLY_PLANNED");
        assertThat(consignmentStatuses(orderId)).containsOnly("ASSIGNED_TO_LOAD");
        assertThat(linksOfOrder(orderId)).as("every load link is still there").isEqualTo(3);
        assertThat(loadStatus(first)).isEqualTo("DRAFT");
        assertThat(loadStatus(poisoned)).isEqualTo("DRAFT");
        assertThat(loadWeight(first)).isEqualByComparingTo("2000");
        assertThat(jdbc.sql("""
                        SELECT count(*) FROM audit_log
                         WHERE tenant_id = :t AND action = 'ORDER_CANCELLED' AND resource_id = :o
                        """).param("t", tenantId).param("o", orderId)
                .query(Integer.class).single()).as("nothing was audited as cancelled").isZero();
    }

    @Test
    @DisplayName("a dispatched load refuses the cancellation and nothing is touched")
    void dispatchedLoadRefuses() {
        UUID orderId = plannedOrder("SO-LEFT", 2);
        List<UUID> consignments = consignmentsOf(orderId);
        UUID leaving = loadWith("LOAD-L-" + salt, lorries.get(0), consignments.subList(0, 1), true);
        UUID waiting = loadWith("LOAD-W-" + salt, lorries.get(1), consignments.subList(1, 2), true);
        inTenant(() -> {
            loadPort.recordAwarded(leaving, customer);
            loadPort.recordDispatched(leaving);
            return null;
        });

        assertThatThrownBy(() -> inTenant(() -> {
            orders.cancel(orderId, null);
            return null;
        })).isInstanceOfSatisfying(BusinessRuleViolationException.class,
                e -> assertThat(e.code()).isEqualTo("order-work-dispatched"));

        assertThat(statusOfOrder(orderId)).isEqualTo("FULLY_PLANNED");
        assertThat(consignmentStatuses(orderId)).containsOnly("ASSIGNED_TO_LOAD");
        assertThat(linksOfOrder(orderId)).isEqualTo(2);
        assertThat(loadStatus(waiting)).isEqualTo("PLANNED");
    }

    @Test
    @DisplayName("completing a full-truckload trip delivers the consignments and fulfils the order")
    void completionFulfilsTheOrder() {
        UUID orderId = plannedOrder("SO-DELIVER", 2);
        UUID load = loadWith("LOAD-D-" + salt, lorries.get(0), consignmentsOf(orderId), true);
        inTenant(() -> {
            loadPort.recordAwarded(load, customer);
            loadPort.recordDispatched(load);
            loadPort.recordTripCompleted(load);
            // A redelivered completion is a no-op.
            loadPort.recordTripCompleted(load);
            return null;
        });

        assertThat(consignmentStatuses(orderId)).containsOnly("DELIVERED");
        assertThat(loadStatus(load)).isEqualTo("COMPLETED");
        assertThat(statusOfOrder(orderId)).isEqualTo("FULFILLED");
        assertThat(jdbc.sql("SELECT version FROM consignment WHERE order_id = :o LIMIT 1")
                .param("o", orderId).query(Long.class).single())
                .as("delivered in one write, and not rewritten by the repeat").isEqualTo(2L);
    }

    // ------------------------------------------------------------- building

    private UUID plannedOrder(String orderNo, int consigneeCount) {
        return inTenant(() -> {
            UUID orderId = orders.raiseOrder(orgUnitId, customer, orderNo + "-" + salt, origin,
                    null, null, SalesOrder.OrderSource.MANUAL);
            for (int i = 0; i < consigneeCount; i++) {
                orders.addLine(orderId, i + 1, "MAT-" + i, "General cargo", MaterialClass.GENERAL,
                        null, BigDecimal.ONE, "EA", new BigDecimal("1000"), null, null, null, null,
                        consignees.get(i), destination);
            }
            orders.validate(orderId);
            planning.generateConsignments(orderId);
            return orderId;
        });
    }

    private UUID loadWith(String loadNo, UUID lorry, List<UUID> consignmentIds, boolean planned) {
        return inTenant(() -> {
            UUID loadId = planning.openLoad(orgUnitId, loadNo, origin, lorry);
            consignmentIds.forEach(id -> planning.assignConsignment(loadId, id));
            if (planned) {
                planning.planLoad(loadId);
            }
            return loadId;
        });
    }

    private UUID depot(String code, double lat, double lon) {
        return terminals.register(new RegisterTerminalCommand.PointRadius(orgUnitId, code, code,
                Terminal.FunctionalCategory.CUSTOMER_SITE, new LatLon(lat, lon), 500, 2, null,
                null, 45, List.of()));
    }

    // -------------------------------------------------- reading it back raw

    private List<UUID> consignmentsOf(UUID orderId) {
        return jdbc.sql("SELECT id FROM consignment WHERE order_id = :o ORDER BY tracking_ref")
                .param("o", orderId).query(UUID.class).list();
    }

    private List<String> consignmentStatuses(UUID orderId) {
        return jdbc.sql("SELECT status FROM consignment WHERE order_id = :o")
                .param("o", orderId).query(String.class).list();
    }

    private int linksOfOrder(UUID orderId) {
        return jdbc.sql("""
                        SELECT count(*) FROM load_consignment
                         WHERE consignment_id IN (SELECT id FROM consignment WHERE order_id = :o)
                        """).param("o", orderId).query(Integer.class).single();
    }

    private String statusOfOrder(UUID orderId) {
        return jdbc.sql("SELECT status FROM sales_order WHERE id = :o")
                .param("o", orderId).query(String.class).single();
    }

    private String loadStatus(UUID loadId) {
        return jdbc.sql("SELECT status FROM load_unit WHERE id = :l")
                .param("l", loadId).query(String.class).single();
    }

    private BigDecimal loadWeight(UUID loadId) {
        return jdbc.sql("SELECT planned_weight_kg FROM load_unit WHERE id = :l")
                .param("l", loadId).query(BigDecimal.class).single();
    }

    /** Scope first, transaction second (the services open their own), signed in. */
    private <T> T inTenant(Supplier<T> action) {
        Set<SimpleGrantedAuthority> authorities = AUTHORITIES.stream()
                .map(SimpleGrantedAuthority::new).collect(Collectors.toSet());
        SecurityContextHolder.getContext().setAuthentication(
                new TestingAuthenticationToken("life-test", null, authorities));
        return TenantContext.callWith(tenantId, orgPath, action);
    }
}
