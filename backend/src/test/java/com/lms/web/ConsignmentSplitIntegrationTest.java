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
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import com.lms.acceptance.TestFixtures;
import com.lms.clientbilling.query.ClientDirectory;
import com.lms.delivery.command.ClaimService;
import com.lms.delivery.command.domain.ClaimType;
import com.lms.masterdata.command.FleetCommandService;
import com.lms.masterdata.command.RegisterTerminalCommand;
import com.lms.masterdata.command.TerminalCommandService;
import com.lms.masterdata.command.domain.BusinessPartner;
import com.lms.masterdata.command.domain.Terminal;
import com.lms.order.api.MaterialClass;
import com.lms.order.command.OrderCommandService;
import com.lms.order.command.domain.SalesOrder;
import com.lms.planning.api.LoadLifecyclePort;
import com.lms.planning.command.ConsignmentSplitService;
import com.lms.planning.command.PlanningCommandService;
import com.lms.planning.command.domain.Consignment.PartSpec;
import com.lms.planning.query.ConsignmentView;
import com.lms.planning.query.PlanningQueryService;
import com.lms.shared.error.BusinessRuleViolationException;
import com.lms.shared.geo.LatLon;
import com.lms.shared.query.Slice;
import com.lms.shared.tenant.TenantContext;
import com.lms.tracking.command.TrackingLinkService;
import com.lms.tracking.query.PublicTrackingQueryService;
import com.lms.tracking.query.PublicTrackingView;
import org.junit.jupiter.api.AfterEach;
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
 * One consignment carried by many vehicles, against a real Postgres: the parts
 * reach their own loads, are delivered one vehicle at a time, and the order,
 * the tracking link, the bill and the cancellation all read the parts and never
 * the split consignment.
 */
@SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ConsignmentSplitIntegrationTest {

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
            "ORDER_CREATE", "ORDER_UPDATE", "LOAD_CREATE", "LOAD_READ", "TRACKING_LINK_MANAGE",
            "CLAIM_RAISE");

    /** A 20 t lorry: 26 t gross, 6 t tare. */
    private static final BigDecimal LORRY_PAYLOAD_KG = new BigDecimal("20000");

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
    private ConsignmentSplitService splits;
    @Autowired
    private PlanningQueryService planningQueries;
    @Autowired
    private LoadLifecyclePort loadPort;
    @Autowired
    private ClientDirectory clients;
    @Autowired
    private TrackingLinkService trackingLinks;
    @Autowired
    private PublicTrackingQueryService publicTracking;
    @Autowired
    private ClaimService claims;
    @Autowired
    private JdbcClient jdbc;

    private UUID tenantId;
    private String orgPath;
    private UUID orgUnitId;
    private UUID customer;
    private UUID consignee;
    private UUID origin;
    private UUID destination;
    private UUID lorry;
    private String salt;

    @BeforeEach
    void aTenant() {
        salt = UUID.randomUUID().toString().substring(0, 8);
        orgPath = "/split-" + salt + "/";
        tenantId = fixtures.createTenant("split-" + salt, "Split " + salt);
        orgUnitId = fixtures.createOrgUnit(tenantId, orgPath, "HQ", "HQ", null);
        inTenant(() -> {
            customer = fleet.registerPartner(orgUnitId, "C-" + salt, "Steel works",
                    BusinessPartner.PartnerType.CUSTOMER, "T-C-" + salt);
            consignee = fleet.registerPartner(orgUnitId, "K-" + salt, "Rolling mill",
                    BusinessPartner.PartnerType.CUSTOMER, "T-K-" + salt);
            origin = depot("O-" + salt, 21.1, 79.0);
            destination = depot("D-" + salt, 21.3, 79.2);
            lorry = fleet.registerVehicle(orgUnitId, null, "SP-" + salt, "TRUCK", "RIGID", "2-AXLE",
                    new BigDecimal("26000"), new BigDecimal("6000"), new BigDecimal("60"), false, false);
            return null;
        });
    }

    @AfterEach
    void signOut() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("two thousand tonnes for one consignee go out as a hundred parts on a hundred loads")
    void aHundredPartsOnAHundredLoads() {
        UUID orderId = bulkOrder("SO-BULK", "2000000");
        UUID whole = theConsignmentOf(orderId);

        assertThatThrownBy(() -> inTenant(() -> {
            UUID load = planning.openLoad(orgUnitId, "LD-WHOLE-" + salt, origin, lorry);
            planning.assignConsignment(load, whole);
            return null;
        })).as("no lorry carries the whole").isInstanceOfSatisfying(BusinessRuleViolationException.class,
                e -> assertThat(e.code()).isEqualTo("load-capacity-exceeded"));

        List<ConsignmentSplitService.SplitPart> parts = split(whole, 100, "20000", "0");
        assertThat(parts).hasSize(100);
        assertThat(parts.get(0).trackingRef()).endsWith("-001");
        assertThat(parts.get(99).trackingRef()).endsWith("-100");

        List<UUID> loads = new ArrayList<>();
        for (int i = 0; i < parts.size(); i++) {
            loads.add(loadWith("LD-" + i + "-" + salt, parts.get(i).id()));
        }
        assertThat(jdbc.sql("SELECT count(DISTINCT load_id) FROM load_consignment WHERE consignment_id IN "
                        + "(SELECT id FROM consignment WHERE parent_consignment_id = :w)")
                .param("w", whole).query(Integer.class).single()).isEqualTo(100);
        assertThat(jdbc.sql("SELECT count(*) FROM consignment_line WHERE consignment_id IN "
                        + "(SELECT id FROM consignment WHERE parent_consignment_id = :w)")
                .param("w", whole).query(Integer.class).single())
                .as("every part is linked to the order line").isEqualTo(100);

        // Three vehicles arrive; the rest are still on the road.
        for (int i = 0; i < 3; i++) {
            deliver(loads.get(i));
        }
        ConsignmentView parent = inTenant(() -> planningQueries.findConsignment(whole).orElseThrow());
        assertThat(parent.status()).isEqualTo("SPLIT");
        assertThat(parent.partCount()).isEqualTo(100);
        assertThat(parent.partsDelivered()).isEqualTo(3);
        assertThat(parent.partsCancelled()).isZero();
        assertThat(parent.loadNo()).as("the whole is on no load").isNull();
        assertThat(statusOfOrder(orderId)).as("97 vehicles still to arrive").isNotEqualTo("FULFILLED");

        Slice<ConsignmentView> firstPage = inTenant(() -> planningQueries.listParts(whole, null, 60).orElseThrow());
        assertThat(firstPage.items()).hasSize(60);
        assertThat(firstPage.items().get(0).parentTrackingRef()).isEqualTo(parent.trackingRef());
        assertThat(firstPage.items().get(0).status()).isEqualTo("DELIVERED");
        Slice<ConsignmentView> secondPage = inTenant(() ->
                planningQueries.listParts(whole, firstPage.nextCursor(), 60).orElseThrow());
        assertThat(secondPage.items()).hasSize(40);
        assertThat(secondPage.hasMore()).isFalse();

        for (int i = 3; i < loads.size(); i++) {
            deliver(loads.get(i));
        }
        assertThat(statusOfOrder(orderId)).as("the last part's delivery fulfils the order").isEqualTo("FULFILLED");
        assertThat(jdbc.sql("SELECT status FROM consignment WHERE id = :w").param("w", whole)
                .query(String.class).single()).as("the whole stays SPLIT").isEqualTo("SPLIT");
    }

    @Test
    @DisplayName("each part is billed once on its own load, and the split consignment never")
    void billingFollowsTheParts() {
        UUID whole = theConsignmentOf(bulkOrder("SO-BILL", "30000"));
        List<ConsignmentSplitService.SplitPart> parts = inTenant(() -> splits.split(whole, List.of(
                new PartSpec(new BigDecimal("20000"), BigDecimal.ZERO),
                new PartSpec(new BigDecimal("10000"), BigDecimal.ZERO))));
        UUID first = loadWith("LD-B1-" + salt, parts.get(0).id());
        UUID second = loadWith("LD-B2-" + salt, parts.get(1).id());

        List<ClientDirectory.ClientShare> onFirst = inTenant(() -> clients.sharesOf(first));
        List<ClientDirectory.ClientShare> onSecond = inTenant(() -> clients.sharesOf(second));
        assertThat(onFirst).singleElement().satisfies(share -> {
            assertThat(share.consignmentCount()).isEqualTo(1);
            assertThat(share.chargeableWeightKg()).isEqualByComparingTo("20000");
        });
        assertThat(onSecond).singleElement().satisfies(share ->
                assertThat(share.chargeableWeightKg()).isEqualByComparingTo("10000"));
    }

    @Test
    @DisplayName("the split consignment is never loaded, by the service or straight into the table")
    void theWholeIsNeverLoaded() {
        UUID whole = theConsignmentOf(bulkOrder("SO-NOLOAD", "30000"));
        split(whole, 2, "15000", "0");

        assertThatThrownBy(() -> inTenant(() -> {
            planning.assignConsignment(planning.openLoad(orgUnitId, "LD-N-" + salt, origin, lorry), whole);
            return null;
        })).isInstanceOfSatisfying(BusinessRuleViolationException.class,
                e -> assertThat(e.code()).isEqualTo("consignment-not-assignable"));

        UUID load = inTenant(() -> planning.openLoad(orgUnitId, "LD-RAW-" + salt, origin, lorry));
        assertThatThrownBy(() -> jdbc.sql("""
                        INSERT INTO load_consignment (tenant_id, load_id, consignment_id, drop_sequence, leg)
                        VALUES (:t, :l, :c, 1, 'LINE_HAUL')
                        """).param("t", tenantId).param("l", load).param("c", whole).update())
                .hasMessageContaining("has been split");
        assertThat(inTenant(() -> planningQueries.listUnassigned(null, 200)).items())
                .extracting(ConsignmentView::id).doesNotContain(whole).hasSize(2);
    }

    @Test
    @DisplayName("cancelling the order cancels the parts and leaves the split consignment as it was")
    void cancellationReachesTheParts() {
        UUID orderId = bulkOrder("SO-CANCEL", "40000");
        UUID whole = theConsignmentOf(orderId);
        List<ConsignmentSplitService.SplitPart> parts = split(whole, 2, "20000", "0");
        UUID load = loadWith("LD-C-" + salt, parts.get(0).id());

        inTenant(() -> {
            orders.cancel(orderId, "customer withdrew the indent");
            return null;
        });

        assertThat(jdbc.sql("SELECT status FROM consignment WHERE parent_consignment_id = :w")
                .param("w", whole).query(String.class).list()).containsOnly("CANCELLED");
        assertThat(jdbc.sql("SELECT status FROM consignment WHERE id = :w").param("w", whole)
                .query(String.class).single()).isEqualTo("SPLIT");
        assertThat(jdbc.sql("SELECT status FROM load_unit WHERE id = :l").param("l", load)
                .query(String.class).single()).as("the emptied load goes too").isEqualTo("CANCELLED");
        ConsignmentView parent = inTenant(() -> planningQueries.findConsignment(whole).orElseThrow());
        assertThat(parent.partsCancelled()).isEqualTo(2);
    }

    @Test
    @DisplayName("a dispatched part refuses the order's cancellation")
    void aDispatchedPartRefusesCancellation() {
        UUID orderId = bulkOrder("SO-LEFT", "40000");
        List<ConsignmentSplitService.SplitPart> parts = split(theConsignmentOf(orderId), 2, "20000", "0");
        UUID load = loadWith("LD-L-" + salt, parts.get(0).id());
        inTenant(() -> {
            loadPort.recordAwarded(load, customer);
            loadPort.recordDispatched(load);
            return null;
        });

        assertThatThrownBy(() -> inTenant(() -> {
            orders.cancel(orderId, null);
            return null;
        })).isInstanceOfSatisfying(BusinessRuleViolationException.class,
                e -> assertThat(e.code()).isEqualTo("order-work-dispatched"));
    }

    @Test
    @DisplayName("a customer's link on the split consignment counts the parts delivered")
    void trackingLinkFollowsTheParts() {
        UUID whole = theConsignmentOf(bulkOrder("SO-TRACK", "60000"));
        List<ConsignmentSplitService.SplitPart> parts = split(whole, 3, "20000", "0");
        List<UUID> loads = new ArrayList<>();
        for (int i = 0; i < parts.size(); i++) {
            loads.add(loadWith("LD-T" + i + "-" + salt, parts.get(i).id()));
        }
        String token = inTenant(() -> trackingLinks.issue(whole, null)).token();

        PublicTrackingView planned = publicTracking.view(token).orElseThrow();
        assertThat(planned.status()).isEqualTo("PLANNED");
        assertThat(planned.partsTotal()).isEqualTo(3);
        assertThat(planned.partsDelivered()).isZero();

        deliver(loads.get(0));
        PublicTrackingView moving = publicTracking.view(token).orElseThrow();
        assertThat(moving.status()).isEqualTo("IN_TRANSIT");
        assertThat(moving.partsDelivered()).isEqualTo(1);
        assertThat(moving.position()).as("no one vehicle to show").isNull();

        deliver(loads.get(1));
        deliver(loads.get(2));
        PublicTrackingView done = publicTracking.view(token).orElseThrow();
        assertThat(done.status()).isEqualTo("DELIVERED");
        assertThat(done.partsDelivered()).isEqualTo(3);
        assertThat(done.deliveredAt()).isNotNull();
        assertThat(done.proofRecorded()).as("no recipient has confirmed any part").isFalse();
    }

    @Test
    @DisplayName("a claim goes against the part that was lost, not the split consignment")
    void claimsGoAgainstParts() {
        UUID whole = theConsignmentOf(bulkOrder("SO-CLAIM", "40000"));
        List<ConsignmentSplitService.SplitPart> parts = split(whole, 2, "20000", "0");

        assertThatThrownBy(() -> inTenant(() -> claims.raise(whole, ClaimType.LOSS,
                new BigDecimal("1000"), "a whole lorry went missing")))
                .isInstanceOfSatisfying(BusinessRuleViolationException.class,
                        e -> assertThat(e.code()).isEqualTo("claim-against-split-consignment"));
        assertThat(inTenant(() -> claims.raise(parts.get(1).id(), ClaimType.LOSS,
                new BigDecimal("1000"), "a whole lorry went missing"))).isNotNull();
    }

    @Test
    @DisplayName("a split is audited, announced once, and a second split of the same consignment is refused")
    void auditedAndNotRepeatable() {
        UUID whole = theConsignmentOf(bulkOrder("SO-AUDIT", "40000"));
        split(whole, 2, "20000", "0");

        assertThatThrownBy(() -> split(whole, 2, "20000", "0"))
                .isInstanceOfSatisfying(BusinessRuleViolationException.class,
                        e -> assertThat(e.code()).isEqualTo("consignment-not-splittable"));
        assertThat(jdbc.sql("""
                        SELECT count(*) FROM audit_log
                         WHERE tenant_id = :t AND action = 'CONSIGNMENT_SPLIT' AND resource_id = :w
                        """).param("t", tenantId).param("w", whole).query(Integer.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM consignment WHERE parent_consignment_id = :w")
                .param("w", whole).query(Integer.class).single()).as("nothing more was written").isEqualTo(2);
    }

    @Test
    @DisplayName("parts that do not add up are refused and nothing is written")
    void refusedSplitWritesNothing() {
        UUID whole = theConsignmentOf(bulkOrder("SO-SUM", "40000"));

        assertThatThrownBy(() -> inTenant(() -> splits.split(whole, List.of(
                new PartSpec(new BigDecimal("20000"), BigDecimal.ZERO),
                new PartSpec(new BigDecimal("19999.999"), BigDecimal.ZERO)))))
                .isInstanceOfSatisfying(BusinessRuleViolationException.class,
                        e -> assertThat(e.code()).isEqualTo("split-weights-do-not-add-up"));
        assertThat(jdbc.sql("SELECT status FROM consignment WHERE id = :w").param("w", whole)
                .query(String.class).single()).isEqualTo("PLANNED");
        assertThat(jdbc.sql("SELECT count(*) FROM consignment WHERE parent_consignment_id = :w")
                .param("w", whole).query(Integer.class).single()).isZero();
    }

    // ------------------------------------------------------------- building

    private UUID bulkOrder(String orderNo, String weightKg) {
        return inTenant(() -> {
            UUID orderId = orders.raiseOrder(orgUnitId, customer, orderNo + "-" + salt, origin,
                    null, null, SalesOrder.OrderSource.MANUAL);
            orders.addLine(orderId, 1, "STEEL-COIL", "Hot-rolled coil", MaterialClass.GENERAL,
                    null, BigDecimal.ONE, "LOT", new BigDecimal(weightKg), null, null, null, null,
                    consignee, destination);
            orders.validate(orderId);
            assertThat(planning.generateConsignments(orderId)).as("one consignee, one consignment").hasSize(1);
            return orderId;
        });
    }

    private List<ConsignmentSplitService.SplitPart> split(UUID whole, int count, String weightKg, String volumeM3) {
        return inTenant(() -> splits.split(whole, Collections.nCopies(count,
                new PartSpec(new BigDecimal(weightKg), new BigDecimal(volumeM3)))));
    }

    private UUID loadWith(String loadNo, UUID consignmentId) {
        return inTenant(() -> {
            UUID loadId = planning.openLoad(orgUnitId, loadNo, origin, lorry);
            planning.assignConsignment(loadId, consignmentId);
            planning.planLoad(loadId);
            return loadId;
        });
    }

    private void deliver(UUID loadId) {
        inTenant(() -> {
            loadPort.recordAwarded(loadId, customer);
            loadPort.recordDispatched(loadId);
            loadPort.recordTripCompleted(loadId);
            return null;
        });
    }

    private UUID depot(String code, double lat, double lon) {
        return terminals.register(new RegisterTerminalCommand.PointRadius(orgUnitId, code, code,
                Terminal.FunctionalCategory.CUSTOMER_SITE, new LatLon(lat, lon), 500, 2, null,
                null, 45, List.of()));
    }

    // -------------------------------------------------- reading it back raw

    private UUID theConsignmentOf(UUID orderId) {
        return jdbc.sql("SELECT id FROM consignment WHERE order_id = :o AND parent_consignment_id IS NULL")
                .param("o", orderId).query(UUID.class).single();
    }

    private String statusOfOrder(UUID orderId) {
        return jdbc.sql("SELECT status FROM sales_order WHERE id = :o")
                .param("o", orderId).query(String.class).single();
    }

    /** Scope first, transaction second (the services open their own), signed in. */
    private <T> T inTenant(Supplier<T> action) {
        Set<SimpleGrantedAuthority> authorities = AUTHORITIES.stream()
                .map(SimpleGrantedAuthority::new).collect(Collectors.toSet());
        SecurityContextHolder.getContext().setAuthentication(
                new TestingAuthenticationToken("split-test", null, authorities));
        return TenantContext.callWith(tenantId, orgPath, action);
    }
}
