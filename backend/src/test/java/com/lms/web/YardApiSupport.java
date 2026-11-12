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
import java.net.http.HttpRequest;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import com.lms.acceptance.SpineFixtures;
import com.lms.acceptance.TestFixtures;
import com.lms.execution.command.TripCommandService;
import com.lms.identity.security.TokenService;
import com.lms.masterdata.command.FleetCommandService;
import com.lms.masterdata.command.RegisterTerminalCommand;
import com.lms.masterdata.command.TerminalCommandService;
import com.lms.masterdata.command.domain.BusinessPartner;
import com.lms.masterdata.command.domain.Terminal;
import com.lms.settings.api.SettingKey;
import com.lms.settings.api.Settings;
import com.lms.shared.geo.LatLon;
import com.lms.shared.tenant.TenantContext;
import com.lms.web.ApiTestKit.Outcome;
import com.lms.web.ApiTestKit.Tenant;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * What the yard's API tests (gate register and passes, trip stops and corridors,
 * issue #223) share: one Postgres and one application for every subclass, a
 * tenant per test with three depots on a line (origin, a drop on the way, the
 * destination), a lorry and a driver, and the HTTP calls to take a trip to
 * dispatch and to report positions.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
abstract class YardApiSupport {

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
        // Deliveries are read from the outbox, not posted anywhere.
        registry.add("lms.webhooks.dispatcher.enabled", () -> "false");
    }

    /** Depots on a diagonal, about 15 km apart, each with a 500 m geofence. */
    static final double[] ORIGIN_AT = {19.10, 72.90};
    static final double[] MID_AT = {19.20, 73.00};
    static final double[] DEST_AT = {19.30, 73.10};

    private static final List<String> SETUP = List.of(
            "PARTNER_CREATE", "PARTNER_UPDATE", "VEHICLE_CREATE", "DRIVER_CREATE", "TERMINAL_CREATE", "ORDER_CREATE",
            "ORDER_UPDATE", "LOAD_CREATE", "LOAD_ALLOCATE", "TRIP_EXECUTE", "GATE_REGISTER", "GATE_PASS_MANAGE");

    /** One test's world. */
    record Yard(Tenant tenant, UUID origin, UUID mid, UUID dest, UUID vehicle, String registration, UUID driver,
                UUID customer, UUID consignee, UUID vendor) {

        UUID tenantId() {
            return tenant.tenantId();
        }

        UUID orgUnitId() {
            return tenant.orgUnitId();
        }
    }

    @LocalServerPort
    protected int port;
    @Autowired
    protected TestFixtures fixtures;
    @Autowired
    protected SpineFixtures spine;
    @Autowired
    protected FleetCommandService fleet;
    @Autowired
    protected TerminalCommandService terminals;
    @Autowired
    protected TripCommandService trips;
    @Autowired
    protected TokenService tokens;
    @Autowired
    protected JdbcClient jdbc;
    @Autowired
    protected Settings settings;
    @Autowired
    protected PlatformTransactionManager transactionManager;

    protected ApiTestKit kit;
    protected Yard yard;

    @BeforeEach
    void aYard() {
        kit = new ApiTestKit(fixtures, tokens, port);
        yard = newYard();
    }

    // ----------------------------------------------------------------- setup

    protected Yard newYard() {
        Tenant tenant = kit.newTenant("yard");
        String salt = UUID.randomUUID().toString().substring(0, 6);
        UUID unit = tenant.orgUnitId();
        return asSetup(tenant, () -> {
            UUID customer = fleet.registerPartner(unit, "C-" + salt, "Customer", BusinessPartner.PartnerType.CUSTOMER,
                    "T-C-" + salt);
            UUID consignee = fleet.registerPartner(unit, "K-" + salt, "Consignee",
                    BusinessPartner.PartnerType.CUSTOMER, "T-K-" + salt);
            UUID vendor = fleet.registerPartner(unit, "V-" + salt, "Vendor", BusinessPartner.PartnerType.VENDOR,
                    "T-V-" + salt);
            fleet.transitionPartner(vendor, BusinessPartner.PartnerStatus.PENDING_VERIFICATION);
            fleet.transitionPartner(vendor, BusinessPartner.PartnerStatus.ACTIVE);
            UUID origin = depot(unit, "O-" + salt, ORIGIN_AT);
            UUID mid = depot(unit, "M-" + salt, MID_AT);
            UUID dest = depot(unit, "D-" + salt, DEST_AT);
            String registration = "MH 12 YD " + salt.substring(0, 4);
            UUID vehicle = fleet.registerVehicle(unit, null, registration, "TRUCK", "RIGID", "2-AXLE",
                    new BigDecimal("26000"), new BigDecimal("6000"), new BigDecimal("60"), false, false);
            UUID driver = fleet.registerDriver(unit, null, "Ravi", "+910000000001", "DL-" + salt, "HEAVY", "RTO",
                    LocalDate.now(ZoneOffset.UTC).plusDays(365), false);
            return new Yard(tenant, origin, mid, dest, vehicle, registration, driver, customer, consignee, vendor);
        });
    }

    private UUID depot(UUID unit, String code, double[] at) {
        return terminals.register(new RegisterTerminalCommand.PointRadius(unit, code, code,
                Terminal.FunctionalCategory.CUSTOMER_SITE, new LatLon(at[0], at[1]), 500, 2, null, null, 45,
                List.of()));
    }

    /** A trip with its stops derived from a load that drops at the middle depot and then the destination. */
    protected UUID raiseMultiDropTrip() {
        return asSetup(yard.tenant(), () -> {
            String no = UUID.randomUUID().toString().substring(0, 8);
            UUID load = spine.awardedMultiDropLoad(yard.orgUnitId(), "L-" + no, yard.customer(), yard.origin(),
                    yard.consignee(), List.of(yard.mid(), yard.dest()), yard.vehicle(), yard.vendor(),
                    new BigDecimal("5000"));
            return trips.raiseTrip("TR-" + no, load, Instant.now());
        });
    }

    protected UUID raiseSingleDropTrip() {
        return asSetup(yard.tenant(), () -> {
            String no = UUID.randomUUID().toString().substring(0, 8);
            UUID load = spine.awardedLoad(yard.orgUnitId(), "L-" + no, yard.customer(), yard.origin(),
                    yard.consignee(), yard.dest(), yard.vehicle(), yard.vendor(), new BigDecimal("5000"), false);
            return trips.raiseTrip("TR-" + no, load, Instant.now());
        });
    }

    protected <T> T asSetup(Tenant tenant, Supplier<T> action) {
        Set<SimpleGrantedAuthority> authorities = SETUP.stream().map(SimpleGrantedAuthority::new)
                .collect(Collectors.toSet());
        SecurityContextHolder.getContext().setAuthentication(
                new TestingAuthenticationToken("yard-setup", null, authorities));
        try {
            return TenantContext.callWith(tenant.tenantId(), tenant.orgPath(), action);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    /** Sets organisation-default settings in a transaction of their own (the port joins one, ADR 0006). */
    protected void setting(Map<SettingKey<?>, Object> values) {
        asSetup(yard.tenant(), () -> new TransactionTemplate(transactionManager).execute(status -> {
            settings.setOrgDefaults(values);
            return null;
        }));
    }

    // ------------------------------------------------------------------ HTTP

    protected static final String TRIP_RIGHTS = "TRIP_EXECUTE";

    protected Outcome post(String path, String body, String... permissions) {
        return kit.post(yard.tenant(), path, body, permissions);
    }

    protected Outcome get(String path, String... permissions) {
        return kit.get(yard.tenant(), path, permissions);
    }

    protected Outcome send(String method, String path, String body, String ifMatch, String... permissions) {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + kit.token(yard.tenant(), permissions))
                .method(method, HttpRequest.BodyPublishers.ofString(body == null ? "" : body));
        if (ifMatch != null) {
            request.header("If-Match", ifMatch);
        }
        return kit.send(request.build());
    }

    /** The ETag of a pass or the stops of a trip, read the way a console does. */
    protected String etagOf(String path, String... permissions) {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Authorization", "Bearer " + kit.token(yard.tenant(), permissions)).GET().build();
        try {
            return java.net.http.HttpClient.newHttpClient()
                    .send(request, java.net.http.HttpResponse.BodyHandlers.ofString()).headers()
                    .firstValue("ETag").orElseThrow();
        } catch (java.io.IOException | InterruptedException e) {
            throw new AssertionError(e);
        }
    }

    protected String id(Outcome outcome) {
        return kit.parse(outcome).get("id").asString();
    }

    protected static String json(String... pairs) {
        StringBuilder body = new StringBuilder("{");
        for (int i = 0; i < pairs.length; i += 2) {
            body.append(i == 0 ? "" : ",").append('"').append(pairs[i]).append("\":").append(pairs[i + 1]);
        }
        return body.append('}').toString();
    }

    protected static String q(Object value) {
        return "\"" + value + "\"";
    }

    // ---------------------------------------------------------- trip driving

    /** Assigns the lorry, gates in at the origin, weighs, loads, attaches the papers and dispatches. */
    protected void dispatch(UUID tripId, Instant gateInAt, Instant dispatchedAt) {
        String base = "/api/v1/trips/" + tripId;
        assertCreatedOrOk(post(base + "/assignment", json("vehicleId", q(yard.vehicle()), "driverId", q(yard.driver())),
                TRIP_RIGHTS));
        assertCreatedOrOk(post(base + "/gate-in", json("terminalId", q(yard.origin()), "at", q(gateInAt)), TRIP_RIGHTS));
        assertCreatedOrOk(post(base + "/weighings", json("terminalId", q(yard.origin()), "type", q("TARE"),
                "weightKg", "6000"), TRIP_RIGHTS));
        assertCreatedOrOk(post(base + "/weighings", json("terminalId", q(yard.origin()), "type", q("GROSS"),
                "weightKg", "11000"), TRIP_RIGHTS));
        assertCreatedOrOk(post(base + "/loaded", "{}", TRIP_RIGHTS));
        assertCreatedOrOk(post(base + "/documents", json("documentType", q("CONSIGNMENT_NOTE"), "documentRef", q("LR-1")),
                TRIP_RIGHTS));
        assertCreatedOrOk(post(base + "/documents", json("documentType", q("EWAY_BILL"), "documentRef", q("EW-1")),
                TRIP_RIGHTS));
        assertCreatedOrOk(post(base + "/dispatch", json("at", q(dispatchedAt)), TRIP_RIGHTS));
    }

    private static void assertCreatedOrOk(Outcome outcome) {
        if (outcome.status() >= 300) {
            throw new AssertionError("Setup call failed: " + outcome.status() + " " + outcome.body());
        }
    }

    /** One position of the yard's lorry. */
    protected Outcome ping(double[] at, Instant recordedAt) {
        return ping(at[0], at[1], recordedAt);
    }

    protected Outcome ping(double lat, double lon, Instant recordedAt) {
        return post("/api/v1/telematics/pings", "{\"pings\":[" + json("vehicleId", q(yard.vehicle()),
                "recordedAt", q(recordedAt), "lat", String.valueOf(lat), "lon", String.valueOf(lon),
                "speedKph", "40") + "]}", "TELEMATICS_INGEST");
    }

    /** The vehicle drives O, off the road, M, off the road, D, as hours ago, and every report is accepted. */
    protected void driveTo(Instant start, boolean throughMid, boolean intoDestination) {
        Instant t = start;
        assertAccepted(ping(ORIGIN_AT, t));
        assertAccepted(ping(19.15, 72.95, t = t.plus(Duration.ofMinutes(20))));
        if (throughMid) {
            assertAccepted(ping(MID_AT, t = t.plus(Duration.ofMinutes(20))));
            assertAccepted(ping(19.25, 73.05, t = t.plus(Duration.ofMinutes(20))));
        }
        if (intoDestination) {
            assertAccepted(ping(DEST_AT, t.plus(Duration.ofMinutes(20))));
        }
    }

    private void assertAccepted(Outcome outcome) {
        if (outcome.status() != 202 || !outcome.body().contains("\"accepted\":1")) {
            throw new AssertionError("Position not accepted: " + outcome.status() + " " + outcome.body());
        }
    }
}
