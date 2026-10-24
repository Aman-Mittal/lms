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
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import com.lms.acceptance.TestFixtures;
import com.lms.identity.security.TokenService;
import com.lms.web.ApiTestKit.Tenant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every class of bad input answers a problem document, never a 500, over the
 * real stack: security filter chain, Spring MVC, the advice and a real
 * PostgreSQL behind it.
 *
 * <p>The handler's mappings are unit-tested in {@code ProblemMappingTest}; this
 * is the proof that the framework really raises what those tests assume, in
 * the order the filter chain and the dispatcher actually run, for requests
 * that never reach a controller at all (an unrouted path, a wrong method).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ErrorContractApiTest {

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

    private static final String[] EVERYTHING = {
            "PARTNER_CREATE", "PARTNER_READ", "PARTNER_UPDATE", "VEHICLE_CREATE", "VEHICLE_READ",
            "VEHICLE_UPDATE", "DRIVER_CREATE", "DRIVER_READ", "TERMINAL_CREATE", "TERMINAL_READ",
            "ORDER_CREATE", "ORDER_UPDATE", "ROUTING_GUIDE_MANAGE", "ACCESS_POLICY_MANAGE",
            "WAREHOUSE_SETUP", "WAREHOUSE_OPERATE", "WAREHOUSE_READ", "INVENTORY_ADJUST",
            "CLIENT_BILLING_READ", "CLIENT_BILLING_RUN", "CLIENT_MARGIN_READ", "COMPLIANCE_READ"};

    @LocalServerPort
    private int port;
    @Autowired
    private TestFixtures fixtures;
    @Autowired
    private TokenService tokens;

    private ApiTestKit kit;
    private Tenant tenant;
    private String token;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final ObjectMapper json = new ObjectMapper();

    @BeforeEach
    void aTenant() {
        kit = new ApiTestKit(fixtures, tokens, port);
        tenant = kit.newTenant("errc");
        token = kit.token(tenant, EVERYTHING);
    }

    private record Answer(int status, String body, String contentType, String allow, String authenticate) {

        JsonNode problem() {
            return new ObjectMapper().readTree(body);
        }
    }

    private Answer call(String method, String path, String bearer, String body) {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
        if (bearer != null) {
            request.header("Authorization", "Bearer " + bearer);
        }
        if (body != null) {
            request.header("Content-Type", "application/json");
        }
        request.method(method, body == null
                ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        try {
            HttpResponse<String> r = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
            return new Answer(r.statusCode(), r.body(),
                    r.headers().firstValue("Content-Type").orElse(null),
                    r.headers().firstValue("Allow").orElse(null),
                    r.headers().firstValue("WWW-Authenticate").orElse(null));
        } catch (Exception e) {
            throw new AssertionError("The request never reached the server", e);
        }
    }

    private void assertProblem(Answer a, int status, String code) {
        assertThat(a.status()).as("status; body was %s", a.body()).isEqualTo(status);
        assertThat(a.contentType()).as("content type").startsWith("application/problem+json");
        JsonNode p = a.problem();
        assertThat(p.get("code").asString()).as("code").isEqualTo(code);
        assertThat(p.get("status").asInt()).isEqualTo(status);
        assertThat(p.get("instance").asString()).as("instance").startsWith("/");
        assertThat(p.get("type").asString()).endsWith("/" + code);
        // Nothing of the implementation reaches the caller.
        assertThat(a.body()).doesNotContain("Exception").doesNotContain("com.lms")
                .doesNotContain("org.springframework").doesNotContain("at java.")
                .doesNotContain("jackson").doesNotContain("SELECT");
    }

    // ------------------------------------------------------------ bad bodies

    static Stream<Arguments> badBodies() {
        return Stream.of(
                Arguments.of("malformed JSON", "{not json", "malformed-request"),
                Arguments.of("truncated JSON", "{\"code\":\"X\"", "malformed-request"),
                Arguments.of("unknown enum value", "{\"orgUnitId\":\"%s\",\"code\":\"X\","
                        + "\"legalName\":\"X\",\"partnerType\":\"NOPE\"}", "malformed-request"),
                Arguments.of("wrong JSON type", "{\"code\":[1,2]}", "malformed-request"),
                Arguments.of("a bare scalar", "\"text\"", "malformed-request"),
                Arguments.of("a non-UUID id inside the body", "{\"orgUnitId\":\"not-a-uuid\"}", "malformed-request"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("badBodies")
    @DisplayName("an unreadable body is 400 malformed-request")
    void unreadableBody(String label, String body, String code) {
        assertProblem(call("POST", "/api/v1/partners", token, body.replace("%s", tenant.orgUnitId().toString())),
                400, code);
    }

    @Test
    @DisplayName("a missing body on an endpoint that requires one is 400 malformed-request")
    void missingBody() {
        assertProblem(call("POST", "/api/v1/partners", token, null), 400, "malformed-request");
    }

    // ------------------------------------------- the eleven empty-object writes

    static Stream<Arguments> emptyObjectWrites() {
        return Stream.of(
                Arguments.of("PUT", "/api/v1/access-policies/{id}"),
                Arguments.of("POST", "/api/v1/orders/{id}/lines"),
                Arguments.of("POST", "/api/v1/routing-guides/{id}/vendors"),
                Arguments.of("POST", "/api/v1/drivers"),
                Arguments.of("POST", "/api/v1/vehicles"),
                Arguments.of("POST", "/api/v1/terminals/point-radius"),
                Arguments.of("POST", "/api/v1/warehouses/{id}/bins"),
                Arguments.of("POST", "/api/v1/putaways"),
                Arguments.of("POST", "/api/v1/stock/adjustments"),
                Arguments.of("POST", "/api/v1/stock/quarantine/releases"),
                Arguments.of("POST", "/api/v1/stock/quarantine/write-offs"));
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("emptyObjectWrites")
    @DisplayName("an empty object is 400 validation-failed, never 500")
    void emptyObject(String method, String path) {
        Answer a = call(method, path.replace("{id}", UUID.randomUUID().toString()), token, "{}");
        assertProblem(a, 400, "validation-failed");
        assertThat(a.problem().get("errors").size()).as("field errors").isGreaterThan(0);
    }

    @ParameterizedTest(name = "{0} {1} with no body")
    @MethodSource("emptyObjectWrites")
    @DisplayName("no body at all is 400 malformed-request, never 500")
    void noBody(String method, String path) {
        assertProblem(call(method, path.replace("{id}", UUID.randomUUID().toString()), token, null),
                400, "malformed-request");
    }

    // ------------------------------------------------- bad path and query values

    @Test
    @DisplayName("a non-UUID path id is 400 invalid-parameter naming the parameter")
    void badPathId() {
        Answer a = call("GET", "/api/v1/partners/not-a-uuid", token, null);
        assertProblem(a, 400, "invalid-parameter");
        assertThat(a.problem().get("errors").has("id")).isTrue();
    }

    @Test
    @DisplayName("a non-numeric limit is 400 invalid-parameter naming limit")
    void badLimit() {
        Answer a = call("GET", "/api/v1/partners?limit=abc", token, null);
        assertProblem(a, 400, "invalid-parameter");
        assertThat(a.problem().get("errors").has("limit")).isTrue();
    }

    static Stream<Arguments> requiredQueryParameters() {
        return Stream.of(
                Arguments.of("/api/v1/client-billing/clients/%s/charges", "period"),
                Arguments.of("/api/v1/client-billing/clients/%s/storage", "period"),
                Arguments.of("/api/v1/asns", "warehouseId"),
                Arguments.of("/api/v1/stock", "warehouseId"),
                Arguments.of("/api/v1/stock/movements", "skuId"),
                Arguments.of("/api/v1/outbound-orders", "warehouseId"),
                Arguments.of("/api/v1/handling-units", "warehouseId"),
                Arguments.of("/api/v1/pick-waves", "warehouseId"));
    }

    @ParameterizedTest(name = "{0} without {1}")
    @MethodSource("requiredQueryParameters")
    @DisplayName("a missing required query parameter is 400 missing-parameter naming it")
    void missingQueryParameter(String path, String parameter) {
        Answer a = call("GET", path.replace("%s", UUID.randomUUID().toString()), token, null);
        assertProblem(a, 400, "missing-parameter");
        assertThat(a.problem().get("errors").has(parameter)).isTrue();
    }

    @Test
    @DisplayName("an expiry window outside 0..3650 is 400 validation-failed")
    void windowOutOfRange() {
        assertProblem(call("GET", "/api/v1/compliance/expiring?withinDays=-1", token, null),
                400, "validation-failed");
        assertProblem(call("GET", "/api/v1/compliance/expiring?withinDays=3651", token, null),
                400, "validation-failed");
    }

    // ----------------------------------------------------------- routing

    @Test
    @DisplayName("an unrouted path is 404 not-found")
    void unroutedPath() {
        assertProblem(call("GET", "/api/v1/nope", token, null), 404, "not-found");
        assertProblem(call("GET", "/api/v1/itineraries/", token, null), 404, "not-found");
    }

    @Test
    @DisplayName("a wrong method is 405 method-not-allowed and keeps the Allow header")
    void wrongMethod() {
        Answer a = call("DELETE", "/api/v1/partners", token, null);
        assertProblem(a, 405, "method-not-allowed");
        assertThat(a.allow()).contains("GET").contains("POST");
    }

    @Test
    @DisplayName("an unsupported media type is 415 and an unacceptable one 406")
    void mediaTypes() {
        HttpRequest wrongType = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/partners"))
                .header("Authorization", "Bearer " + token).header("Content-Type", "text/plain")
                .POST(HttpRequest.BodyPublishers.ofString("x")).build();
        Answer a = send(wrongType);
        assertProblem(a, 415, "unsupported-media-type");
    }

    private Answer send(HttpRequest request) {
        try {
            HttpResponse<String> r = http.send(request, HttpResponse.BodyHandlers.ofString());
            return new Answer(r.statusCode(), r.body(), r.headers().firstValue("Content-Type").orElse(null),
                    r.headers().firstValue("Allow").orElse(null),
                    r.headers().firstValue("WWW-Authenticate").orElse(null));
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    @Test
    @DisplayName("actuator endpoints that are not exposed are a clean 404 for an administrator")
    void actuatorNotExposed() {
        for (String path : List.of("/actuator/env", "/actuator/beans", "/actuator/heapdump", "/actuator/nope")) {
            assertProblem(call("GET", path, token, null), 404, "not-found");
        }
    }

    // ------------------------------------------------------------ 401 and 403

    @Test
    @DisplayName("no token is a 401 problem document that keeps WWW-Authenticate")
    void noToken() {
        Answer a = call("GET", "/api/v1/partners", null, null);
        assertProblem(a, 401, "authentication-required");
        assertThat(a.authenticate()).startsWith("Bearer");
    }

    @Test
    @DisplayName("a bad token is the same 401 and never says why it was rejected")
    void badToken() {
        Answer a = call("GET", "/api/v1/partners", "garbage.token.value", null);
        assertProblem(a, 401, "invalid-token");
        assertThat(a.authenticate()).startsWith("Bearer");
        assertThat(a.authenticate()).doesNotContain("Jwt").doesNotContain("malformed");
        assertThat(a.body()).doesNotContainIgnoringCase("jwt").doesNotContainIgnoringCase("signature");
    }

    @Test
    @DisplayName("an anonymous request to an actuator path is a 401 problem document")
    void actuatorAnonymous() {
        assertProblem(call("GET", "/actuator/env", null, null), 401, "authentication-required");
    }

    // ------------------------------------------- documents without an expiry

    @Test
    @DisplayName("an owner's documents list when one has no expiry date")
    void documentWithoutExpiry() {
        UUID partner = create("/api/v1/partners", "{\"orgUnitId\":\"" + tenant.orgUnitId()
                + "\",\"code\":\"P" + salt() + "\",\"legalName\":\"No Expiry Ltd\",\"partnerType\":\"CUSTOMER\",\"taxId\":\"T"+salt()+"\"}");
        UUID driver = create("/api/v1/drivers", "{\"orgUnitId\":\"" + tenant.orgUnitId()
                + "\",\"fullName\":\"A Driver\",\"phone\":\"9\",\"licenceNo\":\"L" + salt()
                + "\",\"licenceClass\":\"HMV\",\"licenceExpiresOn\":\"2099-01-01\"}");
        UUID vehicle = create("/api/v1/vehicles", "{\"orgUnitId\":\"" + tenant.orgUnitId()
                + "\",\"registrationNo\":\"KA" + salt() + "\",\"category\":\"TRUCK\",\"vehicleType\":\"TRUCK_32FT\","
                + "\"grossWeightKg\":9000,\"tareWeightKg\":3000,\"maxVolumeM3\":40}");
        for (String owner : List.of("partners/" + partner, "drivers/" + driver, "vehicles/" + vehicle)) {
            Answer attach = call("POST", "/api/v1/" + owner + "/documents", token,
                    "{\"documentType\":\"PERMIT\",\"documentNo\":\"D" + salt() + "\"}");
            assertThat(attach.status()).as(owner + " attach: " + attach.body()).isEqualTo(201);
            Answer list = call("GET", "/api/v1/" + owner + "/documents", token, null);
            assertThat(list.status()).as(owner + " list: " + list.body()).isEqualTo(200);
            JsonNode first = list.problem().get(0);
            assertThat(first.get("expired").asBoolean()).isFalse();
        }
    }

    private UUID create(String path, String body) {
        Answer a = call("POST", path, token, body);
        assertThat(a.status()).as(path + ": " + a.body()).isEqualTo(201);
        return UUID.fromString(a.problem().get("id").asString());
    }

    private static String salt() {
        return UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }
}
