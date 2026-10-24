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
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;

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
 * Cancelling a trip, maintaining a terminal, and the audit trail execution
 * leaves -- over HTTP, with the exact status and problem code on the wire.
 *
 * <p>Written first, against the code as it stood: there was no way to cancel a
 * trip ({@code Trip.cancel()} had no caller), a terminal could be created and
 * read but never changed or removed, and the trip command service wrote no
 * audit records. Each test here failed then, and says what it expects now.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TripCancelTerminalMaintenanceApiTest {

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

    @BeforeEach
    void aTenantWithATrip() {
        kit = new ApiTestKit(fixtures, tokens, port);
        built = TripScaffold.build(fixtures, spine, fleet, terminals, trips, 0.0);
        tenant = kit.tenantOf(built.tenantId(), built.orgUnitId(), built.orgPath());
    }

    // ------------------------------------------------------------ trip cancel

    @Test
    @DisplayName("a planned trip is cancelled with a recorded reason, and cancelling again changes nothing")
    void cancelRecordsTheReasonAndIsIdempotent() {
        assertThat(cancel(built.tripId(), "vehicle broke down", "TRIP_EXECUTE").status())
                .isEqualTo(204);

        JsonNode trip = kit.parse(kit.get(tenant, "/api/v1/trips/" + built.tripId(), "TRIP_READ"));
        assertThat(trip.get("status").asString()).isEqualTo("CANCELLED");
        assertThat(trip.get("cancellationReason").asString()).isEqualTo("vehicle broke down");
        assertThat(trip.get("cancelledAt").asString()).isNotBlank();

        // A retry whose first answer was lost: same outcome, nothing new written.
        assertThat(cancel(built.tripId(), "a different reason", "TRIP_EXECUTE").status())
                .isEqualTo(204);
        JsonNode again = kit.parse(kit.get(tenant, "/api/v1/trips/" + built.tripId(), "TRIP_READ"));
        assertThat(again.get("cancellationReason").asString()).isEqualTo("vehicle broke down");
        assertThat(auditCount("TRIP_CANCELLED", built.tripId())).isEqualTo(1);
    }

    @Test
    @DisplayName("twelve cancellations of one trip at once: a 204 or a 409, never a 500; cancelled and audited once")
    void concurrentCancels() {
        List<Callable<Outcome>> calls = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            calls.add(() -> cancel(built.tripId(), "raced", "TRIP_EXECUTE"));
        }

        List<Outcome> outcomes = ApiTestKit.race(calls, Duration.ofSeconds(60));

        assertThat(outcomes).extracting(Outcome::status).doesNotContain(500)
                .containsAnyOf(204).isSubsetOf(204, 409);
        assertThat(outcomes).filteredOn(o -> o.status() == 409)
                .allSatisfy(o -> assertThat(o.code()).isEqualTo("concurrent-modification"));
        assertThat(status(built.tripId())).isEqualTo("CANCELLED");
        assertThat(auditCount("TRIP_CANCELLED", built.tripId())).isEqualTo(1);
    }

    @Test
    @DisplayName("a cancellation without a reason is a 400")
    void cancelNeedsAReason() {
        assertThat(cancelRaw(built.tripId(), "{}", "TRIP_EXECUTE").status()).isEqualTo(400);
        assertThat(cancel(built.tripId(), "  ", "TRIP_EXECUTE").status()).isEqualTo(400);
    }

    @Test
    @DisplayName("a trip that has left the yard cannot be cancelled: 409 trip-not-cancellable, state untouched")
    void cancelRefusedOnceOnTheRoad() {
        for (String status : new String[] {"DISPATCHED", "IN_TRANSIT", "AT_DESTINATION", "COMPLETED"}) {
            jdbc.sql("UPDATE trip SET status = :s WHERE id = :id")
                    .param("s", status).param("id", built.tripId()).update();

            Outcome refused = cancel(built.tripId(), "too late", "TRIP_EXECUTE");

            assertThat(refused.status()).as(status).isEqualTo(409);
            assertThat(refused.code()).as(status).isEqualTo("trip-not-cancellable");
            assertThat(status(built.tripId())).isEqualTo(status);
        }
    }

    @Test
    @DisplayName("cancelling needs TRIP_EXECUTE, and another tenant's trip reads as absent")
    void cancelIsGuarded() {
        assertThat(cancel(built.tripId(), "no", "TRIP_READ").status()).isEqualTo(403);

        Tenant stranger = kit.newTenant("stranger");
        assertThat(cancel(stranger, built.tripId(), "no", "TRIP_EXECUTE").status())
                .isIn(403, 404);
        assertThat(status(built.tripId())).isEqualTo("PLANNED");
    }

    @Test
    @DisplayName("a cancelled trip gives its load back: a replacement can be raised, a second live trip cannot")
    void cancelReleasesTheLoad() {
        UUID loadId = jdbc.sql("SELECT load_id FROM trip WHERE id = :id")
                .param("id", built.tripId()).query(UUID.class).single();

        Outcome blocked = raise("REPL-0", loadId);
        assertThat(blocked.status()).isEqualTo(409);
        assertThat(blocked.code()).isEqualTo("load-already-has-trip");

        assertThat(cancel(built.tripId(), "re-plan", "TRIP_EXECUTE").status()).isEqualTo(204);

        assertThat(loadStatus(loadId)).as("the vendor's award stands").isEqualTo("AWARDED");
        Outcome replacement = raise("REPL-1", loadId);
        assertThat(replacement.status()).isEqualTo(201);
        Outcome second = raise("REPL-2", loadId);
        assertThat(second.status()).isEqualTo(409);
        assertThat(second.code()).isEqualTo("load-already-has-trip");
    }

    @Test
    @DisplayName("a trip row that is CANCELLED no longer holds its load, whatever cancelled it")
    void aCancelledRowReleasesItsLoad() {
        UUID loadId = jdbc.sql("SELECT load_id FROM trip WHERE id = :id")
                .param("id", built.tripId()).query(UUID.class).single();
        jdbc.sql("UPDATE trip SET status = 'CANCELLED' WHERE id = :id")
                .param("id", built.tripId()).update();

        Outcome replacement = raise("REPL-DB", loadId);

        assertThat(replacement.status()).as(replacement.body()).isEqualTo(201);
    }

    @Test
    @DisplayName("a cancelled trip raises no freight bill and cannot be completed")
    void cancelledTripsAreNotBilled() {
        cancel(built.tripId(), "stood down", "TRIP_EXECUTE");

        assertThat(jdbc.sql("SELECT count(*) FROM freight_bill WHERE trip_id = :id")
                .param("id", built.tripId()).query(Long.class).single()).isZero();
        Outcome completion = kit.post(tenant, "/api/v1/trips/" + built.tripId() + "/completion",
                "{}", "TRIP_EXECUTE");
        assertThat(completion.status()).isEqualTo(409);
        assertThat(completion.code()).isEqualTo("trip-illegal-transition");
    }

    // -------------------------------------------------------- execution audit

    @Test
    @DisplayName("raising a trip leaves an audit row naming the trip, its load and its state")
    void raisingATripIsAudited() {
        assertThat(auditCount("TRIP_RAISED", built.tripId())).isEqualTo(1);
        String status = jdbc.sql("""
                SELECT after_state ->> 'status' FROM audit_log
                 WHERE resource_id = :id AND action = 'TRIP_RAISED'
                """).param("id", built.tripId()).query(String.class).single();
        assertThat(status).isEqualTo("PLANNED");
    }

    // -------------------------------------------------------- terminal update

    @Test
    @DisplayName("a terminal is updated in place; the code stays, the version moves, a stale version is a 409")
    void updateTerminal() {
        UUID id = createTerminal("UPD", 76.10, 10.0, "TERMINAL_CREATE");
        JsonNode before = kit.parse(kit.get(tenant, "/api/v1/terminals/" + id, "TERMINAL_READ"));
        long version = before.get("version").asLong();

        Outcome ok = updateTerminal(id, version, "Renamed yard", 9, "TERMINAL_UPDATE");
        assertThat(ok.status()).isEqualTo(204);

        JsonNode after = kit.parse(kit.get(tenant, "/api/v1/terminals/" + id, "TERMINAL_READ"));
        assertThat(after.get("name").asString()).isEqualTo("Renamed yard");
        assertThat(after.get("dockCount").asInt()).isEqualTo(9);
        assertThat(after.get("code").asString()).isEqualTo(before.get("code").asString());
        assertThat(after.get("version").asLong()).isGreaterThan(version);
        assertThat(auditCount("TERMINAL_UPDATED", id)).isEqualTo(1);

        Outcome stale = updateTerminal(id, version, "Lost update", 1, "TERMINAL_UPDATE");
        assertThat(stale.status()).isEqualTo(409);
        assertThat(stale.code()).isEqualTo("concurrent-modification");
        assertThat(kit.parse(kit.get(tenant, "/api/v1/terminals/" + id, "TERMINAL_READ"))
                .get("name").asString()).isEqualTo("Renamed yard");
    }

    @Test
    @DisplayName("moving a geofence onto a neighbour of the same category is a 409 terminal-overlap")
    void updateRefusesAnOverlap() {
        createTerminal("NBR", 76.30, 10.0, "TERMINAL_CREATE");
        UUID mine = createTerminal("MINE", 76.30, 10.05, "TERMINAL_CREATE");
        long version = kit.parse(kit.get(tenant, "/api/v1/terminals/" + mine, "TERMINAL_READ"))
                .get("version").asLong();

        String onto = """
                {"name":"Mine","version":%d,"centre":{"lat":76.30,"lon":10.0},"radiusMetres":300}
                """.formatted(version);
        Outcome refused = put("/api/v1/terminals/" + mine, onto, "TERMINAL_UPDATE");
        assertThat(refused.status()).isEqualTo(409);
        assertThat(refused.code()).isEqualTo("terminal-overlap");

        // Its own footprint is not a clash with itself.
        String nudged = """
                {"name":"Mine","version":%d,"centre":{"lat":76.30,"lon":10.0502},"radiusMetres":400}
                """.formatted(version);
        assertThat(put("/api/v1/terminals/" + mine, nudged, "TERMINAL_UPDATE").status())
                .isEqualTo(204);
    }

    @Test
    @DisplayName("updating and deleting need their own permissions, and another tenant's terminal reads as absent")
    void terminalMaintenanceIsGuarded() {
        UUID id = createTerminal("GRD", 76.50, 10.0, "TERMINAL_CREATE");

        assertThat(updateTerminal(id, 0, "x", 1, "TERMINAL_CREATE").status()).isEqualTo(403);
        assertThat(delete("/api/v1/terminals/" + id, "TERMINAL_CREATE").status()).isEqualTo(403);

        Tenant stranger = kit.newTenant("stranger");
        Outcome update = send(stranger, "PUT", "/api/v1/terminals/" + id,
                "{\"name\":\"x\",\"version\":0}", "TERMINAL_UPDATE");
        assertThat(update.status()).isIn(403, 404);
        assertThat(send(stranger, "DELETE", "/api/v1/terminals/" + id, null, "TERMINAL_DELETE")
                .status()).isIn(403, 404);
        assertThat(kit.get(tenant, "/api/v1/terminals/" + id, "TERMINAL_READ").status())
                .isEqualTo(200);
    }

    // -------------------------------------------------------- terminal delete

    @Test
    @DisplayName("an unreferenced terminal is deleted, once; the audit row keeps what it was")
    void deleteTerminal() {
        UUID id = createTerminal("DEL", 76.70, 10.0, "TERMINAL_CREATE");

        assertThat(delete("/api/v1/terminals/" + id, "TERMINAL_DELETE").status()).isEqualTo(204);

        assertThat(kit.get(tenant, "/api/v1/terminals/" + id, "TERMINAL_READ").status())
                .isEqualTo(404);
        assertThat(auditCount("TERMINAL_DELETED", id)).isEqualTo(1);
        assertThat(delete("/api/v1/terminals/" + id, "TERMINAL_DELETE").status()).isEqualTo(404);
    }

    @Test
    @DisplayName("a terminal a trip starts from cannot be deleted: 409 terminal-in-use, and it is still there")
    void deleteRefusedWhileReferenced() {
        UUID origin = jdbc.sql("SELECT origin_terminal_id FROM trip WHERE id = :id")
                .param("id", built.tripId()).query(UUID.class).single();

        Outcome refused = delete("/api/v1/terminals/" + origin, "TERMINAL_DELETE");

        assertThat(refused.status()).isEqualTo(409);
        assertThat(refused.code()).isEqualTo("terminal-in-use");
        assertThat(kit.get(tenant, "/api/v1/terminals/" + origin, "TERMINAL_READ").status())
                .isEqualTo(200);
    }

    // ---------------------------------------------------------------- helpers

    private Outcome cancel(UUID tripId, String reason, String... permissions) {
        return cancel(tenant, tripId, reason, permissions);
    }

    private Outcome cancel(Tenant as, UUID tripId, String reason, String... permissions) {
        return send(as, "POST", "/api/v1/trips/" + tripId + "/cancellation",
                "{\"reason\":\"" + reason + "\"}", permissions);
    }

    private Outcome cancelRaw(UUID tripId, String body, String... permissions) {
        return send(tenant, "POST", "/api/v1/trips/" + tripId + "/cancellation", body, permissions);
    }

    private Outcome raise(String tripNo, UUID loadId) {
        return kit.post(tenant, "/api/v1/trips",
                "{\"tripNo\":\"" + tripNo + "-" + built.tripId().toString().substring(0, 6)
                        + "\",\"loadId\":\"" + loadId + "\"}", "TRIP_EXECUTE");
    }

    private UUID createTerminal(String code, double lat, double lon, String permission) {
        String body = """
                {"orgUnitId":"%s","code":"%s-%s","name":"%s","category":"CUSTOMER_SITE",
                 "centre":{"lat":%s,"lon":%s},"radiusMetres":300,"dockCount":2,"avgDwellMinutes":45}
                """.formatted(tenant.orgUnitId(), code, built.tripId().toString().substring(0, 6),
                code, lat, lon);
        Outcome created = kit.post(tenant, "/api/v1/terminals/point-radius", body, permission);
        assertThat(created.status()).as(created.body()).isEqualTo(201);
        return UUID.fromString(kit.parse(created).get("id").asString());
    }

    private Outcome updateTerminal(UUID id, long version, String name, int docks,
                                   String permission) {
        String body = """
                {"name":"%s","dockCount":%d,"avgDwellMinutes":30,"version":%d}
                """.formatted(name, docks, version);
        return put("/api/v1/terminals/" + id, body, permission);
    }

    private Outcome put(String path, String body, String... permissions) {
        return send(tenant, "PUT", path, body, permissions);
    }

    private Outcome delete(String path, String... permissions) {
        return send(tenant, "DELETE", path, null, permissions);
    }

    private Outcome send(Tenant as, String method, String path, String body, String... permissions) {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Authorization", "Bearer " + kit.token(as, permissions));
        if (body != null) {
            request.header("Content-Type", "application/json");
        }
        return kit.send(request.method(method, body == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body)).build());
    }

    private String status(UUID tripId) {
        return jdbc.sql("SELECT status FROM trip WHERE id = :id")
                .param("id", tripId).query(String.class).single();
    }

    private String loadStatus(UUID loadId) {
        return jdbc.sql("SELECT status FROM load_unit WHERE id = :id")
                .param("id", loadId).query(String.class).single();
    }

    private long auditCount(String action, UUID resourceId) {
        return jdbc.sql("SELECT count(*) FROM audit_log WHERE action = :a AND resource_id = :id")
                .param("a", action).param("id", resourceId).query(Long.class).single();
    }
}
