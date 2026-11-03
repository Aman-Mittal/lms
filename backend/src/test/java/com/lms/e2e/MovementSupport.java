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
package com.lms.e2e;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lms.client.ApiClient;
import com.lms.client.api.AuthenticationApi;
import com.lms.client.model.AuthResult;
import com.lms.client.model.LoginRequest;
import com.lms.shared.tenant.TenantContext;
import feign.FeignException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What the movement scenarios (telematics reads, rail rate cards, milk-run
 * routes) share beyond {@link HttpWorld}: callers with only some permissions,
 * a second tenant, problem documents, and raw reads for the few answers the
 * generated client cannot carry.
 *
 * <p>The only things done in-process are the ones the API has no endpoint for:
 * provisioning a role, a user and a tenant. Everything a scenario asserts goes
 * over HTTP.
 *
 * <p>Raw reads exist because the contract and the server disagree on the
 * shape of some telematics answers (see the notes in the scenarios); the
 * generated model drops a field it does not know, so asserting on the server's
 * real field names needs the JSON itself.
 */
final class MovementSupport {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final double EARTH_RADIUS_M = 6_371_008.8;

    private final HttpWorld world;
    private final int port;
    private final JdbcClient jdbc;
    private final PasswordEncoder passwordEncoder;
    private final PlatformTransactionManager transactionManager;
    private AuthResult admin;

    MovementSupport(HttpWorld world, int port, JdbcClient jdbc, PasswordEncoder passwordEncoder,
                    PlatformTransactionManager transactionManager) {
        this.world = world;
        this.port = port;
        this.jdbc = jdbc;
        this.passwordEncoder = passwordEncoder;
        this.transactionManager = transactionManager;
    }

    /** A caller of the demo tenant, with exactly the named permissions and no others. */
    AuthResult callerWith(String tag, String... permissions) {
        String email = tag + "-" + world.salt.toLowerCase() + "@demo.local";
        String password = "limited " + tag + " " + world.salt;
        UUID tenantId = demoTenantId();
        UUID orgUnitId = world.orgUnitId();
        TransactionTemplate transactions = new TransactionTemplate(transactionManager);
        TenantContext.runWith(tenantId, null, () -> transactions.executeWithoutResult(status -> {
            UUID roleId = UUID.randomUUID();
            insertRole(tenantId, roleId, "MV-" + tag.toUpperCase() + "-" + world.salt, permissions);
            insertUser(tenantId, orgUnitId, email, password, roleId);
        }));
        return login("demo", email, password);
    }

    /** A second tenant with its own administrator, who holds every permission. */
    AuthResult otherTenantAdministrator() {
        String code = "mv-" + world.salt.toLowerCase();
        UUID tenantId = UUID.randomUUID();
        UUID orgUnitId = UUID.randomUUID();
        String path = "/" + code + "/";
        String email = "admin@" + code + ".local";
        String password = "other tenant " + world.salt;
        TransactionTemplate transactions = new TransactionTemplate(transactionManager);
        TenantContext.runWith(tenantId, path, () -> transactions.executeWithoutResult(status -> {
            jdbc.sql("""
                            INSERT INTO tenant (id, code, name, status)
                            VALUES (:id, :code, :name, 'ACTIVE')
                            """)
                    .param("id", tenantId).param("code", code).param("name", "Movement " + code)
                    .update();
            jdbc.sql("""
                            INSERT INTO org_unit (id, tenant_id, parent_id, slug, path, name, unit_type)
                            VALUES (:id, :tenantId, NULL, :slug, :path, 'Head Office', 'HQ')
                            """)
                    .param("id", orgUnitId).param("tenantId", tenantId).param("slug", code)
                    .param("path", path).update();
            UUID roleId = UUID.randomUUID();
            insertRole(tenantId, roleId, "ADMIN", null);
            insertUser(tenantId, orgUnitId, email, password, roleId);
        }));
        return login(code, email, password);
    }

    <T extends ApiClient.Api> T as(AuthResult caller, Class<T> type) {
        return world.client(caller.getAccessToken(), type);
    }

    private void insertRole(UUID tenantId, UUID roleId, String code, String[] permissions) {
        jdbc.sql("INSERT INTO role (id, tenant_id, code, name) VALUES (:id, :tenantId, :code, :code)")
                .param("id", roleId).param("tenantId", tenantId).param("code", code).update();
        if (permissions == null) {
            jdbc.sql("""
                            INSERT INTO role_permission (tenant_id, role_id, permission_id)
                            SELECT :tenantId, :roleId, p.id FROM permission p
                            """)
                    .param("tenantId", tenantId).param("roleId", roleId).update();
        } else {
            for (String permission : permissions) {
                int rows = jdbc.sql("""
                                INSERT INTO role_permission (tenant_id, role_id, permission_id)
                                SELECT :tenantId, :roleId, p.id FROM permission p WHERE p.code = :code
                                """)
                        .param("tenantId", tenantId).param("roleId", roleId)
                        .param("code", permission).update();
                assertThat(rows).as("permission " + permission + " exists").isEqualTo(1);
            }
        }
    }

