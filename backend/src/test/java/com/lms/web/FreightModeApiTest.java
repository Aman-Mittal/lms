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
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import com.lms.acceptance.TestFixtures;
import com.lms.client.ApiClient;
import com.lms.client.api.LtlRatingApi;
import com.lms.client.api.OrdersApi;
import com.lms.client.api.PlanningApi;
import com.lms.client.api.SettlementApi;
import com.lms.client.model.AddOrderLineRequest;
import com.lms.client.model.AssignConsignmentRequest;
import com.lms.client.model.AssignLtlZoneRequest;
import com.lms.client.model.Consignment;
import com.lms.client.model.ConsignmentSlice;
import com.lms.client.model.DocketReceivable;
import com.lms.client.model.DocketReceivableSlice;
import com.lms.client.model.FreightMode;
import com.lms.client.model.FuelSurchargeRequest;
import com.lms.client.model.IdResponse;
import com.lms.client.model.Load;
import com.lms.client.model.LtlLeg;
import com.lms.client.model.OpenLoadRequest;
import com.lms.client.model.PublishLtlRateCardRequest;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Freight modes over HTTP, through the client generated from the contract.
 *
 * <p>Walks a part load the way a client would: zone the hubs, publish a rate
 * card, book two customers' shipments as LTL orders, consolidate both dockets
 * onto one vehicle, and read each customer's receivable back. Master data is
 * set up in-process, as in {@code ControlTowerApiTest}; only the behaviour
 * under test crosses the wire.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class FreightModeApiTest {

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
            "ORDER_CREATE", "ORDER_READ", "ORDER_UPDATE", "LOAD_CREATE", "LOAD_READ",
            "TARIFF_MANAGE", "INVOICE_READ");

    /** What the in-process setup needs. Never put in a token. */
    private static final List<String> SETUP = List.of(
            "PARTNER_CREATE", "PARTNER_UPDATE", "VEHICLE_CREATE", "TERMINAL_CREATE");

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
    private UUID bombay;
    private UUID delhi;
    private UUID site;
    private UUID remoteSite;
    private UUID lorry;

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
            bombay = hub("HUB-BOM-" + salt, 19.10, 72.90);
            delhi = hub("HUB-DEL-" + salt, 28.60, 77.20);
            site = depot("SITE-A-" + salt, 28.40, 77.00);
            remoteSite = depot("SITE-B-" + salt, 29.40, 77.90);
            lorry = fleet.registerVehicle(orgUnitId, null, "MH-04-" + salt, "TRUCK", "RIGID",
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
    @DisplayName("two customers' dockets are consolidated on one vehicle and billed separately")
    void consolidationAndRatingOverHttp() {
        LtlRatingApi rating = client(LtlRatingApi.class);
        OrdersApi orders = client(OrdersApi.class);
        PlanningApi planning = client(PlanningApi.class);

        rating.assignLtlZone(bombay, new AssignLtlZoneRequest().zoneCode("west"));
        rating.assignLtlZone(delhi, new AssignLtlZoneRequest().zoneCode("NORTH"));
        rating.assignLtlZone(remoteSite,
                new AssignLtlZoneRequest().zoneCode("NORTH").outOfDeliveryArea(true));
        rating.publishLtlRateCard(new PublishLtlRateCardRequest()
                .code("WN-" + salt).originZone("WEST").destinationZone("NORTH")
                .effectiveFrom(LocalDate.now(ZoneOffset.UTC).minusDays(7))
                .rateBasis(PublishLtlRateCardRequest.RateBasisEnum.PER_KG)
                .rateAmount(new BigDecimal("12")).minimumChargeableWeightKg(new BigDecimal("50"))
                .kgPerCft(new BigDecimal("6")).docketCharge(new BigDecimal("100"))
                .odaSurcharge(new BigDecimal("500")), null);

        UUID docketOne = book(orders, planning, "LTL-1", customerOne, site, "200");
        UUID docketTwo = book(orders, planning, "LTL-2", customerTwo, remoteSite, "30");

        Consignment one = planning.getConsignment(docketOne);
        assertThat(one.getFreightMode()).isEqualTo("LTL");
        assertThat(one.getTrackingRef()).startsWith("DKT-");

        UUID load = planning.openLoad(new OpenLoadRequest().orgUnitId(orgUnitId)
                .loadNo("LOAD-" + salt).originTerminalId(bombay).vehicleId(lorry)
                .freightMode(FreightMode.LTL), null).getId();
        planning.assignConsignment(load, new AssignConsignmentRequest().consignmentId(docketOne), null);
        planning.assignConsignment(load, new AssignConsignmentRequest().consignmentId(docketTwo), null);
        planning.planLoad(load, null);

        // Each customer is billed for their own docket: 200 kg at 12 plus the
        // docket charge, and a 30 kg parcel lifted to the 50 kg minimum, with
        // the ODA surcharge for a delivery outside the area.
        DocketReceivable first = onlyReceivable(rating, customerOne);
        assertThat(first.getStatus()).isEqualTo(DocketReceivable.StatusEnum.PRICED);
        assertThat(first.getOriginZone()).isEqualTo("WEST");
        assertThat(first.getTotalAmount()).isEqualByComparingTo("2500.00");

        DocketReceivable second = onlyReceivable(rating, customerTwo);
        assertThat(second.getChargeableWeightKg()).isEqualByComparingTo("50");
        assertThat(second.getMinimumApplied()).isTrue();
        assertThat(second.getOdaSurcharge()).isEqualByComparingTo("500.00");
        assertThat(second.getTotalAmount()).isEqualByComparingTo("1200.00");

        // The unfiltered listing pages by docket number.
        DocketReceivableSlice page = rating.listDocketReceivables(null, null, null, 1);
        assertThat(page.getItems()).hasSize(1);
        assertThat(page.getHasMore()).isTrue();
        DocketReceivableSlice rest = rating.listDocketReceivables(null, null, page.getNextCursor(), 1);
        assertThat(rest.getItems()).hasSize(1);
        assertThat(rest.getHasMore()).isFalse();
    }

    @Test
    @DisplayName("the booking month's fuel index surcharges the docket, and a per-kg ODA card is honoured")
    void fuelAndOdaPerKgOverHttp() {
        LtlRatingApi rating = client(LtlRatingApi.class);
        OrdersApi orders = client(OrdersApi.class);
        PlanningApi planning = client(PlanningApi.class);
        SettlementApi settlement = client(SettlementApi.class);

        rating.assignLtlZone(bombay, new AssignLtlZoneRequest().zoneCode("WEST"));
        rating.assignLtlZone(delhi, new AssignLtlZoneRequest().zoneCode("NORTH"));
        rating.assignLtlZone(remoteSite,
                new AssignLtlZoneRequest().zoneCode("NORTH").outOfDeliveryArea(true));
        rating.publishLtlRateCard(new PublishLtlRateCardRequest()
                .code("WN-ODA-" + salt).originZone("WEST").destinationZone("NORTH")
                .effectiveFrom(LocalDate.now(ZoneOffset.UTC).minusDays(7))
                .rateBasis(PublishLtlRateCardRequest.RateBasisEnum.PER_KG)
                .rateAmount(new BigDecimal("12")).minimumChargeableWeightKg(new BigDecimal("50"))
                .kgPerCft(new BigDecimal("6")).docketCharge(new BigDecimal("100"))
                .odaBasis(PublishLtlRateCardRequest.OdaBasisEnum.PER_KG)
                .odaRatePerKg(new BigDecimal("3.5")).odaMinimum(new BigDecimal("250")), null);
        settlement.recordFuelSurcharge(new FuelSurchargeRequest()
                .month(LocalDate.now(ZoneOffset.UTC)).surchargePct(new BigDecimal("7.5")), null);

        book(orders, planning, "LTL-F", customerOne, remoteSite, "200");

        // Freight 200 x 12 = 2400, fuel 7.5% of that = 180, docket 100,
        // ODA 200 kg x 3.50 = 700 (above the 250 floor): 3380.
        DocketReceivable receivable = onlyReceivable(rating, customerOne);
        assertThat(receivable.getStatus()).isEqualTo(DocketReceivable.StatusEnum.PRICED);
        assertThat(receivable.getFreightAmount()).isEqualByComparingTo("2400.00");
        assertThat(receivable.getFuelSurchargePct()).isEqualByComparingTo("7.5");
        assertThat(receivable.getFuelSurcharge()).isEqualByComparingTo("180.00");
        assertThat(receivable.getOdaBasis()).isEqualTo(DocketReceivable.OdaBasisEnum.PER_KG);
        assertThat(receivable.getOdaSurcharge()).isEqualByComparingTo("700.00");
        assertThat(receivable.getTotalAmount()).isEqualByComparingTo("3380.00");
    }

    @Test
    @DisplayName("a delivery load is opened at a hub and its queue is listed")
    void deliveryLegOverHttp() {
        PlanningApi planning = client(PlanningApi.class);

        UUID delivery = planning.openLoad(new OpenLoadRequest().orgUnitId(orgUnitId)
                .loadNo("LOAD-DL-" + salt).originTerminalId(delhi).vehicleId(lorry)
                .freightMode(FreightMode.LTL).ltlLeg(LtlLeg.DELIVERY), null).getId();
        Load load = planning.getLoad(delivery);
        assertThat(load.getLtlLeg()).isEqualTo(LtlLeg.DELIVERY);
        assertThat(load.getFreightMode()).isEqualTo("LTL");

        ConsignmentSlice queue = planning.listDocketsAwaitingDelivery(delhi, null, null);
        assertThat(queue.getItems()).isEmpty();
        assertThat(queue.getHasMore()).isFalse();

        assertConflict(() -> planning.openLoad(new OpenLoadRequest().orgUnitId(orgUnitId)
                .loadNo("LOAD-DLX-" + salt).originTerminalId(delhi).vehicleId(lorry)
                .ltlLeg(LtlLeg.DELIVERY), null), "delivery-leg-only-for-ltl");
    }

    @Test
    @DisplayName("mode refusals arrive as documented conflicts")
    void refusals() {
        OrdersApi orders = client(OrdersApi.class);
        PlanningApi planning = client(PlanningApi.class);

        assertConflict(() -> orders.raiseOrder(order("RAIL-1", customerOne)
                .freightMode(FreightMode.RAIL), null), "freight-mode-not-supported");
        assertConflict(() -> orders.raiseOrder(order("LTL-X", customerOne)
                .freightMode(FreightMode.LTL), null), "ltl-order-needs-hubs");
        // A customer's site is not a hub.
        assertConflict(() -> orders.raiseOrder(order("LTL-Y", customerOne)
                .freightMode(FreightMode.LTL).originHubTerminalId(site)
                .destinationHubTerminalId(delhi), null), "ltl-hub-not-a-hub");

        // A full-truckload consignment cannot ride on a consolidated vehicle.
        UUID ftlOrder = orders.raiseOrder(order("FTL-1", customerOne), null).getId();
        orders.addOrderLine(ftlOrder, line("500"), null);
        orders.validateOrder(ftlOrder, null);
        UUID ftlConsignment = planning.generateConsignments(ftlOrder, null).get(0).getId();
        assertThat(planning.getConsignment(ftlConsignment).getTrackingRef()).startsWith("LR-");

        UUID load = planning.openLoad(new OpenLoadRequest().orgUnitId(orgUnitId)
                .loadNo("LOAD-" + salt).originTerminalId(bombay).vehicleId(lorry)
                .freightMode(FreightMode.LTL), null).getId();
        assertConflict(() -> planning.assignConsignment(load,
                new AssignConsignmentRequest().consignmentId(ftlConsignment), null),
                "load-freight-mode-mismatch");
    }

    @Test
    @DisplayName("a token without the tariff permission cannot zone a terminal")
    void zoningNeedsTariffPermission() {
        LtlRatingApi readOnly = client(LtlRatingApi.class, List.of("INVOICE_READ"));
        assertThatThrownBy(() -> readOnly.assignLtlZone(bombay,
                new AssignLtlZoneRequest().zoneCode("WEST")))
                .isInstanceOfSatisfying(FeignException.class,
                        e -> assertThat(e.status()).isEqualTo(403));
    }

    // --------------------------------------------------------------- helpers

    private UUID book(OrdersApi orders, PlanningApi planning, String orderNo, UUID customer,
                      UUID deliverTo, String weightKg) {
        UUID orderId = orders.raiseOrder(order(orderNo, customer)
                .freightMode(FreightMode.LTL)
                .originHubTerminalId(bombay)
                .destinationHubTerminalId(delhi), null).getId();
        orders.addOrderLine(orderId, line(weightKg).destinationTerminalId(deliverTo), null);
        orders.validateOrder(orderId, null);
        List<IdResponse> dockets = planning.generateConsignments(orderId, null);
        assertThat(dockets).hasSize(1);
        return dockets.get(0).getId();
    }

    private RaiseOrderRequest order(String orderNo, UUID customer) {
        return new RaiseOrderRequest().orgUnitId(orgUnitId).customerPartnerId(customer)
                .orderNo(orderNo + "-" + salt).originTerminalId(bombay);
    }

    private AddOrderLineRequest line(String weightKg) {
        return new AddOrderLineRequest().lineNo(1).materialCode("MAT-1")
                .materialDescription("General cargo").materialClass("GENERAL")
                .quantity(BigDecimal.ONE).uom("EA").deadWeightKg(new BigDecimal(weightKg))
                .consigneePartnerId(consignee).destinationTerminalId(site);
    }

    private DocketReceivable onlyReceivable(LtlRatingApi rating, UUID customer) {
        DocketReceivableSlice slice = rating.listDocketReceivables(customer, null, null, null);
        assertThat(slice.getItems()).hasSize(1);
        assertThat(slice.getItems().get(0).getCustomerPartnerId()).isEqualTo(customer);
        return slice.getItems().get(0);
    }

    private static void assertConflict(Runnable call, String code) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(FeignException.class, e -> {
            assertThat(e.status()).isEqualTo(409);
            assertThat(e.contentUTF8()).contains(code);
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
        return terminal(code, Terminal.FunctionalCategory.CUSTOMER_SITE, lat, lon);
    }

    /** Part loads may only be booked between terminals registered as hubs. */
    private UUID hub(String code, double lat, double lon) {
        return terminal(code, Terminal.FunctionalCategory.HUB, lat, lon);
    }

    private UUID terminal(String code, Terminal.FunctionalCategory category, double lat,
                          double lon) {
        return terminals.register(new RegisterTerminalCommand.PointRadius(orgUnitId, code, code,
                category, new LatLon(lat, lon), 500, 2, null, null, 45, List.of()));
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
