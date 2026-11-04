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
import java.time.Instant;
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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A trip milestone is recorded once.
 *
 * <p>Gate-in used to accept a second call on a trip already at the origin:
 * it overwrote the first gate-in time and appended a second GATE_IN to the
 * gate log, and dwell and detention are billed from those times. It also
 * accepted any terminal, so a trip could be gated in at a yard its lorry never
 * visited.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TripMilestoneRepeatApiTest {

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
    private UUID origin;

    @BeforeEach
    void anAssignedTrip() {
        kit = new ApiTestKit(fixtures, tokens, port);
        built = TripScaffold.build(fixtures, spine, fleet, terminals, trips, 0.0);
        tenant = kit.tenantOf(built.tenantId(), built.orgUnitId(), built.orgPath());
        origin = jdbc.sql("SELECT origin_terminal_id FROM trip WHERE id = :id")
                .param("id", built.tripId()).query(UUID.class).single();
        assertThat(kit.post(tenant, "/api/v1/trips/" + built.tripId() + "/assignment",
                "{\"vehicleId\":\"" + built.vehicleId() + "\"}", "TRIP_EXECUTE").status())
                .isEqualTo(204);
    }

    @Test
    @DisplayName("a second gate-in is refused by name and leaves the first time and the gate log as they were")
    void repeatedGateInIsRefused() {
        Instant first = Instant.parse("2026-11-04T06:00:00Z");
        assertThat(gateIn(origin, first, null).status()).isEqualTo(204);

        Outcome again = gateIn(origin, first.plusSeconds(3600), null);

        assertThat(again.status()).isEqualTo(409);
        assertThat(again.code()).isEqualTo("trip-already-gated-in");
        assertThat(gateInAt()).isEqualTo(first);
        assertThat(gateEvents("GATE_IN")).isEqualTo(1);
        assertThat(auditCount("TRIP_GATE_IN")).isEqualTo(1);
    }

    @Test
    @DisplayName("gate-in at a terminal that is not the trip's origin is refused and writes nothing")
    void gateInElsewhereIsRefused() {
        Outcome elsewhere = gateIn(UUID.randomUUID(), Instant.parse("2026-11-04T06:00:00Z"), null);

        assertThat(elsewhere.status()).isEqualTo(409);
        assertThat(elsewhere.code()).isEqualTo("trip-gate-in-elsewhere");
        assertThat(status()).isEqualTo("ASSIGNED");
        assertThat(gateEvents("GATE_IN")).isZero();
    }

    @Test
    @DisplayName("a retry carrying the same Idempotency-Key is answered from the first response, not refused")
    void idempotentRetryStillSucceeds() {
        String key = UUID.randomUUID().toString();
        Instant at = Instant.parse("2026-11-04T06:00:00Z");

        assertThat(gateIn(origin, at, key).status()).isEqualTo(204);
        assertThat(gateIn(origin, at, key).status()).isEqualTo(204);

        assertThat(gateEvents("GATE_IN")).isEqualTo(1);
        assertThat(gateInAt()).isEqualTo(at);
    }

    @Test
    @DisplayName("ten gate-ins of one trip at once: one is recorded, the rest are refused, never a 500")
    void concurrentGateIns() {
        List<Callable<Outcome>> calls = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            Instant at = Instant.parse("2026-11-04T06:00:00Z").plusSeconds(i);
            calls.add(() -> gateIn(origin, at, null));
        }

        List<Outcome> outcomes = ApiTestKit.race(calls, Duration.ofSeconds(60));

        assertThat(outcomes).extracting(Outcome::status).doesNotContain(500)
                .containsOnlyOnce(204).isSubsetOf(204, 409);
        assertThat(outcomes).filteredOn(o -> o.status() == 409).extracting(Outcome::code)
                .isSubsetOf("concurrent-modification", "trip-already-gated-in");
        assertThat(status()).isEqualTo("AT_ORIGIN");
        assertThat(gateEvents("GATE_IN")).isEqualTo(1);
        assertThat(auditCount("TRIP_GATE_IN")).isEqualTo(1);
    }

    // ---------------------------------------------------------------- helpers

    private Outcome gateIn(UUID terminalId, Instant at, String idempotencyKey) {
        HttpRequest.Builder request = HttpRequest.newBuilder(
                        URI.create("http://localhost:" + port + "/api/v1/trips/" + built.tripId() + "/gate-in"))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + kit.token(tenant, "TRIP_EXECUTE"))
                .POST(HttpRequest.BodyPublishers.ofString(
                        "{\"terminalId\":\"" + terminalId + "\",\"at\":\"" + at + "\"}"));
        if (idempotencyKey != null) {
            request.header("Idempotency-Key", idempotencyKey);
        }
        return kit.send(request.build());
    }

    private Instant gateInAt() {
        return jdbc.sql("SELECT gate_in_at FROM trip WHERE id = :id")
                .param("id", built.tripId()).query(java.sql.Timestamp.class).single().toInstant();
    }

    private String status() {
        return jdbc.sql("SELECT status FROM trip WHERE id = :id")
                .param("id", built.tripId()).query(String.class).single();
    }

    private long gateEvents(String type) {
        return jdbc.sql("SELECT count(*) FROM gate_event WHERE trip_id = :id AND event_type = :t")
                .param("id", built.tripId()).param("t", type).query(Long.class).single();
    }

    private long auditCount(String action) {
        return jdbc.sql("SELECT count(*) FROM audit_log WHERE action = :a AND resource_id = :id")
                .param("a", action).param("id", built.tripId()).query(Long.class).single();
    }
}
