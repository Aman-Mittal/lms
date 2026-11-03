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
import com.lms.shared.audit.TransitionAudit;
import com.lms.shared.config.Json;
import com.lms.shared.geo.LatLon;
import com.lms.shared.tenant.TenantContext;
import com.lms.sourcing.command.AllocationService;
import com.lms.sourcing.command.RoutingGuideCommandService;
import com.lms.sourcing.command.domain.RoutingGuide;
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
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * An audit row lives and dies with the transition it records, against a real
 * Postgres.
 *
 * <p>The audit used to be written in a transaction of its own, so a transition
 * that rolled back could still leave a row saying it had happened. Failures
 * are injected in the database itself, by triggers that raise on the last
 * write of a cascade, so the rollback has audit rows already inserted to
 * undo. Every assertion reads {@code audit_log} back as the superuser, not
 * through the services whose behaviour is in question.
 */
@SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AuditTransitionIntegrationTest {

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
            "ORDER_CREATE", "ORDER_UPDATE", "LOAD_CREATE", "LOAD_ALLOCATE",
            "ROUTING_GUIDE_MANAGE");

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
    private AllocationService allocations;
    @Autowired
    private RoutingGuideCommandService guides;
    @Autowired
    private TransitionAudit transitionAudit;
    @Autowired
    private JdbcClient jdbc;

    private UUID tenantId;
    private String orgPath;
    private UUID orgUnitId;
    private UUID customer;
    private UUID consignee;
    private UUID vendor;
    private UUID origin;
    private UUID destination;
    private UUID lorry;
    private String salt;
    /** The signed-in user's identifier, which the audit must attribute rows to. */
    private final UUID actor = UUID.randomUUID();

    @BeforeAll
    void failureInjection() {
        // A load named FAIL-... cannot be cancelled or awarded: the trigger
        // raises on the last write of the cascade, after the audit rows of the
        // consignments (or of the offer) have already been inserted.
        jdbc.sql("""
                CREATE OR REPLACE FUNCTION audit_test_fail() RETURNS trigger AS $$
                BEGIN
                    IF NEW.status IN ('CANCELLED', 'AWARDED') AND NEW.load_no LIKE 'FAIL-%' THEN
                        RAISE EXCEPTION 'injected failure moving load % to %', NEW.load_no, NEW.status;
                    END IF;
                    RETURN NEW;
                END $$ LANGUAGE plpgsql
                """).update();
        jdbc.sql("""
                CREATE TRIGGER audit_test_fail BEFORE UPDATE ON load_unit
                FOR EACH ROW EXECUTE FUNCTION audit_test_fail()
                """).update();
    }

    @AfterAll
    void removeFailureInjection() {
        jdbc.sql("DROP TRIGGER IF EXISTS audit_test_fail ON load_unit").update();
        jdbc.sql("DROP FUNCTION IF EXISTS audit_test_fail()").update();
    }

    @BeforeEach
    void aTenant() {
        salt = UUID.randomUUID().toString().substring(0, 8);
        orgPath = "/aud-" + salt + "/";
        tenantId = fixtures.createTenant("aud-" + salt, "Audit " + salt);
        orgUnitId = fixtures.createOrgUnit(tenantId, orgPath, "HQ", "HQ", null);
        inTenant(() -> {
            customer = fleet.registerPartner(orgUnitId, "C-" + salt, "Customer",
                    BusinessPartner.PartnerType.CUSTOMER, "T-C-" + salt);
            consignee = fleet.registerPartner(orgUnitId, "K-" + salt, "Consignee",
                    BusinessPartner.PartnerType.CUSTOMER, "T-K-" + salt);
            vendor = fleet.registerPartner(orgUnitId, "V-" + salt, "Vendor",
                    BusinessPartner.PartnerType.VENDOR, "T-V-" + salt);
            fleet.transitionPartner(vendor, BusinessPartner.PartnerStatus.PENDING_VERIFICATION);
            fleet.transitionPartner(vendor, BusinessPartner.PartnerStatus.ACTIVE);
            origin = depot("O-" + salt, 19.1, 72.9);
            destination = depot("D-" + salt, 19.3, 73.1);
            lorry = fleet.registerVehicle(orgUnitId, null, "AU-" + salt, "TRUCK", "RIGID",
                    "2-AXLE", new BigDecimal("26000"), new BigDecimal("6000"),
                    new BigDecimal("60"), false, false);
            return null;
        });
    }

    @AfterEach
    void signOut() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("a committed cancellation leaves one row per transition, attributed to the signed-in user")
    void committedTransitionsAreAudited() {
        UUID orderId = plannedOrder("SO-OK");
        UUID loadId = loadWith("LOAD-OK-" + salt, orderId);

        inTenant(() -> {
            orders.cancel(orderId, "customer withdrew the indent");
            return null;
        });

        assertThat(actions(orderId)).containsExactly("ORDER_RAISED", "ORDER_VALIDATED",
                "ORDER_FULLY_PLANNED", "ORDER_CANCELLED");
        assertThat(actions(consignmentOf(orderId)))
                .containsExactly("CONSIGNMENT_GENERATED", "CONSIGNMENT_ASSIGNED",
                        "CONSIGNMENT_CANCELLED");
        assertThat(actions(loadId)).containsExactly("LOAD_OPENED", "LOAD_PLANNED", "LOAD_CANCELLED");
        assertThat(jdbc.sql("""
                        SELECT DISTINCT actor_id FROM audit_log
                         WHERE tenant_id = :t AND resource_id IN (:ids)
                        """).param("t", tenantId).param("ids", List.of(orderId, loadId))
                .query(UUID.class).list()).containsExactly(actor);
    }

    @Test
    @DisplayName("a failure while cancelling the load rolls back the audit rows of the whole cascade")
    void failedCascadeLeavesNoAuditRows() {
        UUID orderId = plannedOrder("SO-ROLLBACK");
        UUID loadId = loadWith("FAIL-" + salt, orderId);
        UUID consignmentId = consignmentOf(orderId);

        assertThatThrownBy(() -> inTenant(() -> {
            orders.cancel(orderId, "will not stick");
            return null;
        })).hasMessageContaining("injected failure");

        // The consignment's CONSIGNMENT_CANCELLED row was inserted before the
        // load's write failed; it must have gone with the rest.
        assertThat(actions(orderId)).containsExactly("ORDER_RAISED", "ORDER_VALIDATED",
                "ORDER_FULLY_PLANNED");
        assertThat(actions(consignmentId)).containsExactly("CONSIGNMENT_GENERATED",
                "CONSIGNMENT_ASSIGNED");
        assertThat(actions(loadId)).containsExactly("LOAD_OPENED", "LOAD_PLANNED");
    }

    @Test
    @DisplayName("an award that fails on the load rolls back the offer's audit row and the offer")
    void failedAwardLeavesNoAuditRows() {
        UUID orderId = plannedOrder("SO-AWARD");
        UUID loadId = loadWith("FAIL-" + salt, orderId);
        UUID allocationId = inTenant(() -> {
            UUID guideId = guides.define(orgUnitId, origin, destination, "RIGID",
                    RoutingGuide.Strategy.CONTRACTUAL);
            guides.addVendor(guideId, vendor, 1, 60, new BigDecimal("10000"));
            return allocations.allocate(loadId);
        });

        assertThatThrownBy(() -> inTenant(() -> {
            allocations.accept(allocationId, vendor);
            return null;
        })).hasMessageContaining("injected failure");

        assertThat(actions(allocationId)).containsExactly("ALLOCATION_OPENED");
        assertThat(actions(loadId)).containsExactly("LOAD_OPENED", "LOAD_PLANNED");
        assertThat(jdbc.sql("SELECT outcome FROM allocation_offer WHERE allocation_id = :a")
                .param("a", allocationId).query(String.class).single())
                .as("the acceptance rolled back with its audit").isEqualTo("PENDING");
    }

    @Test
    @DisplayName("a committed award audits the allocation and the load in the same transaction")
    void committedAwardIsAudited() {
        UUID orderId = plannedOrder("SO-AWARDED");
        UUID loadId = loadWith("LOAD-AW-" + salt, orderId);
        UUID allocationId = inTenant(() -> {
            UUID guideId = guides.define(orgUnitId, origin, destination, "RIGID",
                    RoutingGuide.Strategy.CONTRACTUAL);
            guides.addVendor(guideId, vendor, 1, 60, new BigDecimal("10000"));
            UUID id = allocations.allocate(loadId);
            allocations.accept(id, vendor);
            return id;
        });

        assertThat(actions(allocationId)).containsExactly("ALLOCATION_OPENED", "ALLOCATION_AWARDED");
        assertThat(actions(loadId)).containsExactly("LOAD_OPENED", "LOAD_PLANNED", "LOAD_AWARDED");
    }

    @Test
    @DisplayName("a transition audit outside a transaction is refused rather than written on its own")
    void auditRefusesToRunOutsideATransaction() {
        UUID resourceId = UUID.randomUUID();

        assertThatThrownBy(() -> inTenant(() -> {
            transitionAudit.record("LOAD_OPENED", "Load", resourceId, null,
                    Json.object("status", "DRAFT"));
            return null;
        })).isInstanceOf(IllegalTransactionStateException.class);

        assertThat(actions(resourceId)).isEmpty();
    }

    // ------------------------------------------------------------- building

    private UUID plannedOrder(String orderNo) {
        return inTenant(() -> {
            UUID orderId = orders.raiseOrder(orgUnitId, customer, orderNo + "-" + salt, origin,
                    null, null, SalesOrder.OrderSource.MANUAL);
            orders.addLine(orderId, 1, "MAT-1", "General cargo", MaterialClass.GENERAL, null,
                    BigDecimal.ONE, "EA", new BigDecimal("1000"), null, null, null, null,
                    consignee, destination);
            orders.validate(orderId);
            planning.generateConsignments(orderId);
            return orderId;
        });
    }

    private UUID loadWith(String loadNo, UUID orderId) {
        UUID consignmentId = consignmentOf(orderId);
        return inTenant(() -> {
            UUID loadId = planning.openLoad(orgUnitId, loadNo, origin, lorry);
            planning.assignConsignment(loadId, consignmentId);
            planning.planLoad(loadId);
            return loadId;
        });
    }

    private UUID depot(String code, double lat, double lon) {
        return terminals.register(new RegisterTerminalCommand.PointRadius(orgUnitId, code, code,
                Terminal.FunctionalCategory.CUSTOMER_SITE, new LatLon(lat, lon), 500, 2, null,
                null, 45, List.of()));
    }

    // -------------------------------------------------- reading it back raw

    private UUID consignmentOf(UUID orderId) {
        return jdbc.sql("SELECT id FROM consignment WHERE order_id = :o")
                .param("o", orderId).query(UUID.class).single();
    }

    private List<String> actions(UUID resourceId) {
        return jdbc.sql("""
                        SELECT action FROM audit_log
                         WHERE tenant_id = :t AND resource_id = :r
                         ORDER BY occurred_at, id
                        """).param("t", tenantId).param("r", resourceId)
                .query(String.class).list();
    }

    /** Scope first, transaction second (the services open their own), signed in. */
    private <T> T inTenant(Supplier<T> action) {
        Set<SimpleGrantedAuthority> authorities = AUTHORITIES.stream()
                .map(SimpleGrantedAuthority::new).collect(Collectors.toSet());
        SecurityContextHolder.getContext().setAuthentication(
                new TestingAuthenticationToken(actor.toString(), null, authorities));
        return TenantContext.callWith(tenantId, orgPath, action);
    }
}
