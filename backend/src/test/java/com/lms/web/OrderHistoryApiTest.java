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
import java.time.Duration;
import java.time.Instant;
import java.time.Year;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import com.lms.acceptance.TestFixtures;
import com.lms.client.ApiClient;
import com.lms.client.api.OrdersApi;
import com.lms.client.api.PlanningApi;
import com.lms.client.api.TripsApi;
import com.lms.client.model.AddOrderLineRequest;
import com.lms.client.model.AmendOrderRequest;
import com.lms.client.model.CancelOrderRequest;
import com.lms.client.model.HistoryEntry;
import com.lms.client.model.HistorySlice;
import com.lms.client.model.OrderSlice;
import com.lms.client.model.RaiseOrderRequest;
import com.lms.client.model.UpdateOrderRequest;
import com.lms.identity.access.SubjectAttributes;
import com.lms.identity.command.domain.AppUser;
import com.lms.identity.security.TokenService;
import com.lms.masterdata.command.FleetCommandService;
import com.lms.masterdata.command.RegisterTerminalCommand;
import com.lms.masterdata.command.TerminalCommandService;
import com.lms.order.command.OrderNumberSequence;
import com.lms.masterdata.command.domain.BusinessPartner;
import com.lms.masterdata.command.domain.Terminal;
import com.lms.shared.geo.LatLon;
import com.lms.shared.tenant.TenantContext;
import com.lms.web.ApiTestKit.Outcome;
import com.lms.web.ApiTestKit.Tenant;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Order numbering, lookup by number, lifecycle events and record history over
 * HTTP, through the client generated from the contract.
 *
 * <p>What only the wire shows: that an omitted number comes back assigned, that
 * concurrent raises never share one, that the history endpoints page by cursor
 * and answer another tenant with the platform's {@code not-found}, and that
 * each lifecycle step reaches the publication registry.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OrderHistoryApiTest {

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

    private static final String[] PERMISSIONS = {"ORDER_CREATE", "ORDER_READ", "ORDER_UPDATE",
            "LOAD_CREATE", "LOAD_READ"};

    /** What the in-process setup needs. Never put in a token. */
    private static final List<String> SETUP = List.of("PARTNER_CREATE", "PARTNER_UPDATE", "TERMINAL_CREATE");

    /** A tenant with somewhere to raise orders from and deliver them to. */
    private record Network(Tenant tenant, UUID customer, UUID consignee, UUID origin, UUID destination) {
    }

    @LocalServerPort
    private int port;

    @Autowired
    private TestFixtures fixtures;
    @Autowired
    private TokenService tokens;
    @Autowired
    private FleetCommandService fleet;
    @Autowired
    private TerminalCommandService terminals;
    @Autowired
    private JdbcClient jdbc;
    @Autowired
    private OrderNumberSequence sequence;
    @Autowired
    private PlatformTransactionManager transactionManager;

    private ApiTestKit kit;
    private Network acme;

    @BeforeEach
    void aNetwork() {
        kit = new ApiTestKit(fixtures, tokens, port);
        acme = network("acme");
    }

    @AfterEach
    void signOut() {
        SecurityContextHolder.clearContext();
    }

    // -------------------------------------------------------------- numbering

    @Test
    @DisplayName("an order raised without a number is given the tenant's next, and each tenant counts its own")
    void assignedNumbers() {
        OrdersApi orders = client(acme, OrdersApi.class);
        UUID first = orders.raiseOrder(raise(acme, null), null).getId();
        UUID second = orders.raiseOrder(raise(acme, "  "), null).getId();
        UUID stated = orders.raiseOrder(raise(acme, "SO-STATED-1"), null).getId();

        assertThat(orders.getOrder(first).getOrderNo()).isEqualTo(assigned(1));
        assertThat(orders.getOrder(second).getOrderNo()).isEqualTo(assigned(2));
        assertThat(orders.getOrder(stated).getOrderNo()).isEqualTo("SO-STATED-1");

        Network globex = network("globex");
        UUID theirs = client(globex, OrdersApi.class).raiseOrder(raise(globex, null), null).getId();
        assertThat(client(globex, OrdersApi.class).getOrder(theirs).getOrderNo()).isEqualTo(assigned(1));

        assertConflict(() -> orders.raiseOrder(raise(acme, "SO-STATED-1"), null), "order-no-taken");
    }

    /**
     * Four at once over HTTP, not twelve: each successful raise publishes an
     * event, and the publication registry marks its after-commit listener in a
     * transaction of its own while the request still holds its connection.
     * With the free tier's pool of five, five or more raises committing at the
     * same instant wait on each other for the pool's timeout. The counter itself
     * is raced harder, without HTTP, in the next test.
     */
    @Test
    @DisplayName("concurrent raises without a number over HTTP get distinct, consecutive numbers")
    void concurrentAssignedNumbersAreDistinct() {
        String body = raiseBody(acme, null);
        List<Callable<Outcome>> calls = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            calls.add(() -> kit.post(acme.tenant(), "/api/v1/orders", body, PERMISSIONS));
        }
        List<Outcome> outcomes = ApiTestKit.race(calls, Duration.ofSeconds(60));

        assertThat(outcomes).extracting(Outcome::status).containsOnly(201);
        OrdersApi orders = client(acme, OrdersApi.class);
        Set<String> numbers = outcomes.stream()
                .map(o -> orders.getOrder(UUID.fromString(kit.parse(o).get("id").asString())).getOrderNo())
                .collect(Collectors.toSet());
        assertThat(numbers).containsExactlyInAnyOrder(assigned(1), assigned(2), assigned(3), assigned(4));
    }

    @Test
    @DisplayName("fifty transactions drawing from one tenant's counter at once draw fifty distinct values")
    void counterIsSafeUnderContention() {
        UUID tenantId = acme.tenant().tenantId();
        TransactionTemplate transactions = new TransactionTemplate(transactionManager);
        List<Callable<String>> calls = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            calls.add(() -> TenantContext.callWith(tenantId, acme.tenant().orgPath(),
                    () -> transactions.execute(status -> sequence.next(tenantId))));
        }
        List<String> drawn = ApiTestKit.race(calls, Duration.ofSeconds(120));

        assertThat(drawn).doesNotHaveDuplicates().hasSize(50);
        for (int i = 1; i <= 50; i++) {
            assertThat(drawn).contains(assigned(i));
        }
    }

    @Test
    @DisplayName("twelve concurrent raises under one stated number: one order, every loser a 409")
    void concurrentStatedNumberHasOneWinner() {
        String body = raiseBody(acme, "SO-RACE-1");
        List<Callable<Outcome>> calls = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            calls.add(() -> kit.post(acme.tenant(), "/api/v1/orders", body, PERMISSIONS));
        }
        List<Outcome> outcomes = ApiTestKit.race(calls, Duration.ofSeconds(60));

        assertThat(outcomes).filteredOn(o -> o.status() == 201).hasSize(1);
        assertThat(outcomes).filteredOn(o -> o.status() != 201)
                .allSatisfy(o -> {
                    assertThat(o.status()).isEqualTo(409);
                    assertThat(o.code()).isIn("order-no-taken", "duplicate-resource");
                });
        assertThat(client(acme, OrdersApi.class).getOrderByNumber("SO-RACE-1").getOrderNo())
                .isEqualTo("SO-RACE-1");
    }

    // ----------------------------------------------------------------- lookup

    @Test
    @DisplayName("an order is found by its exact number, and only by its own tenant")
    void lookupByNumber() {
        OrdersApi orders = client(acme, OrdersApi.class);
        UUID id = orders.raiseOrder(raise(acme, "SO-LOOK-1"), null).getId();

        assertThat(orders.getOrderByNumber("SO-LOOK-1").getId()).isEqualTo(id);
        assertNotFound(() -> orders.getOrderByNumber("SO-LOOK"));
        assertNotFound(() -> orders.getOrderByNumber("so-look-1"));

        Network globex = network("globex");
        assertNotFound(() -> client(globex, OrdersApi.class).getOrderByNumber("SO-LOOK-1"));
    }

    // ---------------------------------------------------------------- history

    @Test
    @DisplayName("an order's history reads newest first, pages by cursor and is a 404 to another tenant")
    void orderHistory() {
        OrdersApi orders = client(acme, OrdersApi.class);
        UUID id = orders.raiseOrder(raise(acme, null), null).getId();
        orders.addOrderLine(id, line(acme, 1, "1000"), null);
        orders.validateOrder(id, null);
        orders.amendOrder(String.valueOf(orders.getOrder(id).getVersion()), id, new AmendOrderRequest()
                .header(new UpdateOrderRequest().customerPartnerId(acme.customer())
                        .originTerminalId(acme.origin()))
                .lines(List.of(line(acme, 1, "1100"))), null);
        orders.cancelOrder(id, null, new CancelOrderRequest().reason("customer withdrew"));

        HistorySlice all = orders.getOrderHistory(id, null, null);
        assertThat(all.getItems()).extracting(HistoryEntry::getAction)
                .containsExactly("ORDER_CANCELLED", "ORDER_AMENDED", "ORDER_VALIDATED", "ORDER_RAISED");
        assertThat(all.getHasMore()).isFalse();
        assertThat(all.getNextCursor()).isNull();
        HistoryEntry cancelled = all.getItems().getFirst();
        assertThat(cancelled.getBefore()).containsEntry("status", "VALIDATED");
        assertThat(cancelled.getAfter()).containsEntry("status", "CANCELLED")
                .containsEntry("reason", "customer withdrew");
        assertThat(all.getItems().getLast().getBefore()).isNull();

        List<String> walked = new ArrayList<>();
        String cursor = null;
        do {
            HistorySlice page = orders.getOrderHistory(id, cursor, 3);
            assertThat(page.getItems().size()).isLessThanOrEqualTo(3);
            page.getItems().forEach(e -> walked.add(e.getAction()));
            cursor = page.getNextCursor();
        } while (cursor != null);
        assertThat(walked).containsExactly("ORDER_CANCELLED", "ORDER_AMENDED", "ORDER_VALIDATED",
                "ORDER_RAISED");

        assertConflict(() -> orders.getOrderHistory(id, "forged", null), "invalid-cursor");
        assertNotFound(() -> orders.getOrderHistory(UUID.randomUUID(), null, null));
        Network globex = network("globex");
        assertNotFound(() -> client(globex, OrdersApi.class).getOrderHistory(id, null, null));
    }

    @Test
    @DisplayName("a portal customer reads its order's history without the names of the tenant's staff")
    void portalReaderIsNotToldStaffNames() {
        Tenant tenant = acme.tenant();
        UUID staffId = fixtures.createUser(tenant.tenantId(), tenant.orgUnitId(), tenant.orgPath(),
                "planner-" + tenant.tenantId() + "@acme.test", "not-used-here");
        UUID portalId = fixtures.createUser(tenant.tenantId(), tenant.orgUnitId(), tenant.orgPath(),
                "buyer-" + tenant.tenantId() + "@customer.test", "not-used-here");
        String staffToken = tokens.issueAccessToken(appUser(tenant, staffId), tenant.orgPath(),
                List.of(PERMISSIONS)).value();
        String portalToken = tokens.issueAccessToken(appUser(tenant, portalId), tenant.orgPath(),
                List.of("ORDER_READ", "ORDER_UPDATE"),
                new SubjectAttributes(null, null, acme.customer(), null)).value();

        OrdersApi staff = client(staffToken, OrdersApi.class);
        UUID id = staff.raiseOrder(raise(acme, null), null).getId();
        staff.addOrderLine(id, line(acme, 1, "1000"), null);
        staff.validateOrder(id, null);
        OrdersApi portal = client(portalToken, OrdersApi.class);
        portal.cancelOrder(id, null, new CancelOrderRequest().reason("no longer needed"));

        List<HistoryEntry> seenByStaff = staff.getOrderHistory(id, null, null).getItems();
        assertThat(seenByStaff).extracting(HistoryEntry::getAction)
                .containsExactly("ORDER_CANCELLED", "ORDER_VALIDATED", "ORDER_RAISED");
        assertThat(seenByStaff.get(1).getActorName()).startsWith("planner-");
        assertThat(seenByStaff.get(1).getActorId()).isEqualTo(staffId);
        assertThat(seenByStaff.get(0).getActorName()).startsWith("buyer-");

        List<HistoryEntry> seenByPortal = portal.getOrderHistory(id, null, null).getItems();
        assertThat(seenByPortal).extracting(HistoryEntry::getAction)
                .containsExactly("ORDER_CANCELLED", "ORDER_VALIDATED", "ORDER_RAISED");
        assertThat(seenByPortal).extracting(HistoryEntry::getActorName).containsOnlyNulls();
        assertThat(seenByPortal).extracting(e -> e.getActorKind().getValue())
                .containsExactly("YOU", "USER", "USER");
        assertThat(seenByPortal.get(0).getActorId()).isEqualTo(portalId);
        assertThat(seenByPortal.get(1).getActorId()).isNull();
        assertThat(seenByPortal.get(2).getActorId()).isNull();
        assertThat(seenByPortal.get(0).getAfter()).containsEntry("reason", "no longer needed");
        assertThat(portal.getOrderHistory(id, null, null).toString()).doesNotContain("planner-");
    }

    @Test
    @DisplayName("a consignment's history reads back, and an unknown load or trip is a 404")
    void otherRecordsHistory() {
        OrdersApi orders = client(acme, OrdersApi.class);
        PlanningApi planning = client(acme, PlanningApi.class);
        UUID id = orders.raiseOrder(raise(acme, null), null).getId();
        orders.addOrderLine(id, line(acme, 1, "1000"), null);
        orders.validateOrder(id, null);
        UUID consignment = planning.generateConsignments(id, null).getFirst().getId();

        assertThat(planning.getConsignmentHistory(consignment, null, null).getItems())
                .extracting(HistoryEntry::getAction).containsExactly("CONSIGNMENT_GENERATED");
        assertThat(orders.getOrderHistory(id, null, 1).getItems())
                .extracting(HistoryEntry::getAction).containsExactly("ORDER_FULLY_PLANNED");

        assertNotFound(() -> planning.getLoadHistory(UUID.randomUUID(), null, null));
        assertNotFound(() -> client(acme, TripsApi.class).getTripHistory(UUID.randomUUID(), null, null));
        Network globex = network("globex");
        assertNotFound(() -> client(globex, PlanningApi.class).getConsignmentHistory(consignment, null, null));
    }

    // ----------------------------------------------------------------- events

    @Test
    @DisplayName("raising, amending, cancelling an order each reach the publication registry once")
    void lifecycleEventsArePublished() {
        OrdersApi orders = client(acme, OrdersApi.class);
        UUID id = orders.raiseOrder(raise(acme, null), null).getId();
        orders.addOrderLine(id, line(acme, 1, "1000"), null);
        orders.validateOrder(id, null);
        orders.amendOrder(String.valueOf(orders.getOrder(id).getVersion()), id, new AmendOrderRequest()
                .lines(List.of(line(acme, 1, "1200"))), null);
        orders.cancelOrder(id, null, new CancelOrderRequest());
        orders.cancelOrder(id, null, new CancelOrderRequest());

        List<String> types = jdbc.sql("""
                        SELECT DISTINCT ON (serialized_event) event_type
                          FROM event_publication
                         WHERE serialized_event LIKE :id
                         ORDER BY serialized_event, publication_date
                        """)
                .param("id", "%" + id + "%")
                .query(String.class).list();
        assertThat(types).extracting(t -> t.substring(t.lastIndexOf('.') + 1))
                .containsExactlyInAnyOrder("OrderRaised", "OrderValidated", "OrderAmended", "OrderCancelled");
    }

    // ------------------------------------------------------- listing and lines

    @Test
    @DisplayName("orders page two at a time by number, each once, the last page offering no cursor")
    void orderListingPages() {
        OrdersApi orders = client(acme, OrdersApi.class);
        List<UUID> raised = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            raised.add(orders.raiseOrder(raise(acme, null), null).getId());
        }
        List<UUID> seen = new ArrayList<>();
        List<String> numbers = new ArrayList<>();
        String cursor = null;
        int pages = 0;
        do {
            OrderSlice page = orders.listOrders(null, cursor, 2, null);
            pages++;
            assertThat(page.getItems().size()).isBetween(1, 2);
            page.getItems().forEach(o -> {
                seen.add(o.getId());
                numbers.add(o.getOrderNo());
            });
            cursor = page.getNextCursor();
            assertThat(page.getHasMore()).isEqualTo(cursor != null);
        } while (cursor != null);

        assertThat(pages).isEqualTo(3);
        assertThat(seen).doesNotHaveDuplicates().containsExactlyInAnyOrderElementsOf(raised);
        assertThat(numbers).isSorted();
    }

    @Test
    @DisplayName("a line of zero quantity is a 400; the smallest positive quantity is accepted")
    void zeroQuantityRefused() {
        OrdersApi orders = client(acme, OrdersApi.class);
        UUID id = orders.raiseOrder(raise(acme, null), null).getId();

        assertThatThrownBy(() -> orders.addOrderLine(id, line(acme, 1, "100").quantity(BigDecimal.ZERO), null))
                .isInstanceOfSatisfying(FeignException.class, e -> {
                    assertThat(e.status()).isEqualTo(400);
                    assertThat(e.contentUTF8()).contains("validation-failed");
                });
        assertThatThrownBy(() -> orders.addOrderLine(id, line(acme, 1, "100")
                .quantity(new BigDecimal("-1")), null))
                .isInstanceOfSatisfying(FeignException.class, e -> assertThat(e.status()).isEqualTo(400));
        assertThat(orders.listOrderLines(id)).isEmpty();

        orders.addOrderLine(id, line(acme, 1, "100").quantity(new BigDecimal("0.001")), null);
        assertThat(orders.listOrderLines(id)).singleElement()
                .satisfies(l -> assertThat(l.getQuantity()).isEqualByComparingTo("0.001"));
    }

    // ---------------------------------------------------------------- helpers

    private static String assigned(int counter) {
        return "ORD-%d-%06d".formatted(Year.now(ZoneOffset.UTC).getValue(), counter);
    }

    private static RaiseOrderRequest raise(Network network, String orderNo) {
        return new RaiseOrderRequest().orgUnitId(network.tenant().orgUnitId())
                .customerPartnerId(network.customer()).orderNo(orderNo).originTerminalId(network.origin());
    }

    private static String raiseBody(Network network, String orderNo) {
        return """
                {"orgUnitId":"%s","customerPartnerId":"%s","originTerminalId":"%s"%s}"""
                .formatted(network.tenant().orgUnitId(), network.customer(), network.origin(),
                        orderNo == null ? "" : ",\"orderNo\":\"" + orderNo + "\"");
    }

    private static AddOrderLineRequest line(Network network, int lineNo, String weightKg) {
        return new AddOrderLineRequest().lineNo(lineNo).materialCode("MAT-" + lineNo)
                .materialDescription("General cargo").materialClass("GENERAL")
                .quantity(BigDecimal.ONE).uom("EA").deadWeightKg(new BigDecimal(weightKg))
                .consigneePartnerId(network.consignee()).destinationTerminalId(network.destination());
    }

    private static void assertConflict(Runnable call, String code) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(FeignException.class, e -> {
            assertThat(e.status()).isEqualTo(409);
            assertThat(e.contentUTF8()).contains(code);
        });
    }

    private static void assertNotFound(Runnable call) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(FeignException.class, e -> {
            assertThat(e.status()).isEqualTo(404);
            assertThat(e.contentUTF8()).contains("not-found");
        });
    }

    private <T extends ApiClient.Api> T client(Network network, Class<T> api) {
        return client(kit.token(network.tenant(), PERMISSIONS), api);
    }

    private <T extends ApiClient.Api> T client(String token, Class<T> api) {
        ApiClient apiClient = new ApiClient().setBasePath("http://localhost:" + port);
        apiClient.getFeignBuilder().requestInterceptor(template ->
                template.header("Authorization", "Bearer " + token));
        return apiClient.buildClient(api);
    }

    private static AppUser appUser(Tenant tenant, UUID id) {
        return new AppUser(id, tenant.tenantId(), tenant.orgUnitId(), id + "@users.test", "not-used",
                "n/a", AppUser.UserStatus.ACTIVE, null, 0, null, null, null, Instant.now(), Instant.now());
    }

    private Network network(String prefix) {
        Tenant tenant = kit.newTenant(prefix);
        return asSetup(tenant, () -> new Network(tenant,
                partner(tenant, "CUST"), partner(tenant, "CONS"),
                depot(tenant, "ORG", 19.10, 72.90), depot(tenant, "DST", 28.40, 77.00)));
    }

    private UUID partner(Tenant tenant, String code) {
        return fleet.registerPartner(tenant.orgUnitId(), code, code, BusinessPartner.PartnerType.CUSTOMER,
                "TAX-" + code + "-" + tenant.tenantId().toString().substring(0, 8));
    }

    private UUID depot(Tenant tenant, String code, double lat, double lon) {
        return terminals.register(new RegisterTerminalCommand.PointRadius(tenant.orgUnitId(), code, code,
                Terminal.FunctionalCategory.CUSTOMER_SITE, new LatLon(lat, lon), 500, 2, null,
                null, 45, List.of()));
    }

    /** Runs setup scoped to the tenant and signed in with what the setup needs. */
    private <T> T asSetup(Tenant tenant, Supplier<T> action) {
        Set<SimpleGrantedAuthority> authorities = SETUP.stream()
                .map(SimpleGrantedAuthority::new)
                .collect(Collectors.toSet());
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("setup", "n/a", authorities));
        try {
            return TenantContext.callWith(tenant.tenantId(), tenant.orgPath(), action);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }
}