    private void insertUser(UUID tenantId, UUID orgUnitId, String email, String password,
                            UUID roleId) {
        UUID userId = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO app_user (id, tenant_id, org_unit_id, email, password_hash,
                                              full_name, status)
                        VALUES (:id, :tenantId, :orgUnitId, :email, :hash, :email, 'ACTIVE')
                        """)
                .param("id", userId).param("tenantId", tenantId).param("orgUnitId", orgUnitId)
                .param("email", email).param("hash", passwordEncoder.encode(password)).update();
        jdbc.sql("INSERT INTO user_role (tenant_id, user_id, role_id) VALUES (:t, :u, :r)")
                .param("t", tenantId).param("u", userId).param("r", roleId).update();
    }

    private UUID demoTenantId() {
        return jdbc.sql("SELECT resolve_tenant_by_code(:code)")
                .param("code", HttpWorld.DEMO_TENANT).query(UUID.class).single();
    }

    private AuthResult login(String tenantCode, String email, String password) {
        return world.client(null, AuthenticationApi.class).login(new LoginRequest()
                .tenantCode(tenantCode).email(email).password(password));
    }

    // ------------------------------------------------------------ problems

    /** The refusal's problem document: its status and stable code. */
    static void assertProblem(Throwable thrown, int status, String code) {
        assertThat(thrown).isInstanceOf(FeignException.class);
        FeignException refusal = (FeignException) thrown;
        assertThat(refusal.status()).as("status of " + refusal.contentUTF8()).isEqualTo(status);
        assertThat(problemOf(refusal).path("code").asText())
                .as("problem code in " + refusal.contentUTF8()).isEqualTo(code);
    }

    static JsonNode problemOf(FeignException refusal) {
        return parse(refusal.contentUTF8());
    }

    static Throwable refusalOf(Runnable call) {
        return org.assertj.core.api.Assertions.catchThrowable(call::run);
    }

    static void assertRefused(Runnable call, int status, String code) {
        Throwable thrown = refusalOf(call);
        assertThat(thrown).as("the call should have been refused").isNotNull();
        assertProblem(thrown, status, code);
    }

    static void assertStatus(Runnable call, int status) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(FeignException.class,
                e -> assertThat(e.status()).as(e.contentUTF8()).isEqualTo(status));
    }

    // ------------------------------------------------------------- raw JSON

    /** GETs a path as the demo administrator and parses the answer, whatever its shape. */
    JsonNode rawGet(String path) {
        return rawGet(adminToken(), path);
    }

    JsonNode rawGet(AuthResult caller, String path) {
        return rawGet(caller.getAccessToken(), path);
    }

    private JsonNode rawGet(String token, String path) {
        try {
            HttpResponse<String> response = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                            .header("Authorization", "Bearer " + token)
                            .header("Accept", "application/json").GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).as(path + " -> " + response.body()).isEqualTo(200);
            return parse(response.body());
        } catch (java.io.IOException e) {
            throw new AssertionError("GET " + path + " failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("GET " + path + " interrupted", e);
        }
    }

    private String adminToken() {
        if (admin == null) {
            admin = login(HttpWorld.DEMO_TENANT, HttpWorld.DEMO_ADMIN, EndToEndContext.DEMO_PASSWORD);
        }
        return admin.getAccessToken();
    }

    private static JsonNode parse(String body) {
        try {
            return JSON.readTree(body);
        } catch (java.io.IOException e) {
            throw new AssertionError("Not JSON: " + body, e);
        }
    }

    /** The element of a JSON array whose field has the given text, or null. */
    static JsonNode elementWhere(JsonNode array, String field, String value) {
        for (JsonNode element : array) {
            if (value.equals(element.path(field).asText(null))) {
                return element;
            }
        }
        return null;
    }

    /** Whether the server sent null or left the field out. */
    static boolean absent(JsonNode node) {
        return node == null || node.isMissingNode() || node.isNull();
    }

    static Instant instant(JsonNode node) {
        return Instant.parse(node.asText());
    }

    static List<String> texts(JsonNode array, String field) {
        java.util.ArrayList<String> out = new java.util.ArrayList<>();
        array.forEach(element -> out.add(element.path(field).asText()));
        return out;
    }

    // ------------------------------------------------------------- geometry

    /** The great-circle distance the platform itself uses for progress and corridors. */
    static double haversineMetres(double lat1, double lon1, double lat2, double lon2) {
        double phi1 = Math.toRadians(lat1);
        double phi2 = Math.toRadians(lat2);
        double dPhi = Math.toRadians(lat2 - lat1);
        double dLambda = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dPhi / 2) * Math.sin(dPhi / 2)
                + Math.cos(phi1) * Math.cos(phi2) * Math.sin(dLambda / 2) * Math.sin(dLambda / 2);
        return 2 * EARTH_RADIUS_M * Math.asin(Math.sqrt(a));
    }
}
