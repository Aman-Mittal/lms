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
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.lms.acceptance.TenantSeeds;
import com.lms.acceptance.TenantSeeds.Seeded;
import com.lms.acceptance.TestFixtures;
import com.lms.identity.security.TokenService;
import com.lms.web.ApiTestKit.Outcome;
import com.lms.web.ApiTestKit.Tenant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
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
 * Tracking links over HTTP, with real permissions: issuing, the one-time token,
 * what the public view shows and what it must never show, expiry, revocation,
 * the uniform refusal, and tenant isolation.
 *
 * <p>Each seeded tenant has one consignment on one trip, so every test starts by
 * putting both back in a known state. The database is reached directly (as the
 * owner) only for what a caller cannot do: resetting state, adding pings and
 * ageing a link past its expiry.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TrackingApiTest {

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17.7-alpine")
            .withDatabaseName("lms").withUsername("lms").withPassword("lms");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        // Many public reads from one address: the limit has its own test.
        registry.add("lms.tracking.rate-limit.requests", () -> "100000");
    }

    @LocalServerPort
    private int port;
    @Autowired
    private TestFixtures fixtures;
    @Autowired
    private TokenService tokens;
    @Autowired
    private TenantSeeds seeds;
    @Autowired
    private JdbcClient jdbc;

    private final HttpClient http = HttpClient.newHttpClient();
    private ApiTestKit kit;
    private String[] everything;
    private Tenant callerA;
    private Tenant callerB;
    private Seeded a;
    private Seeded b;
    private UUID consignment;
    private UUID trip;

    @BeforeAll
    void twoTenants() {
        kit = new ApiTestKit(fixtures, tokens, port);
        everything = seeds.allPermissions().toArray(String[]::new);
        a = seeds.seed(0.0);
        b = seeds.seed(1.0);
        callerA = kit.tenantOf(a.tenantId(), a.orgUnitId(), a.orgPath());
        callerB = kit.tenantOf(b.tenantId(), b.orgUnitId(), b.orgPath());
        consignment = a.id("consignment");
        trip = a.id("trip");
    }

    @BeforeEach
    void inTransit() {
        for (UUID tenant : List.of(a.tenantId(), b.tenantId())) {
            jdbc.sql("DELETE FROM tracking_link WHERE tenant_id = :t").param("t", tenant).update();
            jdbc.sql("DELETE FROM delivery_proof WHERE tenant_id = :t").param("t", tenant).update();
            jdbc.sql("DELETE FROM gps_ping WHERE tenant_id = :t").param("t", tenant).update();
        }
        state("IN_TRANSIT", "DISPATCHED");
        // The seeded trip has no vehicle of its own; pings need one.
        jdbc.sql("""
                        UPDATE trip SET vehicle_id = (SELECT id FROM vehicle WHERE tenant_id = trip.tenant_id
                                                       ORDER BY id LIMIT 1)
                         WHERE id = :t
                        """).param("t", trip).update();
    }

    // ------------------------------------------------------------- helpers

    private void state(String consignmentStatus, String tripStatus) {
        jdbc.sql("UPDATE consignment SET status = :s WHERE id = :c")
                .param("s", consignmentStatus).param("c", consignment).update();
        jdbc.sql("UPDATE trip SET status = :s, completed_at = NULL WHERE id = :t")
                .param("s", tripStatus).param("t", trip).update();
    }

    private Outcome issueOutcome(String body) {
        return kit.post(callerA, "/api/v1/consignments/" + consignment + "/tracking-links", body, everything);
    }

    private JsonNode issue() {
        Outcome issued = issueOutcome("{}");
        assertThat(issued.status()).as(issued.body()).isEqualTo(201);
        return kit.parse(issued);
    }

    private HttpResponse<String> publicGet(String token) {
        return publicGet(token, null);
    }

    /** An unauthenticated read, optionally as if from another client address. */
    private HttpResponse<String> publicGet(String token, String clientAddress) {
        HttpRequest.Builder request = HttpRequest.newBuilder(
                URI.create("http://localhost:" + port + "/api/v1/public/tracking/" + token)).GET();
        if (clientAddress != null) {
            request.header("X-Forwarded-For", clientAddress);
        }
        try {
            return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            throw new AssertionError("The request never reached the server", e);
        }
    }

    private JsonNode view(String token) {
        HttpResponse<String> response = publicGet(token);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return kit.parse(new Outcome(200, response.body(), 0));
    }

    private void expect(String what, Outcome outcome, int status, String code) {
        assertThat(outcome.status()).as(what + ": " + outcome.body()).isEqualTo(status);
        if (code != null) {
            assertThat(outcome.code()).as(what).isEqualTo(code);
        }
    }

    private void addPing(String recordedAt, String lat, String lon) {
        jdbc.sql("""
                        INSERT INTO gps_ping (id, tenant_id, vehicle_id, trip_id, recorded_at, lat, lon)
                        SELECT gen_random_uuid(), t.tenant_id, t.vehicle_id, t.id,
                               CAST(:at AS timestamptz), CAST(:lat AS numeric), CAST(:lon AS numeric)
                          FROM trip t WHERE t.id = :trip
                        """)
                .param("at", recordedAt).param("lat", lat).param("lon", lon).param("trip", trip).update();
    }

    // ------------------------------------------------------------- issuing

    @Test
    @DisplayName("a link is issued with a token shown once, and the public view needs no sign-in")
    void issueAndFollow() {
        JsonNode issued = issue();
        String token = issued.get("token").asString();
        assertThat(token).hasSizeGreaterThanOrEqualTo(40);
        assertThat(Instant.parse(issued.get("expiresAt").asString()))
                .isBetween(Instant.now().plus(Duration.ofDays(29)), Instant.now().plus(Duration.ofDays(31)));

        HttpResponse<String> response = publicGet(token);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        assertThat(response.headers().firstValue("Cache-Control")).hasValue("no-store");
        assertThat(response.headers().firstValue("X-Robots-Tag")).hasValue("noindex");
        JsonNode view = kit.parse(new Outcome(200, response.body(), 0));
        assertThat(view.get("status").asString()).isEqualTo("IN_TRANSIT");
        assertThat(view.get("reference").asString()).isNotBlank();
        assertThat(view.get("originName").asString()).isNotBlank();
        assertThat(view.get("destinationName").asString()).isNotBlank();
        assertThat(view.get("proofRecorded").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("only a hash of the token is stored, and the listing never shows a token")
    void tokenIsNotKept() {
        JsonNode issued = issue();
        String token = issued.get("token").asString();

        String stored = jdbc.sql("SELECT token_hash FROM tracking_link WHERE id = :id")
                .param("id", UUID.fromString(issued.get("id").asString())).query(String.class).single();
        assertThat(stored).isNotEqualTo(token).doesNotContain(token).hasSize(64);

        Outcome listed = kit.get(callerA, "/api/v1/consignments/" + consignment + "/tracking-links", everything);
        assertThat(listed.status()).isEqualTo(200);
        assertThat(listed.body()).doesNotContain(token).doesNotContain("token");
        JsonNode items = kit.parse(listed).get("items");
        assertThat(items).hasSize(1);
        assertThat(items.get(0).get("status").asString()).isEqualTo("ACTIVE");
        assertThat(issue().get("token").asString()).as("every token is its own").isNotEqualTo(token);
    }

    @Test
    @DisplayName("a link's lifetime is 1 to 180 days, and a consignment holds at most ten live links")
    void lifetimeAndLimit() {
        expect("zero days", issueOutcome("{\"expiresInDays\":0}"), 409, "tracking-expiry-invalid");
        expect("too many days", issueOutcome("{\"expiresInDays\":181}"), 409, "tracking-expiry-invalid");
        JsonNode week = kit.parse(issueOutcome("{\"expiresInDays\":7}"));
        assertThat(Instant.parse(week.get("expiresAt").asString()))
                .isBefore(Instant.now().plus(Duration.ofDays(8)));

        List<String> ids = new ArrayList<>();
        ids.add(week.get("id").asString());
        for (int i = 1; i < 10; i++) {
            ids.add(issue().get("id").asString());
        }
        expect("an eleventh link", issueOutcome("{}"), 409, "tracking-link-limit");
        expect("revoke one", kit.post(callerA, "/api/v1/tracking-links/" + ids.get(0) + "/revocations", "{}",
                everything), 204, null);
        issue();
    }

    @Test
    @DisplayName("a consignment nobody has cannot be given a link")
    void unknownConsignment() {
        expect("issue", kit.post(callerA, "/api/v1/consignments/" + UUID.randomUUID() + "/tracking-links", "{}",
                everything), 404, null);
        expect("list", kit.get(callerA, "/api/v1/consignments/" + UUID.randomUUID() + "/tracking-links",
                everything), 404, null);
    }

    // ------------------------------------------------------------- the view

    @Test
    @DisplayName("the public view carries nothing but its few fields, and none of the internals")
    void viewShowsLittle() {
        addPing("2031-01-01T10:00:00Z", "19.123456", "72.987654");
        String body = publicGet(issue().get("token").asString()).body();
        JsonNode view = kit.parse(new Outcome(200, body, 0));

        List<String> keys = new ArrayList<>();
        view.propertyNames().forEach(keys::add);
        assertThat(keys).isSubsetOf("reference", "status", "originName", "destinationName",
                "requestedDeliveryAt", "deliveredAt", "proofRecorded", "position");
        List<String> positionKeys = new ArrayList<>();
        view.get("position").propertyNames().forEach(positionKeys::add);
        assertThat(positionKeys).containsExactlyInAnyOrder("lat", "lon", "recordedAt");

        assertThat(body).as("no identifier of any kind").doesNotContainPattern("[0-9a-f]{8}-[0-9a-f]{4}-");
        List<String> secrets = new ArrayList<>(List.of(
                jdbc.sql("SELECT trip_no FROM trip WHERE id = :t").param("t", trip).query(String.class).single(),
                jdbc.sql("""
                                SELECT v.registration_no FROM trip t JOIN vehicle v ON v.id = t.vehicle_id
                                 WHERE t.id = :t
                                """).param("t", trip).query(String.class).optional().orElseThrow(
                        () -> new AssertionError("the seeded tenant has no vehicle to hide")),
                jdbc.sql("""
                                SELECT p.legal_name FROM consignment c
                                  JOIN sales_order o ON o.id = c.order_id
                                  JOIN business_partner p ON p.id = o.customer_partner_id
                                 WHERE c.id = :c
                                """).param("c", consignment).query(String.class).single()));
        jdbc.sql("""
                        SELECT d.full_name FROM trip t JOIN driver d ON d.id = t.driver_id WHERE t.id = :t
                        """).param("t", trip).query(String.class).optional().ifPresent(secrets::add);
        for (String secret : secrets) {
            assertThat(body).as("the view leaks %s", secret).doesNotContain(secret);
        }
    }

    @Test
    @DisplayName("while in transit the latest ping is shown, to two decimals, and no older one")
    void positionIsRoundedAndLatest() {
        addPing("2031-01-01T10:00:00Z", "10.111111", "70.111111");
        addPing("2031-01-01T12:00:00Z", "19.123456", "72.987654");
        addPing("2031-01-01T11:00:00Z", "11.111111", "71.111111");

        JsonNode position = view(issue().get("token").asString()).get("position");
        assertThat(position.get("lat").decimalValue()).isEqualByComparingTo("19.12");
        assertThat(position.get("lon").decimalValue()).isEqualByComparingTo("72.99");
        assertThat(Instant.parse(position.get("recordedAt").asString()))
                .isEqualTo(Instant.parse("2031-01-01T12:00:00Z"));
    }

    @Test
    @DisplayName("the status follows the consignment and its trip, and the position only while it moves")
    void statusFollowsTheJourney() {
        String token = issue().get("token").asString();
        addPing("2031-01-01T10:00:00Z", "19.1", "72.9");

        state("PLANNED", "ASSIGNED");
        JsonNode planned = view(token);
        assertThat(planned.get("status").asString()).isEqualTo("PLANNED");
        assertThat(planned.has("position")).as("nothing to show before it leaves").isFalse();

        state("ASSIGNED_TO_LOAD", "DISPATCHED");
        assertThat(view(token).get("status").asString())
                .as("a full truckload stays assigned to its load; its trip says it left")
                .isEqualTo("IN_TRANSIT");

        state("CANCELLED", "DISPATCHED");
        assertThat(view(token).get("status").asString()).isEqualTo("CANCELLED");

        state("IN_TRANSIT", "DISPATCHED");
        jdbc.sql("""
                        INSERT INTO delivery_proof (id, tenant_id, consignment_id, status, otp_hash, otp_issued_at,
                                                    otp_expires_at, recipient_name, delivered_at)
                        VALUES (gen_random_uuid(), :t, :c, 'CONFIRMED', 'x', now(), now() + interval '1 hour',
                                'Asha Rao', now())
                        """).param("t", a.tenantId()).param("c", consignment).update();
        JsonNode delivered = view(token);
        assertThat(delivered.get("status").asString()).isEqualTo("DELIVERED");
        assertThat(delivered.get("proofRecorded").asBoolean()).isTrue();
        assertThat(delivered.get("deliveredAt").asString()).isNotBlank();
        assertThat(delivered.has("position")).as("it has arrived").isFalse();
        assertThat(delivered.toString()).as("who received it stays private").doesNotContain("Asha");
    }

    // ------------------------------------------------------ refusal and expiry

    @Test
    @DisplayName("an unknown, a revoked and an expired token are refused with one and the same answer")
    void uniformRefusal() {
        JsonNode revoked = issue();
        JsonNode expired = issue();
        expect("revoke", kit.post(callerA, "/api/v1/tracking-links/" + revoked.get("id").asString()
                + "/revocations", "{}", everything), 204, null);
        jdbc.sql("""
                        UPDATE tracking_link SET created_at = now() - interval '2 days',
                                                 expires_at = now() - interval '1 day'
                         WHERE id = :id
                        """).param("id", UUID.fromString(expired.get("id").asString())).update();

        List<HttpResponse<String>> refusals = List.of(
                publicGet("not-a-token-anybody-issued"),
                publicGet("x".repeat(500)),
                publicGet(revoked.get("token").asString()),
                publicGet(expired.get("token").asString()));
        for (HttpResponse<String> refusal : refusals) {
            assertThat(refusal.statusCode()).as(refusal.body()).isEqualTo(404);
            assertThat(refusal.headers().firstValue("Cache-Control")).hasValue("no-store");
            assertThat(refusal.headers().firstValue("X-Robots-Tag")).hasValue("noindex");
        }
        assertThat(refusals).extracting(HttpResponse::body).containsOnly(refusals.get(0).body());
        assertThat(kit.parse(new Outcome(404, refusals.get(0).body(), 0)).get("code").asString())
                .isEqualTo("tracking-link-not-found");

        JsonNode statuses = kit.parse(kit.get(callerA, "/api/v1/consignments/" + consignment + "/tracking-links",
                everything)).get("items");
        List<String> seen = new ArrayList<>();
        statuses.forEach(item -> seen.add(item.get("status").asString()));
        assertThat(seen).containsExactlyInAnyOrder("REVOKED", "EXPIRED");
    }

    @Test
    @DisplayName("revoking is idempotent, and a revoked link stops working at once")
    void revocation() {
        JsonNode issued = issue();
        String token = issued.get("token").asString();
        assertThat(publicGet(token).statusCode()).isEqualTo(200);

        String path = "/api/v1/tracking-links/" + issued.get("id").asString() + "/revocations";
        expect("revoke", kit.post(callerA, path, "{}", everything), 204, null);
        expect("revoke again", kit.post(callerA, path, "{}", everything), 204, null);
        assertThat(publicGet(token).statusCode()).isEqualTo(404);
        expect("a link nobody has", kit.post(callerA, "/api/v1/tracking-links/" + UUID.randomUUID()
                + "/revocations", "{}", everything), 404, null);
    }

    // --------------------------------------------------- permissions, tenants

    @Test
    @DisplayName("managing links needs its permission and a sign-in; following one needs neither")
    void permissions() {
        String path = "/api/v1/consignments/" + consignment + "/tracking-links";
        JsonNode issued = issue();
        expect("issue with another permission", kit.post(callerA, path, "{}", "POD_READ"), 403, null);
        expect("list with another permission", kit.get(callerA, path, "POD_READ"), 403, null);
        expect("revoke with another permission", kit.post(callerA, "/api/v1/tracking-links/"
                + issued.get("id").asString() + "/revocations", "{}", "POD_READ"), 403, null);

        try {
            HttpResponse<String> anonymous = http.send(HttpRequest.newBuilder(
                    URI.create("http://localhost:" + port + path)).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertThat(anonymous.statusCode()).as("management is not public").isEqualTo(401);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        assertThat(publicGet(issued.get("token").asString()).statusCode()).isEqualTo(200);
    }

    @Test
    @DisplayName("another tenant cannot issue, list or revoke, and a link only ever shows its own tenant's consignment")
    void tenantsAreIsolated() {
        JsonNode issued = issue();
        String path = "/api/v1/consignments/" + consignment + "/tracking-links";

        expect("b issues on a's consignment", kit.post(callerB, path, "{}", everything), 404, null);
        expect("b lists a's links", kit.get(callerB, path, everything), 404, null);
        expect("b revokes a's link", kit.post(callerB, "/api/v1/tracking-links/" + issued.get("id").asString()
                + "/revocations", "{}", everything), 404, null);
        assertThat(publicGet(issued.get("token").asString()).statusCode())
                .as("b's attempt changed nothing").isEqualTo(200);

        Outcome own = kit.post(callerB, "/api/v1/consignments/" + b.id("consignment") + "/tracking-links", "{}",
                everything);
        assertThat(own.status()).as(own.body()).isEqualTo(201);
        String bToken = kit.parse(own).get("token").asString();
        String aReference = view(issued.get("token").asString()).get("reference").asString();
        assertThat(view(bToken).get("reference").asString())
                .as("each token shows only the consignment it was issued for").isNotEqualTo(aReference);
    }
}
