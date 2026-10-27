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
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
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
import com.lms.client.model.AmendOrderRequest;
import com.lms.client.model.CancelOrderRequest;
import com.lms.client.model.Order;
import com.lms.client.model.OrderLine;
import com.lms.client.model.RaiseOrderRequest;
import com.lms.client.model.ReplaceOrderLinesRequest;
import com.lms.client.model.UpdateOrderRequest;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Order editing over HTTP, through the client generated from the contract.
 *
 * <p>What the Cucumber scenarios cannot show: that the version travels as an
 * {@code ETag} on a read and comes back in {@code If-Match}, that an edit
 * without one is a 400 and not a silent overwrite, and that a stale one is a
 * 409 problem document.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OrderEditingApiTest {

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
            "PARTNER_CREATE", "PARTNER_UPDATE", "TERMINAL_CREATE");

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
    private UUID orgUnitId;
    private AppUser planner;
    private UUID customerOne;
    private UUID customerTwo;
    private UUID consignee;
    private UUID origin;
    private UUID destination;

    @BeforeEach
    void aNetwork() {
        salt = UUID.randomUUID().toString().substring(0, 8);
        orgPath = "/acme-" + salt + "/";
        UUID tenantId = fixtures.createTenant("acme-" + salt, "Acme Logistics");
        orgUnitId = fixtures.createOrgUnit(tenantId, orgPath, "Acme HQ", "HQ", null);
        planner = user(tenantId, "planner@acme.test");
        asSetup(tenantId, () -> {
            customerOne = partner("CUST1-" + salt);
            customerTwo = partner("CUST2-" + salt);
            consignee = partner("CONS-" + salt);
            origin = depot("ORG-" + salt, 19.10, 72.90);
            destination = depot("DST-" + salt, 28.40, 77.00);
            return null;
        });
    }

    @AfterEach
    void signOut() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("a read carries the version as its ETag and an edit must send it back")
    void ifMatchGuardsADraftEdit() throws Exception {
        OrdersApi orders = client(OrdersApi.class);
        UUID id = orders.raiseOrder(raise("ED-HTTP-1"), null).getId();
        orders.addOrderLine(id, line(1, "1000"), null);

        Order read = orders.getOrder(id);
        assertThat(etagOf(id)).isEqualTo("\"" + read.getVersion() + "\"");

        assertThatThrownBy(() -> orders.updateOrder(null, id, header(customerTwo), null))
                .isInstanceOfSatisfying(FeignException.class, e -> assertThat(e.status()).isEqualTo(400));

        orders.updateOrder(String.valueOf(read.getVersion()), id, header(customerTwo), null);
        Order edited = orders.getOrder(id);
        assertThat(edited.getCustomerPartnerId()).isEqualTo(customerTwo);
        assertThat(edited.getVersion()).isGreaterThan(read.getVersion());

        // The same, now stale, version again: the second editor of a pair.
        assertThatThrownBy(() -> orders.updateOrder(String.valueOf(read.getVersion()), id,
                header(customerOne), null))
                .isInstanceOfSatisfying(FeignException.class, e -> {
                    assertThat(e.status()).isEqualTo(409);
                    assertThat(e.contentUTF8()).contains("concurrent-modification");
                });
        assertThat(orders.getOrder(id).getCustomerPartnerId()).isEqualTo(customerTwo);

        // A quoted ETag, as a browser would send it back, is accepted too.
        orders.updateOrder("\"" + edited.getVersion() + "\"", id, header(customerOne), null);
        assertThat(orders.getOrder(id).getCustomerPartnerId()).isEqualTo(customerOne);
    }

    @Test
    @DisplayName("a draft's lines are replaced as a set or removed one by one")
    void replaceAndRemoveLines() {
        OrdersApi orders = client(OrdersApi.class);
        UUID id = orders.raiseOrder(raise("ED-HTTP-2"), null).getId();
        orders.addOrderLine(id, line(1, "1000"), null);
        orders.addOrderLine(id, line(2, "500"), null);

        orders.replaceOrderLines(versionOf(orders, id), id, new ReplaceOrderLinesRequest()
                .lines(List.of(line(1, "700"), line(2, "300"), line(3, "200"))), null);
        List<OrderLine> lines = orders.listOrderLines(id);
        assertThat(lines).extracting(OrderLine::getLineNo).containsExactly(1, 2, 3);

        orders.removeOrderLine(versionOf(orders, id), id, lines.get(1).getId(), null);
        assertThat(orders.listOrderLines(id)).extracting(OrderLine::getLineNo).containsExactly(1, 3);

        assertThatThrownBy(() -> orders.removeOrderLine(versionOf(orders, id), id, UUID.randomUUID(), null))
                .isInstanceOfSatisfying(FeignException.class, e -> assertThat(e.status()).isEqualTo(404));
    }

    @Test
    @DisplayName("a line without a material description is a 400 problem document, not a 500")
    void lineWithoutDescriptionIsRejected() {
        OrdersApi orders = client(OrdersApi.class);
        UUID id = orders.raiseOrder(raise("ED-HTTP-4"), null).getId();

        assertThatThrownBy(() -> orders.addOrderLine(id, line(1, "100").materialDescription(null), null))
                .isInstanceOfSatisfying(FeignException.class, e -> assertThat(e.status()).isEqualTo(400));
        assertThatThrownBy(() -> orders.addOrderLine(id, line(1, "100").materialDescription(" "), null))
                .isInstanceOfSatisfying(FeignException.class, e -> assertThat(e.status()).isEqualTo(400));
        assertThat(orders.listOrderLines(id)).isEmpty();
    }

    @Test
    @DisplayName("a validated order is amended until freight is cut from it, then refused")
    void amendmentAndItsRefusals() {
        OrdersApi orders = client(OrdersApi.class);
        PlanningApi planning = client(PlanningApi.class);
        UUID id = orders.raiseOrder(raise("ED-HTTP-3"), null).getId();
        orders.addOrderLine(id, line(1, "1000"), null);

        // A draft is edited, not amended.
        assertConflict(() -> orders.amendOrder(versionOf(orders, id), id,
                new AmendOrderRequest().header(header(customerTwo)), null), "order-not-validated");

        orders.validateOrder(id, null);
        // And a validated order is amended, not edited.
        assertConflict(() -> orders.updateOrder(versionOf(orders, id), id, header(customerTwo), null),
                "order-not-editable");

        String before = versionOf(orders, id);
        orders.amendOrder(before, id, new AmendOrderRequest().header(header(customerTwo))
                .lines(List.of(line(1, "1500"))), null);
        Order amended = orders.getOrder(id);
        assertThat(amended.getStatus()).isEqualTo("VALIDATED");
        assertThat(amended.getCustomerPartnerId()).isEqualTo(customerTwo);
        assertThat(amended.getTotalDeadWeightKg()).isEqualByComparingTo("1500");
        assertThat(String.valueOf(amended.getVersion())).isNotEqualTo(before);

        assertConflict(() -> orders.amendOrder(before, id,
                new AmendOrderRequest().header(header(customerOne)), null), "concurrent-modification");
        assertThatThrownBy(() -> orders.amendOrder(versionOf(orders, id), id, new AmendOrderRequest(), null))
                .isInstanceOfSatisfying(FeignException.class, e -> assertThat(e.status()).isEqualTo(400));

        planning.generateConsignments(id, null);
        assertConflict(() -> orders.amendOrder(versionOf(orders, id), id,
                new AmendOrderRequest().header(header(customerOne)), null), "order-has-consignments");

        UUID cancelled = orders.raiseOrder(raise("ED-HTTP-4"), null).getId();
        orders.addOrderLine(cancelled, line(1, "100"), null);
        orders.validateOrder(cancelled, null);
        orders.cancelOrder(cancelled, null, new CancelOrderRequest().reason("customer withdrew"));
        assertConflict(() -> orders.amendOrder(versionOf(orders, cancelled), cancelled,
                new AmendOrderRequest().header(header(customerOne)), null), "order-not-amendable");
    }

    // ------------------------------------------------------------- helpers

    private String versionOf(OrdersApi orders, UUID id) {
        return String.valueOf(orders.getOrder(id).getVersion());
    }

    /** The raw ETag header of a read, which the generated client does not expose. */
    private String etagOf(UUID id) throws Exception {
        String token = tokens.issueAccessToken(planner, orgPath, PERMISSIONS).value();
        HttpResponse<String> response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                        URI.create("http://localhost:" + port + "/api/v1/orders/" + id))
                .header("Authorization", "Bearer " + token).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        return response.headers().firstValue("ETag").orElseThrow();
    }

    private RaiseOrderRequest raise(String orderNo) {
        return new RaiseOrderRequest().orgUnitId(orgUnitId).customerPartnerId(customerOne)
                .orderNo(orderNo + "-" + salt).originTerminalId(origin);
    }

    private UpdateOrderRequest header(UUID customer) {
        return new UpdateOrderRequest().customerPartnerId(customer).originTerminalId(origin);
    }

    private AddOrderLineRequest line(int lineNo, String weightKg) {
        return new AddOrderLineRequest().lineNo(lineNo).materialCode("MAT-" + lineNo)
                .materialDescription("General cargo").materialClass("GENERAL")
                .quantity(BigDecimal.ONE).uom("EA").deadWeightKg(new BigDecimal(weightKg))
                .consigneePartnerId(consignee).destinationTerminalId(destination);
    }

    private static void assertConflict(Runnable call, String code) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(FeignException.class, e -> {
            assertThat(e.status()).isEqualTo(409);
            assertThat(e.contentUTF8()).contains(code);
        });
    }

    private <T extends ApiClient.Api> T client(Class<T> api) {
        String token = tokens.issueAccessToken(planner, orgPath, PERMISSIONS).value();
        ApiClient apiClient = new ApiClient().setBasePath("http://localhost:" + port);
        apiClient.getFeignBuilder().requestInterceptor(template ->
                template.header("Authorization", "Bearer " + token));
        return apiClient.buildClient(api);
    }

    private AppUser user(UUID tenantId, String email) {
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
    private <T> T asSetup(UUID tenantId, Supplier<T> action) {
        Set<SimpleGrantedAuthority> authorities = SETUP.stream()
                .map(SimpleGrantedAuthority::new)
                .collect(Collectors.toSet());
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("setup", "n/a", authorities));
        try {
            return TenantContext.callWith(tenantId, orgPath, action);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }
}
