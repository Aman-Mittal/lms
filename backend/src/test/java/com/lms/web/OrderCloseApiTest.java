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
import com.lms.client.ApiClient;
import com.lms.client.api.OrdersApi;
import com.lms.client.api.PlanningApi;
import com.lms.client.model.AddOrderLineRequest;
import com.lms.client.model.CancelOrderRequest;
import com.lms.client.model.CloseKind;
import com.lms.client.model.CloseOrderRequest;
import com.lms.client.model.HistoryEntry;
import com.lms.client.model.Order;
import com.lms.client.model.OrderCloseSettings;
import com.lms.client.model.OrderLine;
import com.lms.client.model.RaiseOrderRequest;
import com.lms.client.model.SetOrderCloseSettingsRequest;
import com.lms.identity.security.TokenService;
import com.lms.masterdata.command.FleetCommandService;
import com.lms.masterdata.command.RegisterTerminalCommand;
import com.lms.masterdata.command.TerminalCommandService;
import com.lms.masterdata.command.domain.BusinessPartner;
import com.lms.masterdata.command.domain.Terminal;
import com.lms.shared.geo.LatLon;
import com.lms.shared.tenant.TenantContext;
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
import org.testcontainers.containers.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Closing an order, closing one line, and the tenant's close policy, over
 * HTTP through the client generated from the contract.
 *
 * <p>What only the wire shows: each refusal's status and code, that the close
 * is readable back on the order, its lines and its history, that a forced
 * close and the policy are held to {@code ORDER_FORCE_CLOSE}, that another
 * tenant's order is the platform's {@code not-found}, and that the close
 * reaches the publication registry.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OrderCloseApiTest {

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

    /** An order clerk: everything about orders except forcing a close. */
    private static final String[] CLERK = {"ORDER_CREATE", "ORDER_READ", "ORDER_UPDATE",
            "LOAD_CREATE", "LOAD_READ"};

    /** A clerk who may also force a close and set the policy. */
    private static final String[] SUPERVISOR = {"ORDER_CREATE", "ORDER_READ", "ORDER_UPDATE",
            "LOAD_CREATE", "LOAD_READ", "ORDER_FORCE_CLOSE"};

    /** What the in-process setup needs. Never put in a token. */
    private static final List<String> SETUP = List.of("PARTNER_CREATE", "PARTNER_UPDATE", "TERMINAL_CREATE");

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

    @Test
    @DisplayName("a validated order refuses a normal close, then short-closes with a reason and reads back closed")
    void shortClose() {
        OrdersApi orders = client(acme, CLERK);
        UUID id = validatedOrder(orders, 1);

        assertConflict(() -> orders.closeOrder(id, null, new CloseOrderRequest()), "order-not-complete");
        assertConflict(() -> orders.closeOrder(id, null, new CloseOrderRequest().kind(CloseKind.SHORT)),
                "close-reason-required");
        assertStatus(() -> orders.closeOrder(id, null, new CloseOrderRequest().kind(CloseKind.AUTO)
                .reason("the platform's own")), 400, "invalid-argument");

        orders.closeOrder(id, null, new CloseOrderRequest().kind(CloseKind.SHORT).reason("customer needs no more"));

        Order closed = orders.getOrder(id);
        assertThat(closed.getStatus()).isEqualTo("CLOSED");
        assertThat(closed.getCloseKind()).isEqualTo(CloseKind.SHORT);
        assertThat(closed.getCloseReason()).isEqualTo("customer needs no more");
        assertThat(closed.getClosedAt()).isNotNull();
        assertThat(orders.listOrderLines(id)).extracting(OrderLine::getCloseKind).containsExactly(CloseKind.SHORT);

        HistoryEntry newest = orders.getOrderHistory(id, null, 1).getItems().getFirst();
        assertThat(newest.getAction()).isEqualTo("ORDER_CLOSED");
        assertThat(String.valueOf(newest.getAfter())).contains("customer needs no more");

        assertConflict(() -> orders.closeOrder(id, null, new CloseOrderRequest().kind(CloseKind.SHORT)
                .reason("again")), "order-already-closed");
        assertConflict(() -> orders.cancelOrder(id, null, new CancelOrderRequest()), "order-illegal-transition");
        assertThat(published(id)).contains("OrderClosed");
    }

    @Test
    @DisplayName("cut freight that has not left refuses a short close; a force close needs its right and goes through")
    void forceClose() {
        OrdersApi clerk = client(acme, CLERK);
        UUID id = validatedOrder(clerk, 1);
        PlanningApi planning = client(acme, PlanningApi.class, CLERK);
        UUID consignment = planning.generateConsignments(id, null).getFirst().getId();

        assertConflict(() -> clerk.closeOrder(id, null, new CloseOrderRequest().kind(CloseKind.SHORT)
                .reason("remainder not wanted")), "order-freight-pending");
        assertStatus(() -> clerk.closeOrder(id, null, new CloseOrderRequest().kind(CloseKind.FORCE)
                .reason("customer insolvent")), 403, "");

        client(acme, SUPERVISOR).closeOrder(id, null,
                new CloseOrderRequest().kind(CloseKind.FORCE).reason("customer insolvent"));
        Order closed = clerk.getOrder(id);
        assertThat(closed.getStatus()).isEqualTo("CLOSED");
        assertThat(closed.getCloseKind()).isEqualTo(CloseKind.FORCE);
        // The consignment cut from it is left exactly as it was.
        assertThat(planning.getConsignment(consignment).getStatus()).isEqualTo("PLANNED");
    }

    @Test
    @DisplayName("one line is closed and the rest stay open; closing every line closes the order")
    void lineClose() {
        OrdersApi orders = client(acme, CLERK);
        UUID id = validatedOrder(orders, 2);
        List<OrderLine> lines = orders.listOrderLines(id);
        UUID first = lines.get(0).getId();
        UUID second = lines.get(1).getId();

        orders.closeOrderLine(id, second, null, new CloseOrderRequest().kind(CloseKind.SHORT).reason("drop 2 cancelled"));
        assertThat(orders.getOrder(id).getStatus()).isEqualTo("VALIDATED");
        assertThat(orders.listOrderLines(id)).extracting(OrderLine::getCloseKind)
                .containsExactly(null, CloseKind.SHORT);
        assertConflict(() -> orders.closeOrderLine(id, second, null,
                new CloseOrderRequest().kind(CloseKind.SHORT).reason("again")), "order-line-already-closed");
        assertStatus(() -> orders.closeOrderLine(id, UUID.randomUUID(), null,
                new CloseOrderRequest().kind(CloseKind.SHORT).reason("no such line")), 404, "not-found");

        orders.closeOrderLine(id, first, null, new CloseOrderRequest().kind(CloseKind.SHORT).reason("drop 1 cancelled"));
        Order closed = orders.getOrder(id);
        assertThat(closed.getStatus()).isEqualTo("CLOSED");
        assertThat(closed.getCloseKind()).isEqualTo(CloseKind.SHORT);
        assertThat(orders.getOrderHistory(id, null, 2).getItems()).extracting(HistoryEntry::getAction)
                .containsExactlyInAnyOrder("ORDER_CLOSED", "ORDER_LINE_CLOSED");
    }

    @Test
    @DisplayName("a draft is not closed, and another tenant's order is not found")
    void refusals() {
        OrdersApi orders = client(acme, SUPERVISOR);
        UUID draft = orders.raiseOrder(raise(acme), null).getId();
        orders.addOrderLine(draft, line(acme, 1), null);
        assertConflict(() -> orders.closeOrder(draft, null, new CloseOrderRequest().kind(CloseKind.FORCE)
                .reason("too early")), "order-not-validated");

        UUID cancelled = validatedOrder(orders, 1);
        orders.cancelOrder(cancelled, null, new CancelOrderRequest());
        assertConflict(() -> orders.closeOrder(cancelled, null, new CloseOrderRequest().kind(CloseKind.FORCE)
                .reason("after cancelling")), "order-is-cancelled");

        UUID open = validatedOrder(orders, 1);
        Network globex = network("globex");
        assertStatus(() -> client(globex, SUPERVISOR).closeOrder(open, null, new CloseOrderRequest()
                .kind(CloseKind.FORCE).reason("not ours")), 404, "not-found");
        assertThat(orders.getOrder(open).getStatus()).isEqualTo("VALIDATED");
    }

    @Test
    @DisplayName("the close policy has defaults, is set per tenant under ORDER_FORCE_CLOSE, and is bounded")
    void closeSettings() {
        OrderCloseSettings defaults = client(acme, CLERK).getOrderCloseSettings();
        assertThat(defaults.getTolerancePercent()).isEqualByComparingTo("0");
        assertThat(defaults.getAutoFulfil()).isTrue();
        assertThat(defaults.getCustomised()).isFalse();

        SetOrderCloseSettingsRequest five = new SetOrderCloseSettingsRequest()
                .tolerancePercent(new BigDecimal("5")).autoFulfil(false);
        assertStatus(() -> client(acme, CLERK).setOrderCloseSettings(five), 403, "");
        OrderCloseSettings set = client(acme, SUPERVISOR).setOrderCloseSettings(five);
        assertThat(set.getTolerancePercent()).isEqualByComparingTo("5");
        assertThat(set.getAutoFulfil()).isFalse();
        assertThat(client(acme, CLERK).getOrderCloseSettings().getCustomised()).isTrue();

        assertConflict(() -> client(acme, SUPERVISOR).setOrderCloseSettings(new SetOrderCloseSettingsRequest()
                .tolerancePercent(new BigDecimal("101")).autoFulfil(true)), "close-tolerance-out-of-range");
        // An omitted switch is a bad request, never a silent "off".
        assertThat(kit.put(acme.tenant(), "/api/v1/orders/close-settings", "{\"tolerancePercent\":1}",
                SUPERVISOR).status()).isEqualTo(400);

        Network globex = network("globex");
        assertThat(client(globex, CLERK).getOrderCloseSettings().getCustomised()).isFalse();
    }

    // ---------------------------------------------------------------- helpers

    private UUID validatedOrder(OrdersApi orders, int lineCount) {
        UUID id = orders.raiseOrder(raise(acme), null).getId();
        for (int lineNo = 1; lineNo <= lineCount; lineNo++) {
            orders.addOrderLine(id, line(acme, lineNo), null);
        }
        orders.validateOrder(id, null);
        return id;
    }

    private List<String> published(UUID orderId) {
        return jdbc.sql("SELECT event_type FROM event_publication WHERE serialized_event LIKE :id")
                .param("id", "%" + orderId + "%")
                .query(String.class).list().stream()
                .map(t -> t.substring(t.lastIndexOf('.') + 1)).toList();
    }

    private static RaiseOrderRequest raise(Network network) {
        return new RaiseOrderRequest().orgUnitId(network.tenant().orgUnitId())
                .customerPartnerId(network.customer()).originTerminalId(network.origin());
    }

    private static AddOrderLineRequest line(Network network, int lineNo) {
        return new AddOrderLineRequest().lineNo(lineNo).materialCode("MAT-" + lineNo)
                .materialDescription("General cargo").materialClass("GENERAL")
                .quantity(BigDecimal.ONE).uom("EA").deadWeightKg(new BigDecimal("1000"))
                .consigneePartnerId(network.consignee()).destinationTerminalId(network.destination());
    }

    private static void assertConflict(Runnable call, String code) {
        assertStatus(call, 409, code);
    }

    private static void assertStatus(Runnable call, int status, String code) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(FeignException.class, e -> {
            assertThat(e.status()).isEqualTo(status);
            assertThat(e.contentUTF8()).contains(code);
        });
    }

    private OrdersApi client(Network network, String... permissions) {
        return client(network, OrdersApi.class, permissions);
    }

    private <T extends ApiClient.Api> T client(Network network, Class<T> api, String... permissions) {
        String token = kit.token(network.tenant(), permissions);
        ApiClient apiClient = new ApiClient().setBasePath("http://localhost:" + port);
        apiClient.getFeignBuilder().requestInterceptor(template ->
                template.header("Authorization", "Bearer " + token));
        return apiClient.buildClient(api);
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
