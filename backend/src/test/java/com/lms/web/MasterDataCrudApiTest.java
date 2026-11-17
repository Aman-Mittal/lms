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

import java.net.URI;
import java.net.http.HttpRequest;
import java.util.UUID;

import com.lms.acceptance.SpineFixtures;
import com.lms.acceptance.TestFixtures;
import com.lms.execution.command.TripCommandService;
import com.lms.identity.security.TokenService;
import com.lms.masterdata.command.FleetCommandService;
import com.lms.masterdata.command.TerminalCommandService;
import com.lms.performance.TripScaffold;
import com.lms.web.ApiTestKit.Outcome;
import com.lms.web.ApiTestKit.Tenant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Maintaining partners, vehicles, drivers and terminals over HTTP (issue 238): edits under
 * {@code If-Match}, switching off and on with a reason, and what an inactive record refuses.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class MasterDataCrudApiTest {

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

    private static final String[] ALL = {
        "PARTNER_CREATE", "PARTNER_READ", "PARTNER_UPDATE", "VEHICLE_CREATE", "VEHICLE_READ", "VEHICLE_UPDATE",
        "DRIVER_CREATE", "DRIVER_READ", "DRIVER_UPDATE", "TERMINAL_CREATE", "TERMINAL_READ", "TERMINAL_UPDATE",
        "TERMINAL_DELETE", "TRIP_EXECUTE", "TRIP_READ", "LOAD_CREATE", "LOAD_READ"};

    @LocalServerPort
    private int port;
    @Autowired
    private TestFixtures fixtures;
    @Autowired
    private SpineFixtures spine;
    @Autowired
    private FleetCommandService fleet;
    @Autowired
    private TerminalCommandService terminals;
    @Autowired
    private TripCommandService trips;
    @Autowired
    private TokenService tokens;
    @Autowired
    private JdbcClient jdbc;

    private ApiTestKit kit;
    private TripScaffold.Built built;
    private Tenant tenant;
    private String salt;

    @BeforeEach
    void aTenantWithATrip() {
        kit = new ApiTestKit(fixtures, tokens, port);
        built = TripScaffold.build(fixtures, spine, fleet, terminals, trips, 20.0);
        tenant = kit.tenantOf(built.tenantId(), built.orgUnitId(), built.orgPath());
        salt = UUID.randomUUID().toString().substring(0, 6).toUpperCase();
    }

    // --------------------------------------------------------------- partners

    @Test
    @DisplayName("a partner is edited under If-Match; a stale version is 409 concurrent-modification and changes nothing")
    void partnerEdit() {
        UUID id = create("/api/v1/partners", """
                {"orgUnitId":"%s","code":"P-%s","legalName":"First name","partnerType":"CUSTOMER"}
                """.formatted(tenant.orgUnitId(), salt));
        Outcome read = send("GET", "/api/v1/partners/" + id, null, null);
        assertThat(read.status()).isEqualTo(200);
        assertThat(kit.parse(read).get("version").asLong()).isZero();

        assertThat(send("PUT", "/api/v1/partners/" + id, "\"0\"",
                "{\"legalName\":\"Second name\",\"taxId\":\"T-1\",\"paymentTerms\":\"NET30\"}").status())
                .isEqualTo(204);
        JsonNode edited = kit.parse(send("GET", "/api/v1/partners/" + id, null, null));
        assertThat(edited.get("legalName").asString()).isEqualTo("Second name");
        assertThat(edited.get("paymentTerms").asString()).isEqualTo("NET30");
        assertThat(edited.get("version").asLong()).isEqualTo(1);

        Outcome stale = send("PUT", "/api/v1/partners/" + id, "\"0\"", "{\"legalName\":\"Lost update\"}");
        assertThat(stale.status()).isEqualTo(409);
        assertThat(stale.code()).isEqualTo("concurrent-modification");
        assertThat(kit.parse(send("GET", "/api/v1/partners/" + id, null, null)).get("legalName").asString())
                .isEqualTo("Second name");

        assertThat(send("PUT", "/api/v1/partners/" + id, null, "{\"legalName\":\"No header\"}").status())
                .as("an edit without If-Match is a 400, not a silent overwrite").isEqualTo(400);
        assertThat(send("PUT", "/api/v1/partners/" + id, "\"1\"", "{\"legalName\":\" \"}").status()).isEqualTo(400);
        assertThat(auditCount("PARTNER_UPDATED", id)).isEqualTo(1);
    }

    @Test
    @DisplayName("suspending and blacklisting need a reason; the default list is the working partners, status=ALL is everyone")
    void partnerTransitionsAndLists() {
        UUID id = create("/api/v1/partners", """
                {"orgUnitId":"%s","code":"T-%s","legalName":"Transitions","partnerType":"VENDOR"}
                """.formatted(tenant.orgUnitId(), salt));
        transition(id, "PENDING_VERIFICATION", null, 204);
        transition(id, "ACTIVE", null, 204);

        assertThat(transition(id, "SUSPENDED", null, 400).code()).isNotNull();
        assertThat(transition(id, "SUSPENDED", " ", 400).status()).isEqualTo(400);
        assertThat(status("business_partner", id)).isEqualTo("ACTIVE");
        transition(id, "SUSPENDED", "credit review", 204);
        assertThat(auditReason("PARTNER_SUSPENDED", id)).isEqualTo("credit review");

        assertThat(listed("/api/v1/partners?q=T-" + salt)).doesNotContain(id.toString());
        assertThat(listed("/api/v1/partners?status=ALL&q=T-" + salt)).contains(id.toString());
        assertThat(listed("/api/v1/partners?status=SUSPENDED&q=T-" + salt)).contains(id.toString());

        assertThat(transition(id, "ACTIVE", "", 400).status()).as("reactivation needs a reason").isEqualTo(400);
        transition(id, "ACTIVE", "review passed", 204);
        transition(id, "BLACKLISTED", "fraud", 204);
        Outcome terminal = transition(id, "ACTIVE", "second thoughts", 409);
        assertThat(terminal.code()).isEqualTo("partner-illegal-transition");
    }

    // --------------------------------------------------------------- vehicles

    @Test
    @DisplayName("a vehicle is edited under If-Match, switched off with a reason, refused at assignment while off, and switched on")
    void vehicleLifecycle() {
        UUID id = vehicle("V-" + salt);
        UUID driver = driver("D-" + salt);

        assertThat(send("PUT", "/api/v1/vehicles/" + id, "\"0\"", vehicleBody(21000, "TRUCK_32FT")).status())
                .isEqualTo(204);
        Outcome stale = send("PUT", "/api/v1/vehicles/" + id, "\"0\"", vehicleBody(22000, "LOST"));
        assertThat(stale.status()).isEqualTo(409);
        assertThat(stale.code()).isEqualTo("concurrent-modification");
        JsonNode read = kit.parse(send("GET", "/api/v1/vehicles/" + id, null, null));
        assertThat(read.get("vehicleType").asString()).isEqualTo("TRUCK_32FT");
        assertThat(read.get("version").asLong()).isEqualTo(1);
        assertThat(send("PUT", "/api/v1/vehicles/" + id, "\"1\"", vehicleBody(5000, "BAD")).status())
                .as("tare 8000 is not below gross 5000").isEqualTo(400);

        assertThat(send("POST", "/api/v1/vehicles/" + id + "/deactivate", null, "{\"reason\":\" \"}").status())
                .isEqualTo(400);
        assertThat(send("POST", "/api/v1/vehicles/" + id + "/deactivate", "\"0\"", "{\"reason\":\"sold\"}").code())
                .as("a stale If-Match on a transition").isEqualTo("concurrent-modification");
        assertThat(send("POST", "/api/v1/vehicles/" + id + "/deactivate", "\"1\"", "{\"reason\":\"sold\"}").status())
                .isEqualTo(204);
        assertThat(status("vehicle", id)).isEqualTo("INACTIVE");
        assertThat(auditReason("VEHICLE_DEACTIVATED", id)).isEqualTo("sold");
        assertThat(send("POST", "/api/v1/vehicles/" + id + "/deactivate", null, "{\"reason\":\"again\"}").code())
                .isEqualTo("vehicle-already-inactive");

        assertThat(listed("/api/v1/vehicles?q=V-" + salt)).doesNotContain(id.toString());
        assertThat(listed("/api/v1/vehicles?status=ALL&q=V-" + salt)).contains(id.toString());
        assertThat(listed("/api/v1/vehicles?status=INACTIVE&q=V-" + salt)).contains(id.toString());

        Outcome refused = assign(id, driver);
        assertThat(refused.status()).isEqualTo(409);
        assertThat(refused.code()).isEqualTo("vehicle-inactive");

        assertThat(send("POST", "/api/v1/vehicles/" + id + "/reactivate", null, "{\"reason\":\"bought back\"}")
                .status()).isEqualTo(204);
        assertThat(status("vehicle", id)).isEqualTo("AVAILABLE");
        assertThat(send("POST", "/api/v1/vehicles/" + id + "/reactivate", null, "{\"reason\":\"x\"}").code())
                .isEqualTo("vehicle-not-inactive");
        assertThat(assign(id, driver).code()).as("no longer refused as inactive").isNotEqualTo("vehicle-inactive");
    }

    @Test
    @DisplayName("an inactive vehicle cannot be opened on a load either")
    void inactiveVehicleCannotBePlanned() {
        UUID id = vehicle("L-" + salt);
        send("POST", "/api/v1/vehicles/" + id + "/deactivate", null, "{\"reason\":\"off\"}");
        Outcome refused = kit.post(tenant, "/api/v1/loads", """
                {"orgUnitId":"%s","loadNo":"LD-%s","originTerminalId":"%s","vehicleId":"%s"}
                """.formatted(tenant.orgUnitId(), salt, originOf(built.tripId()), id), ALL);
        assertThat(refused.status()).isEqualTo(409);
        assertThat(refused.code()).isEqualTo("vehicle-inactive");
    }

    // ---------------------------------------------------------------- drivers

    @Test
    @DisplayName("a driver is edited under If-Match, switched off with a reason, refused at assignment while off, and switched on")
    void driverLifecycle() {
        UUID id = driver("DL-" + salt);
        UUID vehicle = vehicle("DV-" + salt);

        assertThat(send("PUT", "/api/v1/drivers/" + id, "\"0\"", driverBody("New Name", "9222222222")).status())
                .isEqualTo(204);
        Outcome stale = send("PUT", "/api/v1/drivers/" + id, "\"0\"", driverBody("Lost", "9"));
        assertThat(stale.status()).isEqualTo(409);
        assertThat(stale.code()).isEqualTo("concurrent-modification");
        assertThat(kit.parse(send("GET", "/api/v1/drivers/" + id, null, null)).get("fullName").asString())
                .isEqualTo("New Name");

        assertThat(send("POST", "/api/v1/drivers/" + id + "/deactivate", null, "{}").status()).isEqualTo(400);
        assertThat(send("POST", "/api/v1/drivers/" + id + "/deactivate", null, "{\"reason\":\"left\"}").status())
                .isEqualTo(204);
        assertThat(status("driver", id)).isEqualTo("INACTIVE");
        assertThat(listed("/api/v1/drivers?q=DL-" + salt)).doesNotContain(id.toString());
        assertThat(listed("/api/v1/drivers?status=ALL&q=DL-" + salt)).contains(id.toString());

        Outcome refused = assign(vehicle, id);
        assertThat(refused.status()).isEqualTo(409);
        assertThat(refused.code()).isEqualTo("driver-inactive");

        assertThat(send("POST", "/api/v1/drivers/" + id + "/reactivate", null, "{\"reason\":\"back\"}").status())
                .isEqualTo(204);
        assertThat(status("driver", id)).isEqualTo("AVAILABLE");
        assertThat(assign(vehicle, id).code()).isNotEqualTo("driver-inactive");
    }

    @Test
    @DisplayName("driver edits and switching off need DRIVER_UPDATE")
    void driverUpdateIsGuarded() {
        UUID id = driver("G-" + salt);
        Outcome forbidden = sendAs(new String[] {"DRIVER_READ", "VEHICLE_UPDATE"}, "POST",
                "/api/v1/drivers/" + id + "/deactivate", null, "{\"reason\":\"x\"}");
        assertThat(forbidden.status()).isEqualTo(403);
    }

    // -------------------------------------------------------------- terminals

    @Test
    @DisplayName("a terminal is edited under If-Match; a free one is switched off and on, one with open work is 409 terminal-in-use")
    void terminalLifecycle() {
        UUID free = create("/api/v1/terminals/point-radius", """
                {"orgUnitId":"%s","code":"T-%s","name":"Spare yard","category":"CUSTOMER_SITE",
                 "centre":{"lat":10.5,"lon":78.5},"radiusMetres":300}
                """.formatted(tenant.orgUnitId(), salt));

        assertThat(send("PUT", "/api/v1/terminals/" + free, "\"0\"", "{\"name\":\"Spare yard 2\",\"version\":0}")
                .status()).isEqualTo(204);
        Outcome stale = send("PUT", "/api/v1/terminals/" + free, "\"0\"", "{\"name\":\"Lost\",\"version\":0}");
        assertThat(stale.status()).isEqualTo(409);
        assertThat(stale.code()).isEqualTo("concurrent-modification");
        assertThat(send("PUT", "/api/v1/terminals/" + free, null, "{\"name\":\"Body version\",\"version\":1}")
                .status()).as("the body's version still works for existing callers").isEqualTo(204);

        UUID origin = originOf(built.tripId());
        Outcome busy = send("POST", "/api/v1/terminals/" + origin + "/deactivate", null, "{\"reason\":\"closing\"}");
        assertThat(busy.status()).isEqualTo(409);
        assertThat(busy.code()).isEqualTo("terminal-in-use");
        assertThat(status("terminal", origin)).isEqualTo("ACTIVE");

        assertThat(send("POST", "/api/v1/terminals/" + free + "/deactivate", null, "{\"reason\":\" \"}").status())
                .isEqualTo(400);
        assertThat(send("POST", "/api/v1/terminals/" + free + "/deactivate", null, "{\"reason\":\"closed\"}")
                .status()).isEqualTo(204);
        assertThat(auditReason("TERMINAL_DEACTIVATED", free)).isEqualTo("closed");
        assertThat(listed("/api/v1/terminals?q=T-" + salt)).doesNotContain(free.toString());
        assertThat(listed("/api/v1/terminals?status=ALL&q=T-" + salt)).contains(free.toString());
        assertThat(send("POST", "/api/v1/terminals/" + free + "/deactivate", null, "{\"reason\":\"again\"}").code())
                .isEqualTo("terminal-already-inactive");

        // Its ground is free while it is off, and it cannot come back over a terminal that took it.
        UUID taker = create("/api/v1/terminals/point-radius", """
                {"orgUnitId":"%s","code":"U-%s","name":"Took the ground","category":"CUSTOMER_SITE",
                 "centre":{"lat":10.5,"lon":78.5},"radiusMetres":300}
                """.formatted(tenant.orgUnitId(), salt));
        Outcome overlap = send("POST", "/api/v1/terminals/" + free + "/reactivate", null, "{\"reason\":\"reopen\"}");
        assertThat(overlap.code()).isEqualTo("terminal-overlap");
        send("POST", "/api/v1/terminals/" + taker + "/deactivate", null, "{\"reason\":\"moved\"}");
        assertThat(send("POST", "/api/v1/terminals/" + free + "/reactivate", null, "{\"reason\":\"reopen\"}")
                .status()).isEqualTo(204);
        assertThat(status("terminal", free)).isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("once the work that named a terminal is finished it can be switched off")
    void terminalFreeOnceWorkIsDone() {
        UUID origin = originOf(built.tripId());
        assertThat(send("POST", "/api/v1/terminals/" + origin + "/deactivate", null, "{\"reason\":\"x\"}").code())
                .isEqualTo("terminal-in-use");
        jdbc.sql("UPDATE trip SET status = 'CANCELLED' WHERE tenant_id = :t").param("t", built.tenantId()).update();
        jdbc.sql("UPDATE load_unit SET status = 'CANCELLED' WHERE tenant_id = :t").param("t", built.tenantId()).update();
        jdbc.sql("UPDATE sales_order SET status = 'CANCELLED' WHERE tenant_id = :t").param("t", built.tenantId()).update();
        Outcome now = send("POST", "/api/v1/terminals/" + origin + "/deactivate", null, "{\"reason\":\"closed\"}");
        assertThat(now.status()).as(now.body()).isEqualTo(204);
    }

    // ---------------------------------------------------------------- helpers

    private UUID vehicle(String registration) {
        return create("/api/v1/vehicles", """
                {"orgUnitId":"%s","registrationNo":"%s","category":"TRUCK","vehicleType":"TRUCK_20FT",
                 "grossWeightKg":18000,"tareWeightKg":6000,"maxVolumeM3":30}
                """.formatted(tenant.orgUnitId(), registration));
    }

    private static String vehicleBody(int gross, String type) {
        return """
                {"category":"TRUCK","vehicleType":"%s","grossWeightKg":%d,"tareWeightKg":8000,"maxVolumeM3":40,
                 "hazmatCertified":false,"reeferCapable":false}
                """.formatted(type, gross);
    }

    private UUID driver(String licence) {
        return create("/api/v1/drivers", """
                {"orgUnitId":"%s","fullName":"Test Driver","phone":"9111111111","licenceNo":"%s",
                 "licenceClass":"HMV","licenceExpiresOn":"2040-01-01","hazmatEndorsed":false}
                """.formatted(tenant.orgUnitId(), licence));
    }

    private static String driverBody(String name, String phone) {
        return """
                {"fullName":"%s","phone":"%s","licenceClass":"HMV","licenceExpiresOn":"2040-01-01",
                 "hazmatEndorsed":false}
                """.formatted(name, phone);
    }

    private Outcome assign(UUID vehicleId, UUID driverId) {
        return send("POST", "/api/v1/trips/" + built.tripId() + "/assignment", null,
                "{\"vehicleId\":\"%s\",\"driverId\":\"%s\"}".formatted(vehicleId, driverId));
    }

    private Outcome transition(UUID id, String target, String reason, int expected) {
        String body = reason == null ? "{\"target\":\"%s\"}".formatted(target)
                : "{\"target\":\"%s\",\"reason\":\"%s\"}".formatted(target, reason);
        Outcome outcome = send("POST", "/api/v1/partners/" + id + "/transitions", null, body);
        assertThat(outcome.status()).as(outcome.body()).isEqualTo(expected);
        return outcome;
    }

    private String listed(String path) {
        Outcome outcome = send("GET", path, null, null);
        assertThat(outcome.status()).as(outcome.body()).isEqualTo(200);
        return outcome.body();
    }

    private UUID create(String path, String body) {
        Outcome created = send("POST", path, null, body);
        assertThat(created.status()).as(created.body()).isEqualTo(201);
        return UUID.fromString(kit.parse(created).get("id").asString());
    }

    private UUID originOf(UUID tripId) {
        return jdbc.sql("SELECT origin_terminal_id FROM trip WHERE id = :id")
                .param("id", tripId).query(UUID.class).single();
    }

    private Outcome send(String method, String path, String ifMatch, String body) {
        return sendAs(ALL, method, path, ifMatch, body);
    }

    private Outcome sendAs(String[] permissions, String method, String path, String ifMatch, String body) {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Authorization", "Bearer " + kit.token(tenant, permissions));
        if (body != null) {
            request.header("Content-Type", "application/json");
        }
        if (ifMatch != null) {
            request.header("If-Match", ifMatch);
        }
        return kit.send(request.method(method, body == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body)).build());
    }

    private String status(String table, UUID id) {
        return jdbc.sql("SELECT status FROM " + table + " WHERE id = :id")
                .param("id", id).query(String.class).single();
    }

    private String auditReason(String action, UUID id) {
        return jdbc.sql("SELECT after_state ->> 'reason' FROM audit_log WHERE action = :a AND resource_id = :id")
                .param("a", action).param("id", id).query(String.class).single();
    }

    private long auditCount(String action, UUID resourceId) {
        return jdbc.sql("SELECT count(*) FROM audit_log WHERE action = :a AND resource_id = :id")
                .param("a", action).param("id", resourceId).query(Long.class).single();
    }
}
