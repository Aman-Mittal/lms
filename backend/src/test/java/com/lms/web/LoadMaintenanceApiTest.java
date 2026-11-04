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
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import com.lms.acceptance.TestFixtures;
import com.lms.client.ApiClient;
import com.lms.client.api.OrdersApi;
import com.lms.client.api.PlanningApi;
import com.lms.client.model.AddOrderLineRequest;
import com.lms.client.model.ApiResponse;
import com.lms.client.model.BulkAssignConsignmentsRequest;
import com.lms.client.model.CancelLoadRequest;
import com.lms.client.model.Consignment;
import com.lms.client.model.HistoryEntry;
import com.lms.client.model.IdResponse;
import com.lms.client.model.Load;
import com.lms.client.model.OpenLoadRequest;
import com.lms.client.model.RaiseOrderRequest;
import com.lms.identity.command.domain.AppUser;
import com.lms.identity.security.TokenService;
import com.lms.masterdata.command.FleetCommandService;
import com.lms.masterdata.command.RegisterTerminalCommand;
import com.lms.masterdata.command.TerminalCommandService;
import com.lms.masterdata.command.domain.BusinessPartner;
import com.lms.masterdata.command.domain.Terminal;
import com.lms.shared.geo.LatLon;
import com.lms.shared.tenant.TenantContext;
import feign.FeignException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Changing a load after it was built, over HTTP: taking a consignment off,
 * opening a planned load again, cancelling it, each guarded by the load's
 * version in {@code If-Match}.
 *
 * <p>Around the happy paths: the drops close up and the totals follow the
 * freight; a stale or missing version changes nothing; two planners racing
 * on one load never both win and never leave it inconsistent, whether they
 * remove, plan or board in bulk. The sourcing and trip guards are exercised
 * in {@code load_maintenance.feature}, where the allocation steps already
 * exist. Master data is set up in-process, as in {@code BulkLoadBuildingApiTest}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LoadMaintenanceApiTest {

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

    private static final List<String> PERMISSIONS = List.of(
            "ORDER_CREATE", "ORDER_READ", "ORDER_UPDATE", "LOAD_CREATE", "LOAD_READ");

    /** What the in-process setup needs. Never put in a token. */
    private static final List<String> SETUP = List.of(
            "PARTNER_CREATE", "PARTNER_UPDATE", "VEHICLE_CREATE", "TERMINAL_CREATE");

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @LocalServerPort
    private int port;

    @Autowired
    private TestFixtures fixtures;
    @Autowired
    private FleetCommandService fleet;
    @Autowired
    private TerminalCommandService terminals;
    @Autowired
    private TokenService tokens;
    @Autowired
    private JdbcClient jdbc;
    @Autowired
    private PlatformTransactionManager transactionManager;

    private String salt;
    private String orgPath;
    private UUID tenantId;
    private UUID orgUnitId;
    private AppUser planner;
    private UUID customer;
    private UUID origin;
    private UUID destination;
    private UUID lorry;
    private OrdersApi orders;
    private PlanningApi planning;

    @BeforeEach
    void aYard() {
        salt = UUID.randomUUID().toString().substring(0, 8);
        orgPath = "/maint-" + salt + "/";
        tenantId = fixtures.createTenant("maint-" + salt, "Load Maintenance Freight");
        orgUnitId = fixtures.createOrgUnit(tenantId, orgPath, "Maint HQ", "HQ", null);
        planner = user("planner@maint.test");

        asSetup(() -> {
            customer = partner("CUST-" + salt);
            origin = depot("ORIGIN-" + salt, 34.10, 81.10);
            destination = depot("DEST-" + salt, 34.40, 81.40);
            lorry = fleet.registerVehicle(orgUnitId, null, "MT-30-" + salt, "TRUCK", "RIGID",
                    "3-AXLE", new BigDecimal("36000"), new BigDecimal("6000"),
                    new BigDecimal("90"), true, false);
            return null;
        });
        orders = client(OrdersApi.class);
        planning = client(PlanningApi.class);
    }

    @AfterEach
    void signOut() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("a consignment taken off goes back to the queue, the drops close up and the totals follow")
    void removeFromAPlannedLoad() {
        // 100, 200 (dangerous), 300 and 400 kg, boarded in that order.
        List<Consignment> four = consignments("SO-RM", List.of("100", "200", "300", "400"), 1);
        UUID load = openLoad("LD-RM");
        board(load, four);
        planning.planLoad(load, null);

        ApiResponse<Load> read = planning.getLoadWithHttpInfo(load);
        Load before = read.getData();
        assertThat(before.getRequiresHazmat()).isTrue();
        assertThat(before.getPlannedWeightKg()).isEqualByComparingTo("1000");
        assertThat(header(read.getHeaders(), "etag")).isEqualTo("\"" + before.getVersion() + "\"");

        Consignment dangerous = four.get(1);
        planning.removeConsignmentFromLoad(etagOf(load), load, dangerous.getId(), null);

        Load after = planning.getLoad(load);
        assertThat(after.getStatus()).isEqualTo("PLANNED");
        assertThat(after.getConsignmentCount()).isEqualTo(3);
        assertThat(after.getPlannedWeightKg()).isEqualByComparingTo("800");
        assertThat(after.getPlannedVolumeM3()).isEqualByComparingTo("3");
        assertThat(after.getRequiresHazmat()).as("the dangerous goods left with it").isFalse();
        assertThat(after.getVersion()).isGreaterThan(before.getVersion());

        assertThat(planning.listLoadConsignments(load)).extracting(Consignment::getId)
                .containsExactly(four.get(0).getId(), four.get(2).getId(), four.get(3).getId());
        assertThat(dropSequences(load)).containsExactly(1, 2, 3);
        assertThat(planning.getConsignment(dangerous.getId()).getStatus()).isEqualTo("PLANNED");
        assertThat(planning.getConsignment(dangerous.getId()).getLoadNo()).isNull();
        assertThat(planning.listUnassignedConsignments(null, 200, dangerous.getOrderId(), null)
                .getItems()).extracting(Consignment::getId).containsExactly(dangerous.getId());

        // Both histories show the step, the load's with the totals either side.
        HistoryEntry removed = planning.getLoadHistory(load, null, null).getItems().get(0);
        assertThat(removed.getAction()).isEqualTo("LOAD_CONSIGNMENT_REMOVED");
        assertThat(new BigDecimal(String.valueOf(removed.getBefore().get("plannedWeightKg"))))
                .isEqualByComparingTo("1000");
        assertThat(removed.getBefore()).containsEntry("consignmentCount", "4");
        assertThat(new BigDecimal(String.valueOf(removed.getAfter().get("plannedWeightKg"))))
                .isEqualByComparingTo("800");
        assertThat(removed.getAfter()).containsEntry("consignmentCount", "3")
                .containsEntry("requiresHazmat", "false")
                .containsEntry("trackingRef", dangerous.getTrackingRef());
        assertThat(planning.getConsignmentHistory(dangerous.getId(), null, null).getItems().get(0)
                .getAction()).isEqualTo("CONSIGNMENT_REMOVED_FROM_LOAD");

        // A repeat of the same removal finds nothing to remove.
        assertProblem(() -> planning.removeConsignmentFromLoad(etagOf(load), load, dangerous.getId(), null),
                409, "consignment-not-on-load");
    }

    @Test
    @DisplayName("a missing or stale If-Match changes nothing")
    void versionGuard() {
        List<Consignment> two = consignments("SO-VG", List.of("100", "100"), -1);
        UUID load = openLoad("LD-VG");
        board(load, two);
        String stale = etagOf(load);
        planning.removeConsignmentFromLoad(stale, load, two.get(0).getId(), null);

        assertProblem(() -> planning.removeConsignmentFromLoad(stale, load, two.get(1).getId(), null),
                409, "concurrent-modification");
        assertProblem(() -> planning.removeConsignmentFromLoad("", load, two.get(1).getId(), null),
                400, null);
        assertProblem(() -> planning.removeConsignmentFromLoad("*", load, two.get(1).getId(), null),
                400, null);
        assertProblem(() -> planning.cancelLoad(stale, load, null, new CancelLoadRequest()), 409, "concurrent-modification");
        assertThat(planning.getLoad(load).getConsignmentCount()).isEqualTo(1);
        assertThat(planning.getLoad(load).getStatus()).isEqualTo("DRAFT");

        assertProblem(() -> planning.removeConsignmentFromLoad(etagOf(load), UUID.randomUUID(),
                two.get(1).getId(), null), 404, "not-found");
        assertProblem(() -> planning.removeConsignmentFromLoad(etagOf(load), load, UUID.randomUUID(), null),
                404, "not-found");

        PlanningApi reader = client(PlanningApi.class, List.of("LOAD_READ"));
        assertProblem(() -> reader.removeConsignmentFromLoad(etagOf(load), load, two.get(1).getId(), null),
                403, "access-denied");
        assertProblem(() -> reader.unplanLoad(etagOf(load), load, null), 403, "access-denied");
        assertProblem(() -> reader.cancelLoad(etagOf(load), load, null, new CancelLoadRequest()), 403, "access-denied");
    }

    @Test
    @DisplayName("the last consignment of a planned load stays on; a draft load may be emptied")
    void lastOnAPlannedLoad() {
        List<Consignment> one = consignments("SO-LAST", List.of("100"), -1);
        UUID load = openLoad("LD-LAST");
        board(load, one);
        planning.planLoad(load, null);

        assertProblem(() -> planning.removeConsignmentFromLoad(etagOf(load), load, one.get(0).getId(), null),
                409, "load-would-be-empty");

        planning.unplanLoad(etagOf(load), load, null);
        assertThat(planning.getLoad(load).getStatus()).isEqualTo("DRAFT");
        assertProblem(() -> planning.unplanLoad(etagOf(load), load, null), 409, "load-not-planned");

        planning.removeConsignmentFromLoad(etagOf(load), load, one.get(0).getId(), null);
        Load empty = planning.getLoad(load);
        assertThat(empty.getConsignmentCount()).isZero();
        assertThat(empty.getPlannedWeightKg()).isEqualByComparingTo("0");
        assertThat(planning.getLoadHistory(load, null, null).getItems()).extracting(HistoryEntry::getAction)
                .startsWith("LOAD_CONSIGNMENT_REMOVED", "LOAD_UNPLANNED", "LOAD_PLANNED");
    }

    @Test
    @DisplayName("a cancelled load releases everything aboard, keeps the reason, and stays cancelled")
    void cancel() {
        List<Consignment> three = consignments("SO-CX", List.of("100", "200", "300"), -1);
        UUID load = openLoad("LD-CX");
        board(load, three);
        planning.planLoad(load, null);

        planning.cancelLoad(etagOf(load), load, null,
                new CancelLoadRequest().reason("vehicle broke down at the yard"));

        Load cancelled = planning.getLoad(load);
        assertThat(cancelled.getStatus()).isEqualTo("CANCELLED");
        assertThat(cancelled.getConsignmentCount()).isZero();
        assertThat(cancelled.getPlannedWeightKg()).isEqualByComparingTo("0");
        for (Consignment c : three) {
            assertThat(planning.getConsignment(c.getId()).getStatus()).isEqualTo("PLANNED");
        }
        HistoryEntry entry = planning.getLoadHistory(load, null, null).getItems().get(0);
        assertThat(entry.getAction()).isEqualTo("LOAD_CANCELLED");
        assertThat(entry.getAfter()).containsEntry("reason", "vehicle broke down at the yard")
                .containsEntry("status", "CANCELLED");
        assertThat(entry.getBefore()).containsEntry("consignmentCount", "3");

        assertProblem(() -> planning.cancelLoad(etagOf(load), load, null, new CancelLoadRequest()), 409, "load-already-cancelled");
        assertProblem(() -> planning.unplanLoad(etagOf(load), load, null), 409, "load-cancelled");

        // The released freight boards another vehicle, in bulk, as if it had never been away.
        UUID next = openLoad("LD-CX2");
        board(next, three);
        assertThat(planning.getLoad(next).getPlannedWeightKg()).isEqualByComparingTo("600");
    }

    @Test
    @DisplayName("removed freight boards again in bulk after what is aboard, and the drops stay unbroken")
    void bulkInterplay() {
        List<Consignment> five = consignments("SO-BI", List.of("100", "100", "100", "100", "100"), -1);
        UUID load = openLoad("LD-BI");
        board(load, five.subList(0, 4));

        planning.removeConsignmentFromLoad(etagOf(load), load, five.get(0).getId(), null);
        planning.removeConsignmentFromLoad(etagOf(load), load, five.get(2).getId(), null);
        assertThat(dropSequences(load)).containsExactly(1, 2);

        // A batch naming one that is still aboard is refused whole, as before.
        assertProblem(() -> planning.assignConsignmentsInBulk(load, new BulkAssignConsignmentsRequest()
                .consignmentIds(List.of(five.get(0).getId(), five.get(1).getId())), null),
                409, "load-bulk-assignment-refused");

        planning.assignConsignmentsInBulk(load, new BulkAssignConsignmentsRequest()
                .consignmentIds(List.of(five.get(4).getId(), five.get(0).getId(), five.get(2).getId())), null);
        assertThat(planning.listLoadConsignments(load)).extracting(Consignment::getId).containsExactly(
                five.get(1).getId(), five.get(3).getId(), five.get(4).getId(), five.get(0).getId(),
                five.get(2).getId());
        assertThat(dropSequences(load)).containsExactly(1, 2, 3, 4, 5);
        assertThat(planning.getLoad(load).getPlannedWeightKg()).isEqualByComparingTo("500");
    }

    @Test
    @DisplayName("two removals read at one version: one wins, the other is told the load moved on")
    void twoRemovalsRace() throws Exception {
        for (int round = 0; round < 5; round++) {
            List<Consignment> three = consignments("SO-R" + round, List.of("100", "200", "300"), -1);
            UUID load = openLoad("LD-R" + round);
            board(load, three);
            String version = etagOf(load);

            List<Integer> statuses = race(
                    () -> planning.removeConsignmentFromLoad(version, load, three.get(0).getId(), null),
                    () -> planning.removeConsignmentFromLoad(version, load, three.get(1).getId(), null));

            assertThat(statuses).containsExactlyInAnyOrder(204, 409);
            assertConsistent(load);
            assertThat(planning.getLoad(load).getConsignmentCount()).isEqualTo(2);
        }
    }

    @Test
    @DisplayName("the same consignment removed twice at once comes off once")
    void sameRemovalRace() throws Exception {
        List<Consignment> two = consignments("SO-SR", List.of("100", "200"), -1);
        UUID load = openLoad("LD-SR");
        board(load, two);
        String version = etagOf(load);

        List<Integer> statuses = race(
                () -> planning.removeConsignmentFromLoad(version, load, two.get(0).getId(), null),
                () -> planning.removeConsignmentFromLoad(version, load, two.get(0).getId(), null));

        assertThat(statuses).containsExactlyInAnyOrder(204, 409);
        assertConsistent(load);
        assertThat(planning.getConsignment(two.get(0).getId()).getStatus()).isEqualTo("PLANNED");
    }

    @Test
    @DisplayName("a removal racing planning, or a bulk boarding, leaves the load whole whichever wins")
    void removalAgainstPlanAndBulk() throws Exception {
        for (int round = 0; round < 5; round++) {
            List<Consignment> four = consignments("SO-P" + round, List.of("100", "200", "300", "400"), -1);
            UUID load = openLoad("LD-P" + round);
            board(load, four.subList(0, 2));
            String version = etagOf(load);

            List<Integer> statuses = race(
                    () -> planning.removeConsignmentFromLoad(version, load, four.get(0).getId(), null),
                    () -> planning.planLoad(load, null));
            assertThat(statuses).doesNotContain(500).contains(204);
            assertConsistent(load);

            String now = etagOf(load);
            UUID still = planning.listLoadConsignments(load).get(0).getId();
            List<UUID> batch = List.of(four.get(2).getId(), four.get(3).getId());
            String status = planning.getLoad(load).getStatus();
            List<Integer> bulk = race(
                    () -> {
                        if ("PLANNED".equals(status)) {
                            planning.unplanLoad(now, load, null);
                        } else {
                            planning.removeConsignmentFromLoad(now, load, still, null);
                        }
                    },
                    () -> planning.assignConsignmentsInBulk(load,
                            new BulkAssignConsignmentsRequest().consignmentIds(batch), null));
            assertThat(bulk).doesNotContain(500).contains(204);
            assertConsistent(load);
        }
    }

    // --------------------------------------------------------------- helpers

    /** Runs both calls at once and answers each one's HTTP status. */
    private static List<Integer> race(Runnable first, Runnable second) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Integer>> results = new ArrayList<>();
            for (Runnable call : List.of(first, second)) {
                Callable<Integer> task = () -> {
                    start.await();
                    try {
                        call.run();
                        return 204;
                    } catch (FeignException e) {
                        if (e.status() == 409) {
                            assertThat(JSON.readTree(e.contentUTF8()).get("code").asString())
                                    .isEqualTo("concurrent-modification");
                        }
                        return e.status();
                    }
                };
                results.add(pool.submit(task));
            }
            start.countDown();
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> result : results) {
                statuses.add(result.get(60, TimeUnit.SECONDS));
            }
            return statuses;
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * The load says what is aboard: its totals are the sum of its freight,
     * its drops run 1..n, every consignment aboard is boarded and every one
     * waiting is not on it, and a planned load is not empty.
     */
    private void assertConsistent(UUID load) {
        Load read = planning.getLoad(load);
        List<Consignment> aboard = planning.listLoadConsignments(load);
        BigDecimal weight = aboard.stream().map(Consignment::getTotalDeadWeightKg)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(read.getPlannedWeightKg()).isEqualByComparingTo(weight);
        assertThat(read.getConsignmentCount()).isEqualTo(aboard.size());
        assertThat(aboard).allMatch(c -> "ASSIGNED_TO_LOAD".equals(c.getStatus()));
        List<Integer> drops = dropSequences(load);
        for (int i = 0; i < drops.size(); i++) {
            assertThat(drops.get(i)).isEqualTo(i + 1);
        }
        if ("PLANNED".equals(read.getStatus())) {
            assertThat(aboard).isNotEmpty();
        }
    }

    private List<Integer> dropSequences(UUID load) {
        return TenantContext.callWith(tenantId, orgPath, () -> new TransactionTemplate(transactionManager)
                .execute(status -> jdbc.sql("""
                                SELECT drop_sequence FROM load_consignment
                                 WHERE tenant_id = :tenant AND load_id = :load ORDER BY drop_sequence
                                """)
                        .param("tenant", tenantId).param("load", load)
                        .query(Integer.class).list()));
    }

    /**
     * An order of one line per weight, each to its own consignee, cut into
     * one consignment per line; the line at {@code hazmatAt} (if any) carries
     * dangerous goods. Answered in line order.
     */
    private List<Consignment> consignments(String orderNo, List<String> weights, int hazmatAt) {
        UUID orderId = orders.raiseOrder(new RaiseOrderRequest().orgUnitId(orgUnitId)
                .customerPartnerId(customer).orderNo(orderNo + "-" + salt).originTerminalId(origin), null)
                .getId();
        List<UUID> consignees = new ArrayList<>();
        for (int i = 0; i < weights.size(); i++) {
            boolean hazmat = i == hazmatAt;
            UUID consignee = consignee(orderNo + "-" + i);
            consignees.add(consignee);
            AddOrderLineRequest line = new AddOrderLineRequest().lineNo(i + 1).materialCode("MAT-" + i)
                    .materialDescription("cargo").materialClass(hazmat ? "TOXIC" : "GENERAL")
                    .quantity(BigDecimal.ONE).uom("EA").deadWeightKg(new BigDecimal(weights.get(i)))
                    .lengthM(BigDecimal.ONE).widthM(BigDecimal.ONE).heightM(BigDecimal.ONE)
                    .consigneePartnerId(consignee).destinationTerminalId(destination);
            orders.addOrderLine(orderId, hazmat ? line.hazmatUnCode("2810") : line, null);
        }
        orders.validateOrder(orderId, null);
        List<UUID> ids = planning.generateConsignments(orderId, null).stream().map(IdResponse::getId).toList();
        Map<UUID, Consignment> byConsignee = new LinkedHashMap<>();
        for (UUID id : ids) {
            Consignment c = planning.getConsignment(id);
            byConsignee.put(consigneeIdOf(c, consignees), c);
        }
        return consignees.stream().map(byConsignee::get).toList();
    }

    private final Map<String, UUID> consigneeIds = new LinkedHashMap<>();

    private UUID consignee(String name) {
        return consigneeIds.computeIfAbsent(name, n -> asSetup(() -> fleet.registerPartner(orgUnitId,
                "K-" + consigneeIds.size() + "-" + salt, n, BusinessPartner.PartnerType.CUSTOMER,
                "TAX-K-" + consigneeIds.size() + "-" + salt)));
    }

    private UUID consigneeIdOf(Consignment c, List<UUID> candidates) {
        return consigneeIds.entrySet().stream()
                .filter(e -> e.getKey().equals(c.getConsigneeName()) && candidates.contains(e.getValue()))
                .map(Map.Entry::getValue).findFirst().orElseThrow();
    }

    private void board(UUID load, List<Consignment> batch) {
        planning.assignConsignmentsInBulk(load, new BulkAssignConsignmentsRequest()
                .consignmentIds(batch.stream().map(Consignment::getId).toList()), null);
    }

    private UUID openLoad(String loadNo) {
        return planning.openLoad(new OpenLoadRequest().orgUnitId(orgUnitId)
                .loadNo(loadNo + "-" + salt).originTerminalId(origin).vehicleId(lorry), null).getId();
    }

    private String etagOf(UUID load) {
        return header(planning.getLoadWithHttpInfo(load).getHeaders(), "etag");
    }

    private static String header(Map<String, Collection<String>> headers, String name) {
        return headers.entrySet().stream().filter(e -> e.getKey().equalsIgnoreCase(name))
                .flatMap(e -> e.getValue().stream()).findFirst().orElseThrow();
    }

    private static void assertProblem(Runnable call, int status, String code) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(FeignException.class, e -> {
            assertThat(e.status()).isEqualTo(status);
            JsonNode problem = JSON.readTree(e.contentUTF8());
            if (code != null) {
                assertThat(problem.get("code").asString()).isEqualTo(code);
            }
        });
    }

    private <T extends ApiClient.Api> T client(Class<T> api) {
        return client(api, PERMISSIONS);
    }

    private <T extends ApiClient.Api> T client(Class<T> api, List<String> permissions) {
        String token = tokens.issueAccessToken(planner, orgPath, permissions).value();
        ApiClient apiClient = new ApiClient().setBasePath("http://localhost:" + port);
        apiClient.getFeignBuilder().requestInterceptor(template ->
                template.header("Authorization", "Bearer " + token));
        return apiClient.buildClient(api);
    }

    private AppUser user(String email) {
        UUID id = fixtures.createUser(tenantId, orgUnitId, orgPath, email, "not-used-here");
        return new AppUser(id, tenantId, orgUnitId, email, "not-used", email,
                AppUser.UserStatus.ACTIVE, null, 0, null, null, null, Instant.now(), Instant.now());
    }

    private UUID partner(String code) {
        return fleet.registerPartner(orgUnitId, code, code, BusinessPartner.PartnerType.CUSTOMER,
                "TAX-" + code);
    }

    private UUID depot(String code, double lat, double lon) {
        return terminals.register(new RegisterTerminalCommand.PointRadius(orgUnitId, code, code,
                Terminal.FunctionalCategory.CUSTOMER_SITE, new LatLon(lat, lon), 500, 2, null,
                null, 45, List.of()));
    }

    /** Runs setup scoped to the tenant and signed in with what the setup needs. */
    private <T> T asSetup(Supplier<T> action) {
        Set<SimpleGrantedAuthority> authorities = SETUP.stream()
                .map(SimpleGrantedAuthority::new)
                .collect(Collectors.toSet());
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("setup", null, authorities));
        try {
            return TenantContext.callWith(tenantId, orgPath, action);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }
}
