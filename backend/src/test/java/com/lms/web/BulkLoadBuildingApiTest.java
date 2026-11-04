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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import com.lms.acceptance.TestFixtures;
import com.lms.client.ApiClient;
import com.lms.client.api.OrdersApi;
import com.lms.client.api.PlanningApi;
import com.lms.client.model.AddOrderLineRequest;
import com.lms.client.model.AssignConsignmentRequest;
import com.lms.client.model.BulkAssignConsignmentsRequest;
import com.lms.client.model.Consignment;
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
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * One load carrying a hundred-odd consignments, built over HTTP.
 *
 * <p>An order with 150 consignees becomes 150 consignments, found together by
 * their order in the planner's queue and put on one vehicle in one request.
 * Around that: a batch that cannot board names every consignment that
 * cannot, with its reasons, and leaves everything as it was; the batch's
 * total is what is weighed against the vehicle; and the request's limits are
 * validation failures, not refusals. Master data is set up in-process, as in
 * {@code FreightModeApiTest}; only the behaviour under test crosses the wire.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class BulkLoadBuildingApiTest {

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

    private static final int LARGE = 150;

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

    private String salt;
    private String orgPath;
    private UUID tenantId;
    private UUID orgUnitId;
    private AppUser planner;
    private UUID customer;
    private UUID origin;
    private UUID destination;
    private UUID lorry;
    private UUID smallLorry;

    @BeforeEach
    void aYard() {
        salt = UUID.randomUUID().toString().substring(0, 8);
        orgPath = "/bulk-" + salt + "/";
        tenantId = fixtures.createTenant("bulk-" + salt, "Bulk Freight");
        orgUnitId = fixtures.createOrgUnit(tenantId, orgPath, "Bulk HQ", "HQ", null);
        planner = user("planner@bulk.test");

        asSetup(() -> {
            customer = partner("CUST-" + salt);
            origin = depot("ORIGIN-" + salt, 33.10, 80.10);
            destination = depot("DEST-" + salt, 33.40, 80.40);
            lorry = fleet.registerVehicle(orgUnitId, null, "BK-30-" + salt, "TRUCK", "RIGID",
                    "3-AXLE", new BigDecimal("36000"), new BigDecimal("6000"),
                    new BigDecimal("90"), false, false);
            smallLorry = fleet.registerVehicle(orgUnitId, null, "BK-10-" + salt, "TRUCK", "RIGID",
                    "2-AXLE", new BigDecimal("16000"), new BigDecimal("6000"),
                    new BigDecimal("40"), false, false);
            return null;
        });
    }

    @AfterEach
    void signOut() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("150 consignments of one order are found together and put on one load in one request")
    void aHundredAndFiftyOnOneLoad() {
        OrdersApi orders = client(OrdersApi.class);
        PlanningApi planning = client(PlanningApi.class);

        List<String> classes = new ArrayList<>();
        for (int i = 0; i < LARGE; i++) {
            classes.add("GENERAL");
        }
        UUID orderId = order(orders, "SO-150", classes, "100");
        assertThat(planning.generateConsignments(orderId, null)).hasSize(LARGE);

        // The queue narrowed to the order holds exactly its consignments.
        List<Consignment> queue = planning.listUnassignedConsignments(null, 200, orderId, null)
                .getItems();
        assertThat(queue).hasSize(LARGE).allMatch(c -> "PLANNED".equals(c.getStatus()));
        assertThat(planning.listUnassignedConsignments(null, 200, UUID.randomUUID(), null).getItems())
                .isEmpty();

        // Board them in reverse queue order, to prove the order given is kept.
        List<UUID> boarding = new ArrayList<>(queue.stream().map(Consignment::getId).toList());
        java.util.Collections.reverse(boarding);
        UUID load = openLoad(planning, "LD-150", lorry);
        planning.assignConsignmentsInBulk(load,
                new BulkAssignConsignmentsRequest().consignmentIds(boarding), null);

        List<Consignment> aboard = planning.listLoadConsignments(load);
        assertThat(aboard).extracting(Consignment::getId).containsExactlyElementsOf(boarding);
        assertThat(aboard).allMatch(c -> "ASSIGNED_TO_LOAD".equals(c.getStatus()));
        Load built = planning.getLoad(load);
        assertThat(built.getConsignmentCount()).isEqualTo(LARGE);
        assertThat(built.getPlannedWeightKg()).isEqualByComparingTo("15000");
        assertThat(planning.listUnassignedConsignments(null, 200, orderId, null).getItems()).isEmpty();

        planning.planLoad(load, null);
        assertThat(planning.getLoad(load).getStatus()).isEqualTo("PLANNED");
    }

    @Test
    @DisplayName("a batch that cannot board names every offender and its reasons, and boards nothing")
    void everyRefusalAtOnceAndNothingBoards() {
        OrdersApi orders = client(OrdersApi.class);
        PlanningApi planning = client(PlanningApi.class);

        UUID mixed = order(orders, "SO-MIX",
                List.of("FOOD", "GENERAL", "GENERAL", "FOOD", "FOOD"), "500");
        planning.generateConsignments(mixed, null);
        Map<String, Consignment> byConsignee = byConsignee(planning, mixed);
        Consignment taken = byConsignee.get("Consignee 1");
        Consignment general = byConsignee.get("Consignee 2");
        Consignment generalToo = byConsignee.get("Consignee 3");
        Consignment foodAboard = byConsignee.get("Consignee 4");
        Consignment foodLate = byConsignee.get("Consignee 5");

        UUID poisonOrder = orders.raiseOrder(header("SO-TOX"), null).getId();
        orders.addOrderLine(poisonOrder, line(1, "TOXIC", "300", consignee("Poison")).hazmatUnCode("2810"), null);
        orders.validateOrder(poisonOrder, null);
        UUID poisonId = planning.generateConsignments(poisonOrder, null).get(0).getId();
        Consignment poison = planning.getConsignment(poisonId);

        UUID elsewhere = openLoad(planning, "LD-A", smallLorry);
        planning.assignConsignment(elsewhere, new AssignConsignmentRequest().consignmentId(taken.getId()), null);
        UUID load = openLoad(planning, "LD-B", lorry);
        planning.assignConsignment(load, new AssignConsignmentRequest().consignmentId(foodAboard.getId()), null);

        List<UUID> batch = List.of(general.getId(), taken.getId(), poisonId, foodLate.getId(),
                generalToo.getId());
        JsonNode problem = conflict(() -> planning.assignConsignmentsInBulk(load,
                new BulkAssignConsignmentsRequest().consignmentIds(batch), null));

        assertThat(problem.get("code").asString()).isEqualTo("load-bulk-assignment-refused");
        assertThat(problem.get("detail").asString()).contains("cannot take 3 of the 5");
        Map<String, String> errors = new LinkedHashMap<>();
        problem.get("errors").properties().forEach(e -> errors.put(e.getKey(), e.getValue().asString()));
        assertThat(errors.keySet()).containsExactly(taken.getTrackingRef(), poison.getTrackingRef(),
                foodLate.getTrackingRef());
        assertThat(errors.get(taken.getTrackingRef())).startsWith("consignment-not-assignable: ")
                .contains("ASSIGNED_TO_LOAD");
        // The two that clash within the batch are both named, each pointing at the other.
        assertThat(errors.get(poison.getTrackingRef()))
                .contains("load-not-hazmat-certified: ")
                .contains("load-incompatible-materials: TOXIC in " + poison.getTrackingRef()
                        + " must not travel with FOOD already on " + foodAboard.getTrackingRef())
                .contains("load-incompatible-materials: TOXIC in " + poison.getTrackingRef()
                        + " must not travel with FOOD in " + foodLate.getTrackingRef());
        assertThat(errors.get(foodLate.getTrackingRef()))
                .isEqualTo("load-incompatible-materials: FOOD in " + foodLate.getTrackingRef()
                        + " must not travel with TOXIC in " + poison.getTrackingRef());

        // Nothing boarded: the load holds what it held, the rest still wait.
        assertThat(planning.listLoadConsignments(load)).extracting(Consignment::getId)
                .containsExactly(foodAboard.getId());
        assertThat(planning.getLoad(load).getPlannedWeightKg()).isEqualByComparingTo("500");
        assertThat(planning.getConsignment(general.getId()).getStatus())
                .isEqualTo("PLANNED");
        assertThat(planning.getConsignment(poisonId).getStatus())
                .isEqualTo("PLANNED");

        // The clean part of the batch boards after what is already aboard.
        planning.assignConsignmentsInBulk(load, new BulkAssignConsignmentsRequest()
                .consignmentIds(List.of(generalToo.getId(), general.getId())), null);
        assertThat(planning.listLoadConsignments(load)).extracting(Consignment::getId)
                .containsExactly(foodAboard.getId(), generalToo.getId(), general.getId());
        assertThat(planning.getLoad(load).getPlannedWeightKg()).isEqualByComparingTo("1500");
    }

    @Test
    @DisplayName("the batch's total is weighed against the vehicle, with the single-assignment code")
    void theBatchTotalMustFit() {
        OrdersApi orders = client(OrdersApi.class);
        PlanningApi planning = client(PlanningApi.class);

        UUID heavy = order(orders, "SO-HVY", List.of("GENERAL", "GENERAL"), "6000");
        List<UUID> both = planning.generateConsignments(heavy, null).stream()
                .map(com.lms.client.model.IdResponse::getId).toList();
        UUID load = openLoad(planning, "LD-S", smallLorry);

        JsonNode problem = conflict(() -> planning.assignConsignmentsInBulk(load,
                new BulkAssignConsignmentsRequest().consignmentIds(both), null));
        assertThat(problem.get("code").asString()).isEqualTo("load-capacity-exceeded");
        assertThat(problem.get("detail").asString()).contains("12000");
        assertThat(planning.listLoadConsignments(load)).isEmpty();
        assertThat(planning.getConsignment(both.get(0)).getStatus())
                .isEqualTo("PLANNED");

        // Either one alone fits.
        planning.assignConsignmentsInBulk(load,
                new BulkAssignConsignmentsRequest().consignmentIds(both.subList(0, 1)), null);
        assertThat(planning.getLoad(load).getConsignmentCount()).isEqualTo(1);
        assertThat(planning.getLoad(load).getPlannedWeightKg()).isEqualByComparingTo("6000");
    }

    @Test
    @DisplayName("an empty, oversized or repeating batch is a validation failure; an unknown id is not found")
    void limits() {
        OrdersApi orders = client(OrdersApi.class);
        PlanningApi planning = client(PlanningApi.class);
        UUID orderId = order(orders, "SO-LIM", List.of("GENERAL"), "100");
        UUID one = planning.generateConsignments(orderId, null).get(0).getId();
        UUID load = openLoad(planning, "LD-L", lorry);

        assertStatus(() -> planning.assignConsignmentsInBulk(load,
                new BulkAssignConsignmentsRequest().consignmentIds(List.of()), null),
                400, "validation-failed");
        List<UUID> tooMany = new ArrayList<>();
        for (int i = 0; i < 501; i++) {
            tooMany.add(UUID.randomUUID());
        }
        assertStatus(() -> planning.assignConsignmentsInBulk(load,
                new BulkAssignConsignmentsRequest().consignmentIds(tooMany), null),
                400, "validation-failed");
        assertStatus(() -> planning.assignConsignmentsInBulk(load,
                new BulkAssignConsignmentsRequest().consignmentIds(List.of(one, one)), null),
                400, "validation-failed");

        UUID unknown = UUID.randomUUID();
        assertStatus(() -> planning.assignConsignmentsInBulk(load,
                new BulkAssignConsignmentsRequest().consignmentIds(List.of(one, unknown)), null),
                404, "not-found");
        assertStatus(() -> planning.assignConsignmentsInBulk(UUID.randomUUID(),
                new BulkAssignConsignmentsRequest().consignmentIds(List.of(one)), null),
                404, "not-found");
        assertThat(planning.getConsignment(one).getStatus()).isEqualTo("PLANNED");

        PlanningApi reader = client(PlanningApi.class, List.of("LOAD_READ"));
        assertStatus(() -> reader.assignConsignmentsInBulk(load,
                new BulkAssignConsignmentsRequest().consignmentIds(List.of(one)), null),
                403, "access-denied");
    }

    // --------------------------------------------------------------- helpers

    /** An order with one line per class, each to its own consignee, so one consignment each. */
    private UUID order(OrdersApi orders, String orderNo, List<String> classes, String weightKg) {
        UUID orderId = orders.raiseOrder(header(orderNo), null).getId();
        for (int i = 0; i < classes.size(); i++) {
            orders.addOrderLine(orderId,
                    line(i + 1, classes.get(i), weightKg, consignee("Consignee " + (i + 1))), null);
        }
        orders.validateOrder(orderId, null);
        return orderId;
    }

    private RaiseOrderRequest header(String orderNo) {
        return new RaiseOrderRequest().orgUnitId(orgUnitId).customerPartnerId(customer)
                .orderNo(orderNo + "-" + salt).originTerminalId(origin);
    }

    private AddOrderLineRequest line(int lineNo, String materialClass, String weightKg,
                                     UUID consignee) {
        return new AddOrderLineRequest().lineNo(lineNo).materialCode("MAT-" + lineNo)
                .materialDescription(materialClass + " cargo").materialClass(materialClass)
                .quantity(BigDecimal.ONE).uom("EA").deadWeightKg(new BigDecimal(weightKg))
                .consigneePartnerId(consignee).destinationTerminalId(destination);
    }

    private final Map<String, UUID> consignees = new LinkedHashMap<>();

    /** A consignee by name, registered the first time it is asked for. */
    private UUID consignee(String name) {
        return consignees.computeIfAbsent(name, n -> asSetup(() -> fleet.registerPartner(orgUnitId,
                "K-" + consignees.size() + "-" + salt, n, BusinessPartner.PartnerType.CUSTOMER,
                "TAX-K-" + consignees.size() + "-" + salt)));
    }

    private Map<String, Consignment> byConsignee(PlanningApi planning, UUID orderId) {
        return planning.listUnassignedConsignments(null, 200, orderId, null).getItems().stream()
                .collect(Collectors.toMap(Consignment::getConsigneeName, c -> c));
    }

    private UUID openLoad(PlanningApi planning, String loadNo, UUID vehicle) {
        return planning.openLoad(new OpenLoadRequest().orgUnitId(orgUnitId)
                .loadNo(loadNo + "-" + salt).originTerminalId(origin).vehicleId(vehicle), null).getId();
    }

    private static JsonNode conflict(Runnable call) {
        FeignException[] thrown = new FeignException[1];
        assertThatThrownBy(call::run).isInstanceOfSatisfying(FeignException.class, e -> {
            assertThat(e.status()).isEqualTo(409);
            thrown[0] = e;
        });
        return JSON.readTree(thrown[0].contentUTF8());
    }

    private static void assertStatus(Runnable call, int status, String code) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(FeignException.class, e -> {
            assertThat(e.status()).isEqualTo(status);
            assertThat(JSON.readTree(e.contentUTF8()).get("code").asString()).isEqualTo(code);
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
