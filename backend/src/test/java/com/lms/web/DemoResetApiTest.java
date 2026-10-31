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

import com.lms.acceptance.TenantSeeds;
import com.lms.acceptance.TenantSeeds.Seeded;
import com.lms.acceptance.TestFixtures;
import com.lms.identity.security.TokenService;
import com.lms.web.ApiTestKit.Outcome;
import com.lms.web.ApiTestKit.Tenant;
import org.junit.jupiter.api.BeforeAll;
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
 * Resetting the demo over HTTP, on a deployment with the demo switched on.
 *
 * <p>The administrator signs in for real, so the reset is seen doing what a
 * visitor would see: their data gone, the administrator recreated, and the
 * same password signing in again. The coverage of every table is
 * DemoResetCoverageIntegrationTest's job.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DemoResetApiTest {

    static final String CODE = "demo";
    static final String EMAIL = "admin@demo.local";
    static final String SECRET = "demo reset test secret, not a real one";
    static final String CONFIRM = "{\"confirm\":\"" + CODE + "\"}";

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
        registry.add("lms.demo.enabled", () -> "true");
        registry.add("lms.demo.tenant-code", () -> CODE);
        registry.add("lms.demo.admin-email", () -> EMAIL);
        registry.add("lms.demo.admin-password", () -> SECRET);
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

    private ApiTestKit kit;
    private Seeded other;

    @BeforeAll
    void kit() {
        kit = new ApiTestKit(fixtures, tokens, port);
        other = seeds.seed(0.0);
    }

    private UUID demoTenant() {
        return jdbc.sql("SELECT resolve_tenant_by_code(:c)").param("c", CODE).query(UUID.class).single();
    }

    private Tenant demoCaller() {
        UUID tenant = demoTenant();
        UUID unit = jdbc.sql("SELECT id FROM org_unit WHERE tenant_id = :t AND parent_id IS NULL")
                .param("t", tenant).query(UUID.class).single();
        return kit.tenantOf(tenant, unit, "/" + CODE + "/");
    }

    private Outcome request(String method, String path, String token, String body) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json");
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        return kit.send(builder.method(method, body == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body)).build());
    }

    private JsonNode signIn() {
        Outcome login = request("POST", "/api/v1/auth/login", null,
                "{\"tenantCode\":\"" + CODE + "\",\"email\":\"" + EMAIL + "\",\"password\":\"" + SECRET + "\"}");
        assertThat(login.status()).as(login.body()).isEqualTo(200);
        return kit.parse(login);
    }

    private long count(String table, UUID tenant) {
        return jdbc.sql("SELECT count(*) FROM " + table + " WHERE tenant_id = :t")
                .param("t", tenant).query(Long.class).single();
    }

    @Test
    @DisplayName("a reset removes what visitors created and seeds the administrator again")
    void resetRestoresTheSeed() {
        JsonNode session = signIn();
        String token = session.get("accessToken").asString();
        String unit = session.get("orgUnitId").asString();
        String code = "RESET-" + UUID.randomUUID().toString().substring(0, 8);
        Outcome created = request("POST", "/api/v1/partners", token,
                "{\"orgUnitId\":\"" + unit + "\",\"code\":\"" + code + "\",\"legalName\":\"Reset me\","
                        + "\"partnerType\":\"CUSTOMER\",\"taxId\":\"TAX-" + code + "\"}");
        assertThat(created.status()).as(created.body()).isEqualTo(201);
        UUID partner = UUID.fromString(kit.parse(created).get("id").asString());
        UUID adminBefore = UUID.fromString(session.get("userId").asString());

        Outcome reset = request("POST", "/api/v1/admin/demo-reset", token, CONFIRM);

        assertThat(reset.status()).as(reset.body()).isEqualTo(200);
        JsonNode body = kit.parse(reset);
        assertThat(body.get("tenantCode").asString()).isEqualTo(CODE);
        assertThat(body.get("rowsDeleted").asLong()).isPositive();
        assertThat(body.get("signInAgain").asBoolean()).as("the caller's user was recreated").isTrue();

        JsonNode again = signIn();
        UUID adminAfter = UUID.fromString(again.get("userId").asString());
        assertThat(adminAfter).as("a new administrator, same password").isNotEqualTo(adminBefore);
        Outcome gone = request("GET", "/api/v1/partners/" + partner, again.get("accessToken").asString(), null);
        assertThat(gone.status()).as("the partner a visitor made is gone").isEqualTo(404);
        assertThat(count("access_policy", demoTenant())).as("default access policies are back").isPositive();
        assertThat(jdbc.sql("SELECT count(*) FROM audit_log WHERE tenant_id = :t AND action = 'DEMO_RESET'")
                .param("t", demoTenant()).query(Long.class).single()).as("the reset is audited").isOne();
    }

    @Test
    @DisplayName("resetting twice in a row is fine")
    void resetIsRepeatable() {
        String token = signIn().get("accessToken").asString();
        assertThat(request("POST", "/api/v1/admin/demo-reset", token, CONFIRM).status()).isEqualTo(200);
        String fresh = signIn().get("accessToken").asString();
        Outcome second = request("POST", "/api/v1/admin/demo-reset", fresh, CONFIRM);
        assertThat(second.status()).as(second.body()).isEqualTo(200);
    }

    @Test
    @DisplayName("a reset that does not name the demo is refused and changes nothing")
    void needsConfirmation() {
        JsonNode session = signIn();
        String token = session.get("accessToken").asString();
        for (String body : new String[] {null, "{}", "{\"confirm\":\"other\"}"}) {
            Outcome refused = request("POST", "/api/v1/admin/demo-reset", token, body);
            assertThat(refused.status()).as(String.valueOf(body)).isEqualTo(409);
            assertThat(refused.code()).isEqualTo("demo-reset-unconfirmed");
        }
        assertThat(signIn().get("userId").asString()).as("the administrator was not recreated")
                .isEqualTo(session.get("userId").asString());
    }

    @Test
    @DisplayName("a reset needs DEMO_RESET")
    void needsPermission() {
        Outcome refused = kit.post(demoCaller(), "/api/v1/admin/demo-reset", CONFIRM, "PARTNER_READ");
        assertThat(refused.status()).isEqualTo(403);
    }

    @Test
    @DisplayName("from any other tenant the action is not there, and that tenant keeps every row")
    void otherTenantsCannotReset() {
        Tenant caller = kit.tenantOf(other.tenantId(), other.orgUnitId(), other.orgPath());
        long partners = count("business_partner", other.tenantId());

        Outcome refused = kit.post(caller, "/api/v1/admin/demo-reset", CONFIRM, "DEMO_RESET");

        assertThat(refused.status()).as(refused.body()).isEqualTo(404);
        assertThat(refused.code()).isEqualTo("not-found");
        assertThat(count("business_partner", other.tenantId())).isEqualTo(partners);
    }
}
