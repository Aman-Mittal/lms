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
 */package com.lms.web;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.lms.acceptance.TestFixtures;
import com.lms.identity.command.domain.AppUser;
import com.lms.identity.security.TokenService;
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
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The HTTP edge, over the real filter chain and a real database.
 *
 * <p>Everything below the controllers is covered by the Cucumber suite, which
 * drives the services directly. That is the right level for domain rules and
 * the wrong level for everything here: token verification, the tenant binding
 * that row-level security depends on, status codes, and the idempotency filter
 * -- none of which exist on the path a service call takes. The RBAC defect and
 * the unreachable login endpoint both survived a green suite for exactly this
 * reason.
 *
 * <p>Real HTTP on a real port rather than a hand-assembled MockMvc chain. The
 * filter ordering is the thing under test -- a key is scoped to a tenant, so
 * the idempotency filter has to run after the tenant filter -- and a chain
 * assembled by the test would be a chain that proves nothing about the one the
 * application registers.
 *
 * <p>Tokens are minted with the application's own signing key rather than
 * stubbed, so the {@code perms} claim goes through the real converter.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RestApiTest {

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

    /**
     * The JDK client rather than a Spring one. It needs no dependency, it does
     * not follow redirects or retry behind the test's back, and it reports the
     * exact status and headers the server sent -- which is what these
     * assertions are about.
     */
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    @LocalServerPort
    private int port;

    @Autowired
    private TestFixtures fixtures;
    @Autowired
    private TokenService tokens;
    @Autowired
    private JdbcClient jdbc;
    private final ObjectMapper json = new ObjectMapper();

    private UUID tenantId;
    private UUID orgUnitId;
    private String orgPath;
    private AppUser user;

    @BeforeEach
    void seedTenant() {
        // Salted per test: the container is shared, and tests isolate
        // themselves by operating on distinct tenants rather than by wiping
        // the database -- which also means every read runs against a database
        // that already holds other tenants' rows.
        String salt = UUID.randomUUID().toString().substring(0, 8);
        orgPath = "/acme-" + salt + "/";
        tenantId = fixtures.createTenant("acme-" + salt, "Acme Logistics");
        orgUnitId = fixtures.createOrgUnit(tenantId, orgPath, "Acme HQ", "HQ", null);

        user = new AppUser(UUID.randomUUID(), tenantId, orgUnitId, "ops@acme.test",
                "not-used", "Ops User", AppUser.UserStatus.ACTIVE,
                null, 0, null, null, null, Instant.now(), Instant.now());
    }

    // ------------------------------------------------------- authentication

    @Test
    @DisplayName("an unauthenticated write never reaches a controller")
    void anonymousIsRejected() {
        assertThat(postAnonymously("/api/v1/partners", partnerBody("CUST-1")).statusCode())
                .isEqualTo(401);
    }

    @Test
    @DisplayName("a token without the permission is 403 with a problem document")
    void withoutPermissionIsForbidden() throws Exception {
        // The regression this whole class exists for. @PreAuthorize on the
        // service, translated by the advice -- not swallowed into a 500 by the
        // catch-all, which is what happened before there was a handler for it.
        HttpResponse<String> response =
                post("/api/v1/partners", partnerBody("CUST-1"), null, "PARTNER_READ");

        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(body(response).get("code").asString()).isEqualTo("access-denied");
    }

    @Test
    @DisplayName("the tenant comes from the token, not from the request")
    void tenantIsBoundFromTheTokenClaim() {
        assertThat(post("/api/v1/partners", partnerBody("CUST-1"), null, "PARTNER_CREATE")
                .statusCode()).isEqualTo(201);

        // Written under the tenant the token named. Nothing in the body said so.
        assertThat(partnerCount("CUST-1")).isEqualTo(1);
    }

    // ---------------------------------------------------------- idempotency

    @Test
    @DisplayName("a retried write with the same key creates one partner, not two")
    void retryWithSameKeyIsDeduplicated() {
        String key = UUID.randomUUID().toString();

        HttpResponse<String> first =
                post("/api/v1/partners", partnerBody("CUST-1"), key, "PARTNER_CREATE");
        assertThat(first.statusCode()).isEqualTo(201);

        // The failure this prevents: a dispatcher on a patchy yard connection
        // loses the response, and the client retries.
        HttpResponse<String> retry =
                post("/api/v1/partners", partnerBody("CUST-1"), key, "PARTNER_CREATE");

        assertThat(retry.statusCode()).isEqualTo(201);
        assertThat(retry.body()).isEqualTo(first.body());
        // Says this is the earlier answer rather than a fresh one -- the
        // difference between "your retry worked" and "you now have two".
        assertThat(retry.headers().firstValue("Idempotent-Replay")).hasValue("true");
        assertThat(partnerCount("CUST-1")).isEqualTo(1);
    }

    @Test
    @DisplayName("reusing a key for a different request is refused rather than answered")
    void sameKeyDifferentBodyIsRefused() {
        String key = UUID.randomUUID().toString();

        assertThat(post("/api/v1/partners", partnerBody("CUST-1"), key, "PARTNER_CREATE")
                .statusCode()).isEqualTo(201);

        // Replaying the first response here would hide a client defect while
        // answering a question the caller did not ask.
        assertThat(post("/api/v1/partners", partnerBody("CUST-2"), key, "PARTNER_CREATE")
                .statusCode()).isEqualTo(409);

        assertThat(partnerCount("CUST-2")).isZero();
    }

    @Test
    @DisplayName("a failed request releases its key so a corrected retry can succeed")
    void failureDoesNotPoisonTheKey() {
        String key = UUID.randomUUID().toString();

        String noLegalName = """
                {"orgUnitId":"%s","code":"CUST-9","legalName":"","partnerType":"CUSTOMER"}
                """.formatted(orgUnitId);

        assertThat(post("/api/v1/partners", noLegalName, key, "PARTNER_CREATE")
                .statusCode()).isEqualTo(400);

        // Without the release, the claim outlives the failure and the client
        // can never succeed with this key.
        assertThat(post("/api/v1/partners", partnerBody("CUST-9"), key, "PARTNER_CREATE")
                .statusCode()).isEqualTo(201);
    }

    @Test
    @DisplayName("without a key, two posts create two partners")
    void deduplicationIsOptIn() {
        // Opt-in deliberately. Requiring the header would break every simple
        // client for the sake of endpoints where a duplicate is harmless.
        assertThat(post("/api/v1/partners", partnerBody("CUST-A"), null, "PARTNER_CREATE")
                .statusCode()).isEqualTo(201);
        assertThat(post("/api/v1/partners", partnerBody("CUST-B"), null, "PARTNER_CREATE")
                .statusCode()).isEqualTo(201);

        assertThat(partnerCount("CUST-A") + partnerCount("CUST-B")).isEqualTo(2);
    }

    // ------------------------------------------------------------ read side

    @Test
    @DisplayName("a listing is a keyset slice, with no total and no page number")
    void listingIsASlice() throws Exception {
        post("/api/v1/partners", partnerBody("CUST-1"), null, "PARTNER_CREATE");

        HttpResponse<String> response =
                get("/api/v1/partners", "PARTNER_READ", "PARTNER_CREATE");

        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode slice = body(response);
        assertThat(slice.get("items").size()).isEqualTo(1);
        assertThat(slice.get("items").get(0).get("code").asString()).isEqualTo("CUST-1");
        // No count and no page index: both would need the scan that keyset
        // pagination exists to avoid.
        assertThat(slice.get("hasMore").asBoolean()).isFalse();
        assertThat(slice.has("totalElements")).isFalse();
    }

    @Test
    @DisplayName("a listing shows this tenant's rows and no others")
    void listingIsTenantScoped() throws Exception {
        post("/api/v1/partners", partnerBody("CUST-1"), null, "PARTNER_CREATE");

        // A second tenant, whose token must not see the first one's partner.
        String salt = UUID.randomUUID().toString().substring(0, 8);
        UUID otherTenant = fixtures.createTenant("globex-" + salt, "Globex Freight");
        String otherPath = "/globex-" + salt + "/";
        fixtures.createOrgUnit(otherTenant, otherPath, "Globex HQ", "HQ", null);

        AppUser outsider = new AppUser(UUID.randomUUID(), otherTenant, UUID.randomUUID(),
                "ops@globex.test", "not-used", "Other Ops", AppUser.UserStatus.ACTIVE,
                null, 0, null, null, null, Instant.now(), Instant.now());

        HttpResponse<String> response = getWithToken("/api/v1/partners",
                tokens.issueAccessToken(outsider, otherPath, List.of("PARTNER_READ")).value());

        assertThat(body(response).get("items").size()).isZero();
    }

    @Test
    @DisplayName("a resource that does not exist is 404 with a problem document")
    void missingResourceIsNotFound() throws Exception {
        HttpResponse<String> response =
                get("/api/v1/partners/" + UUID.randomUUID(), "PARTNER_READ");

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(body(response).get("code").asString()).isEqualTo("not-found");
    }

    @Test
    @DisplayName("a refused domain rule is 409, not 400")
    void refusedRuleIsConflict() throws Exception {
        assertThat(post("/api/v1/partners", partnerBody("CUST-1"), null, "PARTNER_CREATE")
                .statusCode()).isEqualTo(201);

        // A perfectly well-formed request that the domain refuses -- the code
        // is taken. Reporting it as 400 would send the caller looking at their
        // own JSON.
        HttpResponse<String> duplicate =
                post("/api/v1/partners", partnerBody("CUST-1"), null, "PARTNER_CREATE");

        assertThat(duplicate.statusCode()).isEqualTo(409);
        assertThat(body(duplicate).get("code").asString()).isEqualTo("partner-code-taken");
    }

    // ------------------------------------------------------------ internals

    private HttpResponse<String> post(String path, String body, String idempotencyKey,
                                      String... permissions) {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri(path))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + token(permissions))
                .POST(HttpRequest.BodyPublishers.ofString(body));

        if (idempotencyKey != null) {
            request.header("Idempotency-Key", idempotencyKey);
        }
        return send(request.build());
    }

    private HttpResponse<String> postAnonymously(String path, String body) {
        return send(HttpRequest.newBuilder(uri(path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build());
    }

    /**
     * Named rather than overloaded on {@code get(String, String)}. The two
     * would be ambiguous -- Java prefers the fixed-arity form, so a single
     * permission code would silently be sent as a bearer token, and every
     * such call would answer 401 for a reason that looks like a product bug.
     */
    private HttpResponse<String> getWithToken(String path, String bearerToken) {
        return send(HttpRequest.newBuilder(uri(path))
                .header("Authorization", "Bearer " + bearerToken)
                .GET()
                .build());
    }

    private HttpResponse<String> get(String path, String... permissions) {
        return getWithToken(path, token(permissions));
    }

    private HttpResponse<String> send(HttpRequest request) {
        try {
            return http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new AssertionError("The request never reached the server", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted waiting for a response", e);
        }
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    private String token(String... permissions) {
        return tokens.issueAccessToken(user, orgPath, List.of(permissions)).value();
    }

    private JsonNode body(HttpResponse<String> response) {
        return json.readTree(response.body());
    }

    private String partnerBody(String code) {
        return """
                {"orgUnitId":"%s","code":"%s","legalName":"%s Ltd",
                 "partnerType":"CUSTOMER","taxId":"TAX-%s"}
                """.formatted(orgUnitId, code, code, code);
    }

    /**
     * Counted with an explicit tenant predicate rather than through the API, so
     * the assertion is about what the request actually wrote rather than about
     * what a scoped read happens to show.
     */
    private int partnerCount(String code) {
        return jdbc.sql("SELECT count(*) FROM business_partner WHERE tenant_id = :t AND code = :c")
                .param("t", tenantId)
                .param("c", code)
                .query(Integer.class)
                .single();
    }
}
