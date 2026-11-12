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
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
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
 * What the consoles' client-side cache (DOCS/adr/0007) relies on the server
 * for: the cache epoch on {@code /me}, the incremental track read, conditional
 * reads of terminal geometry, and the {@code ui.clientCache} setting.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ClientCacheApiTest {

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
    void aTripWithAVehicle() {
        kit = new ApiTestKit(fixtures, tokens, port);
        built = TripScaffold.build(fixtures, spine, fleet, terminals, trips, 0.0);
        tenant = kit.tenantOf(built.tenantId(), built.orgUnitId(), built.orgPath());
    }

    @Test
    @DisplayName("GET /me carries a cacheEpoch that holds while the permissions hold and moves when they change")
    void epochFollowsPermissions() {
        String first = epoch("ORDER_READ", "TRIP_READ");
        assertThat(first).isNotBlank();
        assertThat(epoch("TRIP_READ", "ORDER_READ")).as("same grant, asked again").isEqualTo(first);
        assertThat(epoch("ORDER_READ", "TRIP_READ", "ORDER_CREATE")).as("a permission added").isNotEqualTo(first);
    }

    private String epoch(String... permissions) {
        Outcome me = kit.get(tenant, "/api/v1/me", permissions);
        assertThat(me.status()).as(me.body()).isEqualTo(200);
        return kit.parse(me).get("cacheEpoch").asString();
    }

    @Test
    @DisplayName("track?afterSeq returns only newer points with their cursor; without it the answer is unchanged")
    void incrementalTrack() {
        Instant base = Instant.parse("2026-01-01T10:00:00Z");
        for (int i = 0; i < 4; i++) {
            ping(base.plusSeconds(60L * i), 19.1 + i * 0.01, 72.9 + i * 0.01);
        }
        String path = "/api/v1/telematics/trips/" + built.tripId() + "/track";

        JsonNode plain = kit.parse(kit.get(tenant, path, "TRIP_READ"));
        assertThat(plain).hasSize(4);
        assertThat(plain.get(0)).as("[lon, lat] only, as before").hasSize(2);

        JsonNode all = kit.parse(kit.get(tenant, path + "?afterSeq=0", "TRIP_READ"));
        assertThat(all).hasSize(4);
        assertThat(all.get(0)).hasSize(3);
        assertThat(all.get(0).get(0).asDouble()).isEqualTo(plain.get(0).get(0).asDouble());
        long cursor = all.get(1).get(2).asLong();
        assertThat(cursor).isEqualTo(base.plusSeconds(60).getEpochSecond() * 1_000_000L);

        JsonNode newer = kit.parse(kit.get(tenant, path + "?afterSeq=" + cursor, "TRIP_READ"));
        assertThat(newer).hasSize(2);
        assertThat(newer.get(0).get(2).asLong()).isGreaterThan(cursor);

        long last = newer.get(1).get(2).asLong();
        assertThat(kit.parse(kit.get(tenant, path + "?afterSeq=" + last, "TRIP_READ"))).isEmpty();

        ping(base.plusSeconds(600), 19.5, 73.5);
        JsonNode appended = kit.parse(kit.get(tenant, path + "?afterSeq=" + last, "TRIP_READ"));
        assertThat(appended).hasSize(1);
        assertThat(appended.get(0).get(0).asDouble()).isEqualTo(73.5);

        assertThat(kit.get(tenant, path + "?afterSeq=-1", "TRIP_READ").status()).isEqualTo(400);
    }

    @Test
    @DisplayName("once retention has collapsed the raw points, afterSeq answers the simplified track as pairs")
    void collapsedTrackAnswersPairs() {
        jdbc.sql("""
                INSERT INTO trip_track (id, tenant_id, trip_id, points, point_count, raw_count)
                VALUES (gen_random_uuid(), :tenantId, :tripId, '[[72.9,19.1],[73.0,19.2]]'::jsonb, 2, 50)
                """).param("tenantId", built.tenantId()).param("tripId", built.tripId()).update();
        JsonNode track = kit.parse(kit.get(tenant,
                "/api/v1/telematics/trips/" + built.tripId() + "/track?afterSeq=5", "TRIP_READ"));
        assertThat(track).hasSize(2);
        assertThat(track.get(0)).hasSize(2);
    }

    @Test
    @DisplayName("terminal reads carry an ETag and answer 304 to If-None-Match; a changed terminal gets a new one")
    void terminalGeometryIsRevalidatable() {
        UUID origin = jdbc.sql("SELECT origin_terminal_id FROM trip WHERE id = :id")
                .param("id", built.tripId()).query(UUID.class).single();
        String one = "/api/v1/terminals/" + origin;

        Outcome first = send(one, null);
        assertThat(first.status()).isEqualTo(200);
        String etag = etagOf(one);
        assertThat(etag).isNotBlank();
        Outcome again = send(one, etag);
        assertThat(again.status()).isEqualTo(304);
        assertThat(again.body()).isEmpty();

        String list = "/api/v1/terminals?limit=50";
        String listTag = etagOf(list);
        assertThat(send(list, listTag).status()).isEqualTo(304);
        assertThat(send(list, "\"something-else\"").status()).isEqualTo(200);

        jdbc.sql("UPDATE terminal SET version = COALESCE(version, 0) + 1 WHERE id = :id").param("id", origin).update();
        assertThat(send(one, etag).status()).as("a new version is not 'not modified'").isEqualTo(200);
        assertThat(send(list, listTag).status()).isEqualTo(200);
    }

    @Test
    @DisplayName("ui.clientCache is a boolean UI setting, on by default, settable for the organisation and a unit only")
    void settingIsRegistered() {
        JsonNode definitions = kit.parse(kit.get(tenant, "/api/v1/settings/definitions", "SETTINGS_READ"));
        JsonNode cache = null;
        for (JsonNode d : definitions.get("items")) {
            if ("ui.clientCache".equals(d.get("key").asString())) {
                cache = d;
            }
        }
        assertThat(cache).isNotNull();
        assertThat(cache.get("type").asString()).isEqualToIgnoringCase("BOOLEAN");
        assertThat(cache.get("defaultValue").asBoolean()).isTrue();
        assertThat(cache.get("category").asString()).isEqualTo("UI");
        List<String> scopes = new java.util.ArrayList<>();
        cache.get("scopes").forEach(s -> scopes.add(s.asString()));
        assertThat(scopes).containsExactly("ORG_DEFAULT", "ORG_UNIT");

        // Not a preference the person sets, yet the console needs it at sign-in: /settings/me
        // carries it under policy, and never among the preferences the profile page edits.
        JsonNode mine = kit.parse(kit.get(tenant, "/api/v1/settings/me"));
        List<String> policyKeys = new java.util.ArrayList<>();
        mine.get("policy").forEach(e -> policyKeys.add(e.get("key").asString()));
        List<String> personalKeys = new java.util.ArrayList<>();
        mine.get("effective").forEach(e -> personalKeys.add(e.get("key").asString()));
        assertThat(policyKeys).contains("ui.clientCache");
        assertThat(personalKeys).doesNotContain("ui.clientCache");
    }

    private String etagOf(String path) {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Authorization", "Bearer " + kit.token(tenant, "TERMINAL_READ")).GET().build();
        try {
            return java.net.http.HttpClient.newHttpClient()
                    .send(request, java.net.http.HttpResponse.BodyHandlers.ofString())
                    .headers().firstValue("ETag").orElse(null);
        } catch (java.io.IOException | InterruptedException e) {
            throw new IllegalStateException(e);
        }
    }

    private Outcome send(String path, String ifNoneMatch) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Authorization", "Bearer " + kit.token(tenant, "TERMINAL_READ"));
        if (ifNoneMatch != null) {
            builder.header("If-None-Match", ifNoneMatch);
        }
        return kit.send(builder.GET().build());
    }

    private void ping(Instant at, double lat, double lon) {
        jdbc.sql("""
                INSERT INTO gps_ping (id, tenant_id, vehicle_id, trip_id, recorded_at, lat, lon, source)
                VALUES (gen_random_uuid(), :tenantId, :vehicleId, :tripId, :at, :lat, :lon, 'DEVICE')
                """)
                .param("tenantId", built.tenantId()).param("vehicleId", built.vehicleId())
                .param("tripId", built.tripId()).param("at", Timestamp.from(at))
                .param("lat", BigDecimal.valueOf(lat)).param("lon", BigDecimal.valueOf(lon)).update();
    }
}
