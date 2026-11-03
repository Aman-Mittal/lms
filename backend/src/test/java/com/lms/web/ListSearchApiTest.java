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
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Supplier;

import com.lms.acceptance.SpineFixtures;
import com.lms.acceptance.TenantSeeds;
import com.lms.acceptance.TestFixtures;
import com.lms.execution.command.TripCommandService;
import com.lms.identity.security.TokenService;
import com.lms.masterdata.command.FleetCommandService;
import com.lms.masterdata.command.RegisterTerminalCommand;
import com.lms.masterdata.command.TerminalCommandService;
import com.lms.masterdata.command.domain.BusinessPartner;
import com.lms.masterdata.command.domain.Terminal;
import com.lms.shared.geo.LatLon;
import com.lms.shared.tenant.TenantContext;
import com.lms.web.ApiTestKit.Outcome;
import com.lms.web.ApiTestKit.Tenant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The {@code q} search the console's pickers type into: every listing a
 * picker reads narrows to rows whose code or name contains the text,
 * ignoring case, and pages exactly as it does unsearched.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ListSearchApiTest {

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

    private static final String[] RIGHTS = {
        "PARTNER_CREATE", "PARTNER_READ", "VEHICLE_CREATE", "VEHICLE_READ", "DRIVER_CREATE",
        "DRIVER_READ", "TERMINAL_CREATE", "TERMINAL_READ", "WAREHOUSE_SETUP", "WAREHOUSE_READ"};

    @LocalServerPort
    private int port;
    @Autowired
    private TestFixtures fixtures;
    @Autowired
    private TokenService tokens;
    @Autowired
    private SpineFixtures spine;
    @Autowired
    private TenantSeeds seeds;
    @Autowired
    private FleetCommandService fleet;
    @Autowired
    private TerminalCommandService terminals;
    @Autowired
    private TripCommandService trips;
    @Autowired
    private JdbcClient jdbc;

    private ApiTestKit kit;
    private Tenant tenant;
    private String salt;
    private int terminalsMade;

    @BeforeEach
    void aTenant() {
        kit = new ApiTestKit(fixtures, tokens, port);
        tenant = kit.newTenant("search");
        salt = UUID.randomUUID().toString().substring(0, 6).toUpperCase(Locale.ROOT);
    }

    @Test
    @DisplayName("partners are found by part of the code or the legal name, ignoring case")
    void partners() {
        partner("CUST-ALPHA", "Northern Steel Works");
        partner("CUST-BETA", "Southern Cement");
        partner("VEND-GAMMA", "Fast Haulage");

        assertThat(codes("/api/v1/partners?q=steel", "code")).containsExactly("CUST-ALPHA");
        assertThat(codes("/api/v1/partners?q=cust-", "code")).containsExactly("CUST-ALPHA", "CUST-BETA");
        assertThat(codes("/api/v1/partners?q=CUST&partnerType=VENDOR", "code")).isEmpty();
        assertThat(codes("/api/v1/partners?q=%20%20", "code")).hasSize(3);
    }

    @Test
    @DisplayName("a search pages like the unsearched list, and the wildcards are literal")
    void pagingAndWildcards() {
        for (int i = 1; i <= 5; i++) {
            partner("SITE_" + i, "Depot " + i);
        }
        partner("SITEX1", "Sitex depot");

        List<String> seen = new ArrayList<>();
        String cursor = null;
        do {
            JsonNode page = kit.parse(ok("/api/v1/partners?q=site_&limit=2"
                    + (cursor == null ? "" : "&cursor=" + cursor)));
            page.get("items").forEach(item -> seen.add(item.get("code").asString()));
            cursor = page.hasNonNull("nextCursor") ? page.get("nextCursor").asString() : null;
        } while (cursor != null);

        assertThat(seen).containsExactly("SITE_1", "SITE_2", "SITE_3", "SITE_4", "SITE_5");
        assertThat(codes("/api/v1/partners?q=%25", "code")).isEmpty();
    }

    @Test
    @DisplayName("vehicles, drivers and terminals are found by registration, licence, name or code")
    void fleetAndTerminals() {
        vehicle("MH12AB1234");
        vehicle("KA01ZZ0001");
        driver("DL-0099", "Ravi Kumar");
        driver("DL-0100", "Asha Patel");
        terminal("BHW-DC", "Bhiwandi Distribution Centre");
        terminal("PNQ-HUB", "Pune Hub");

        assertThat(codes("/api/v1/vehicles?q=mh12", "registrationNo")).containsExactly("MH12AB1234");
        assertThat(codes("/api/v1/drivers?q=asha", "licenceNo")).containsExactly("DL-0100");
        assertThat(codes("/api/v1/drivers?q=0099", "licenceNo")).containsExactly("DL-0099");
        assertThat(codes("/api/v1/terminals?q=distribution", "code")).containsExactly("BHW-DC");
        assertThat(codes("/api/v1/terminals?q=hub&category=PORT", "code")).isEmpty();
    }

    @Test
    @DisplayName("SKUs and a warehouse's bins are found by code or description")
    void skusAndBins() {
        String warehouse = terminal("WH-1", "Main Warehouse");
        sku("SKU-COIL-01", "Hot-rolled coil");
        sku("SKU-BOLT-02", "Hex bolt M12");
        bin(warehouse, "A-01-01");
        bin(warehouse, "B-02-07");

        assertThat(codes("/api/v1/skus?q=bolt", "code")).containsExactly("SKU-BOLT-02");
        assertThat(codes("/api/v1/skus?q=sku-", "code")).containsExactly("SKU-BOLT-02", "SKU-COIL-01");
        assertThat(codes("/api/v1/warehouses/" + warehouse + "/bins?q=b-02", "code"))
                .containsExactly("B-02-07");
    }

    @Test
    @DisplayName("another tenant's rows are never found, however well they match")
    void tenancy() {
        partner("MATCH-ME", "Shared Name Ltd");
        Tenant other = kit.newTenant("other");
        Outcome theirs = kit.get(other, "/api/v1/partners?q=match", RIGHTS);
        assertThat(theirs.status()).isEqualTo(200);
        assertThat(kit.parse(theirs).get("items")).isEmpty();
    }

    @Test
    @DisplayName("loads, trips, tickets, allocations and bills are found by their numbers")
    void movement() {
        Movement alpha = movement("ALPHA");
        Movement beta = movement("BETA");
        String[] all = seeds.allPermissions().toArray(String[]::new);

        assertThat(codesAs(all, "/api/v1/loads?q=ld-alpha", "loadNo")).containsExactly(alpha.loadNo());
        assertThat(codesAs(all, "/api/v1/trips?q=TR-BE", "tripNo")).containsExactly(beta.tripNo());
        assertThat(codesAs(all, "/api/v1/allocations?q=alpha", "loadNo")).containsExactly(alpha.loadNo());
        assertThat(codesAs(all, "/api/v1/allocations?q=" + beta.allocationId().toString().substring(0, 13),
                "loadNo")).containsExactly(beta.loadNo());
        assertThat(codesAs(all, "/api/v1/freight-bills?q=fb-beta", "billNo"))
                .containsExactly("FB-BETA-" + salt);

        for (Movement m : List.of(alpha, beta)) {
            Outcome raised = kit.post(tenant, "/api/v1/exceptions", """
                    {"tripId":"%s","category":"BREAKDOWN","description":"Stopped on the highway"}"""
                    .formatted(m.tripId()), all);
            assertThat(raised.status()).as(raised.body()).isEqualTo(201);
        }
        assertThat(codesAs(all, "/api/v1/exceptions?q=tr-alpha", "tripNo")).containsExactly(alpha.tripNo());
        String ticketNo = codesAs(all, "/api/v1/exceptions?q=tr-beta", "ticketNo").get(0);
        assertThat(codesAs(all, "/api/v1/exceptions?q=" + ticketNo.toLowerCase(Locale.ROOT), "ticketNo"))
                .containsExactly(ticketNo);
    }

    @Test
    @DisplayName("orders and consignments are found by order number and tracking reference")
    void ordersAndConsignments() {
        String[] all = seeds.allPermissions().toArray(String[]::new);
        String customer = partner("CUST-1", "Steel Works");
        String origin = terminal("ORIG-1", "Plant");
        String destination = terminal("DEST-1", "Mill");
        String first = order(all, "SO-NORTH-1", customer, origin, destination);
        order(all, "SO-SOUTH-2", customer, origin, destination);

        assertThat(codesAs(all, "/api/v1/orders?q=north", "orderNo")).containsExactly("SO-NORTH-1");

        Outcome cut = kit.post(tenant, "/api/v1/orders/" + first + "/consignments", "{}", all);
        assertThat(cut.status()).as(cut.body()).isEqualTo(201);
        String consignment = kit.parse(cut).get(0).get("id").asString();
        String ref = kit.parse(ok(all, "/api/v1/consignments/" + consignment)).get("trackingRef").asString();
        String tail = ref.substring(ref.length() - 6).toLowerCase(Locale.ROOT);

        assertThat(codesAs(all, "/api/v1/consignments?q=" + tail, "trackingRef")).containsExactly(ref);
        assertThat(codesAs(all, "/api/v1/consignments/unassigned?q=" + tail, "trackingRef"))
                .containsExactly(ref);
        assertThat(codesAs(all, "/api/v1/consignments?q=" + tail + "&status=DELIVERED", "trackingRef"))
                .isEmpty();

        Outcome split = kit.post(tenant, "/api/v1/consignments/" + consignment + "/split", """
                {"parts":[{"deadWeightKg":600,"volumeM3":0},{"deadWeightKg":400,"volumeM3":0}]}""", all);
        assertThat(split.status()).as(split.body()).isEqualTo(201);
        assertThat(codesAs(all, "/api/v1/consignments/" + consignment + "/parts?q=-02", "trackingRef"))
                .containsExactly(ref + "-02");
        assertThat(codesAs(all, "/api/v1/consignments?q=" + tail, "trackingRef"))
                .containsExactly(ref, ref + "-01", ref + "-02");
    }

    // ------------------------------------------------------------- helpers

    private List<String> codes(String path, String field) {
        List<String> out = new ArrayList<>();
        kit.parse(ok(path)).get("items").forEach(item -> out.add(item.get(field).asString()));
        return out;
    }

    private Outcome ok(String path) {
        Outcome outcome = kit.get(tenant, path, RIGHTS);
        assertThat(outcome.status()).as(path + " -> " + outcome.body()).isEqualTo(200);
        return outcome;
    }

    private String created(String path, String body) {
        Outcome outcome = kit.post(tenant, path, body, RIGHTS);
        assertThat(outcome.status()).as(path + " -> " + outcome.body()).isEqualTo(201);
        return kit.parse(outcome).get("id").asString();
    }

    private String partner(String code, String name) {
        return created("/api/v1/partners", """
                {"orgUnitId":"%s","code":"%s","legalName":"%s","partnerType":"CUSTOMER"}"""
                .formatted(tenant.orgUnitId(), code, name));
    }

    private void vehicle(String registration) {
        created("/api/v1/vehicles", """
                {"orgUnitId":"%s","registrationNo":"%s","category":"TRUCK",
                 "vehicleType":"RIGID","axleConfig":"2-AXLE","grossWeightKg":16000,"tareWeightKg":6000,"maxVolumeM3":40}"""
                .formatted(tenant.orgUnitId(), registration));
    }

    private void driver(String licence, String name) {
        created("/api/v1/drivers", """
                {"orgUnitId":"%s","fullName":"%s","phone":"+910000000000","licenceNo":"%s",
                 "licenceClass":"HMV","licenceExpiresOn":"2031-01-01"}"""
                .formatted(tenant.orgUnitId(), name, licence));
    }

    /** Each terminal a degree further east, so none overlaps another of its category. */
    private String terminal(String code, String name) {
        double lon = 70 + terminalsMade++;
        return created("/api/v1/terminals/point-radius", """
                {"orgUnitId":"%s","code":"%s","name":"%s","category":"WAREHOUSE",
                 "centre":{"lat":19.3,"lon":%s},"radiusMetres":400}"""
                .formatted(tenant.orgUnitId(), code, name, lon));
    }

    private void sku(String code, String description) {
        created("/api/v1/skus", """
                {"code":"%s","description":"%s"}""".formatted(code, description));
    }

    private void bin(String warehouse, String code) {
        created("/api/v1/warehouses/" + warehouse + "/bins", """
                {"code":"%s","capacityUnits":100}""".formatted(code));
    }

    /** One awarded load with its trip, its allocation and the trip's freight bill. */
    private record Movement(String loadNo, String tripNo, UUID tripId, UUID allocationId) {
    }

    private Movement movement(String name) {
        String loadNo = "LD-" + name + "-" + salt;
        String tripNo = "TR-" + name + "-" + salt;
        UUID[] ids = asSetup(() -> {
            UUID customer = fleet.registerPartner(tenant.orgUnitId(), "C-" + name + salt, "Customer " + name,
                    BusinessPartner.PartnerType.CUSTOMER, "TAX-C-" + name + salt);
            UUID consignee = fleet.registerPartner(tenant.orgUnitId(), "K-" + name + salt, "Consignee " + name,
                    BusinessPartner.PartnerType.CUSTOMER, "TAX-K-" + name + salt);
            UUID vendor = fleet.registerPartner(tenant.orgUnitId(), "V-" + name + salt, "Vendor " + name,
                    BusinessPartner.PartnerType.VENDOR, "TAX-V-" + name + salt);
            fleet.transitionPartner(vendor, BusinessPartner.PartnerStatus.PENDING_VERIFICATION);
            fleet.transitionPartner(vendor, BusinessPartner.PartnerStatus.ACTIVE);
            double lat = "ALPHA".equals(name) ? 19.1 : 21.1;
            UUID origin = depot("O-" + name + salt, lat, 72.9);
            UUID destination = depot("D-" + name + salt, lat + 0.2, 73.1);
            UUID vehicle = fleet.registerVehicle(tenant.orgUnitId(), null, "MH-" + name + salt, "TRUCK",
                    "RIGID", "2-AXLE", new BigDecimal("26000"), new BigDecimal("6000"),
                    new BigDecimal("60"), false, false);
            UUID load = spine.awardedLoad(tenant.orgUnitId(), loadNo, customer, origin, consignee,
                    destination, vehicle, vendor, new BigDecimal("5000"), false);
            UUID trip = trips.raiseTrip(tripNo, load, Instant.now());
            return new UUID[] {load, trip, vendor};
        });
        UUID allocation = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO allocation (id, tenant_id, load_id, strategy, status,
                                                current_rank, awarded_vendor_partner_id, awarded_at)
                        VALUES (:id, :t, :load, 'CONTRACTUAL', 'AWARDED', 1, :vendor, now())
                        """)
                .param("id", allocation).param("t", tenant.tenantId()).param("load", ids[0])
                .param("vendor", ids[2]).update();
        jdbc.sql("""
                        INSERT INTO freight_bill (id, tenant_id, org_unit_id, trip_id, load_id,
                                                  vendor_partner_id, bill_no)
                        VALUES (:id, :t, :ou, :trip, :load, :vendor, :no)
                        """)
                .param("id", UUID.randomUUID()).param("t", tenant.tenantId())
                .param("ou", tenant.orgUnitId()).param("trip", ids[1]).param("load", ids[0])
                .param("vendor", ids[2]).param("no", "FB-" + name + "-" + salt).update();
        return new Movement(loadNo, tripNo, ids[1], allocation);
    }

    private UUID depot(String code, double lat, double lon) {
        return terminals.register(new RegisterTerminalCommand.PointRadius(tenant.orgUnitId(), code, code,
                Terminal.FunctionalCategory.CUSTOMER_SITE, new LatLon(lat, lon), 500, 2, null,
                null, 45, List.of()));
    }

    /** Builds through the services as the setup role; never put in a token. */
    private <T> T asSetup(Supplier<T> action) {
        List<SimpleGrantedAuthority> authorities = seeds.allPermissions().stream()
                .map(SimpleGrantedAuthority::new).toList();
        SecurityContextHolder.getContext().setAuthentication(
                new TestingAuthenticationToken("search-setup", null, authorities));
        try {
            return TenantContext.callWith(tenant.tenantId(), tenant.orgPath(), action);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private String order(String[] all, String orderNo, String customer, String origin, String destination) {
        Outcome raised = kit.post(tenant, "/api/v1/orders", """
                {"orgUnitId":"%s","customerPartnerId":"%s","orderNo":"%s","originTerminalId":"%s"}"""
                .formatted(tenant.orgUnitId(), customer, orderNo, origin), all);
        assertThat(raised.status()).as(raised.body()).isEqualTo(201);
        String id = kit.parse(raised).get("id").asString();
        Outcome line = kit.post(tenant, "/api/v1/orders/" + id + "/lines", """
                {"lineNo":1,"materialCode":"COIL","materialDescription":"Coil","materialClass":"GENERAL",
                 "quantity":1,"uom":"EA","deadWeightKg":1000,"consigneePartnerId":"%s",
                 "destinationTerminalId":"%s"}""".formatted(customer, destination), all);
        assertThat(line.status()).as(line.body()).isEqualTo(201);
        Outcome validated = kit.post(tenant, "/api/v1/orders/" + id + "/validate", "{}", all);
        assertThat(validated.status()).as(validated.body()).isBetween(200, 204);
        return id;
    }

    private List<String> codesAs(String[] rights, String path, String field) {
        List<String> out = new ArrayList<>();
        kit.parse(ok(rights, path)).get("items").forEach(item -> out.add(item.get(field).asString()));
        return out;
    }

    private Outcome ok(String[] rights, String path) {
        Outcome outcome = kit.get(tenant, path, rights);
        assertThat(outcome.status()).as(path + " -> " + outcome.body()).isEqualTo(200);
        return outcome;
    }
}
