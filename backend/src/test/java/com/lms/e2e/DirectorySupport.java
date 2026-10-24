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

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import com.lms.client.api.AuthenticationApi;
import com.lms.client.model.AuthResult;
import com.lms.client.model.LoginRequest;
import com.lms.shared.tenant.TenantContext;
import feign.FeignException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the directory scenarios share beyond {@link HttpWorld}: a second
 * tenant, limited-permission users and a branch unit provisioned in-process
 * (the one thing the end-to-end suite may do outside HTTP, because the API
 * has no endpoint to create a tenant or a user), plus readers for problem
 * documents and tokens.
 *
 * <p>Every write goes through the same scope-first, transaction-second path
 * the production code and {@code TestFixtures} use, because row-level
 * security applies to inserts too. Everything created here is salted and is
 * left behind: nothing in the shared demo tenant is changed or removed.
 */
final class DirectorySupport {

    static final String PASSWORD = "directory e2e password, not a real one";

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** A tenant provisioned for the scenario, with its administrator. */
    record Tenant(UUID id, String code, String adminEmail) {
    }

    /** A user provisioned for the scenario. */
    record Account(UUID id, String email, UUID orgUnitId, String orgPath) {
    }

    /**
     * The people a directory scenario talks as: the demo administrator, the
     * administrator of a second tenant, a user who may read the directory but
     * change nothing, and a user who holds no permission at all.
     */
    record Cast(AuthResult demo, Tenant other, AuthResult otherAdmin, Account readerAccount,
                AuthResult reader, Account nobodyAccount, AuthResult nobody) {
    }

    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;
    private final PasswordEncoder passwordEncoder;
    private final int port;

    DirectorySupport(JdbcClient jdbc, PlatformTransactionManager transactionManager,
                     PasswordEncoder passwordEncoder, int port) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(transactionManager);
        this.passwordEncoder = passwordEncoder;
        this.port = port;
    }

    // ----------------------------------------------------- provisioning

    /** A whole second tenant: root unit, an administrator role holding every permission, one administrator. */
    Tenant tenant(String code) {
        UUID tenantId = UUID.randomUUID();
        String rootPath = "/" + code + "/";
        inTenant(tenantId, null, () -> jdbc.sql("""
                        INSERT INTO tenant (id, code, name, status)
                        VALUES (:id, :code, :name, 'ACTIVE')
                        """)
                .param("id", tenantId).param("code", code).param("name", "E2E " + code)
                .update());
        UUID rootUnit = orgUnit(tenantId, null, rootPath, "E2E Head Office", "HQ");

        UUID roleId = UUID.randomUUID();
        inTenant(tenantId, rootPath, () -> {
            jdbc.sql("INSERT INTO role (id, tenant_id, code, name) VALUES (:id, :t, 'ADMIN', 'Administrator')")
                    .param("id", roleId).param("t", tenantId).update();
            jdbc.sql("""
                            INSERT INTO role_permission (tenant_id, role_id, permission_id)
                            SELECT :t, :r, p.id FROM permission p
                            """)
                    .param("t", tenantId).param("r", roleId).update();
            return null;
        });
        String email = "admin@" + code + ".local";
        insertUser(tenantId, rootUnit, rootPath, email, "Administrator " + code, roleId);
        return new Tenant(tenantId, code, email);
    }

    UUID orgUnit(UUID tenantId, UUID parentId, String path, String name, String type) {
        UUID id = UUID.randomUUID();
        String trimmed = path.replaceAll("^/|/$", "");
        String slug = trimmed.substring(trimmed.lastIndexOf('/') + 1);
        inTenant(tenantId, path, () -> jdbc.sql("""
                        INSERT INTO org_unit (id, tenant_id, parent_id, slug, path, name, unit_type)
                        VALUES (:id, :t, :parent, :slug, :path, :name, :type)
                        """)
                .param("id", id).param("t", tenantId).param("parent", parentId)
                .param("slug", slug).param("path", path).param("name", name).param("type", type)
                .update());
        return id;
    }

    /** A user holding exactly the named permissions, through a role of its own. */
    Account user(UUID tenantId, UUID orgUnitId, String orgPath, String email, String fullName,
                 String roleCode, String... permissions) {
        UUID roleId = UUID.randomUUID();
        inTenant(tenantId, orgPath, () -> {
            jdbc.sql("INSERT INTO role (id, tenant_id, code, name) VALUES (:id, :t, :code, :code)")
                    .param("id", roleId).param("t", tenantId).param("code", roleCode).update();
            for (String permission : permissions) {
                int granted = jdbc.sql("""
                                INSERT INTO role_permission (tenant_id, role_id, permission_id)
                                SELECT :t, :r, p.id FROM permission p WHERE p.code = :code
                                """)
                        .param("t", tenantId).param("r", roleId).param("code", permission).update();
                assertThat(granted).as("permission " + permission + " exists").isEqualTo(1);
            }
            return null;
        });
        UUID id = insertUser(tenantId, orgUnitId, orgPath, email, fullName, roleId);
        return new Account(id, email, orgUnitId, orgPath);
    }

    void setStatus(UUID tenantId, String orgPath, UUID userId, String status) {
        inTenant(tenantId, orgPath, () -> jdbc.sql("UPDATE app_user SET status = :s WHERE id = :id")
                .param("s", status).param("id", userId).update());
    }

    private UUID insertUser(UUID tenantId, UUID orgUnitId, String orgPath, String email,
                            String fullName, UUID roleId) {
        UUID id = UUID.randomUUID();
        String hash = passwordEncoder.encode(PASSWORD);
        inTenant(tenantId, orgPath, () -> {
            jdbc.sql("""
                            INSERT INTO app_user (id, tenant_id, org_unit_id, email, password_hash, full_name, status)
                            VALUES (:id, :t, :unit, :email, :hash, :name, 'ACTIVE')
                            """)
                    .param("id", id).param("t", tenantId).param("unit", orgUnitId)
                    .param("email", email).param("hash", hash).param("name", fullName).update();
            jdbc.sql("INSERT INTO user_role (tenant_id, user_id, role_id) VALUES (:t, :u, :r)")
                    .param("t", tenantId).param("u", id).param("r", roleId).update();
            return null;
        });
        return id;
    }

    private <T> T inTenant(UUID tenantId, String orgPath, Supplier<T> action) {
        // Scope first, transaction second: the transaction manager reads the
        // scope while starting the transaction.
        return TenantContext.callWith(tenantId, orgPath, () -> transactions.execute(status -> action.get()));
    }

    Cast cast(HttpWorld world) {
        String tag = world.salt.toLowerCase();
        AuthResult demo = login(HttpWorld.DEMO_TENANT, HttpWorld.DEMO_ADMIN, EndToEndContext.DEMO_PASSWORD);
        Tenant other = tenant("e2e-dir-" + tag);
        AuthResult otherAdmin = login(other.code(), other.adminEmail());

        Account reader = user(demo.getTenantId(), demo.getOrgUnitId(), demo.getOrgPath(),
                "reader-" + tag + "@demo.local", "Reader " + world.salt, "E2E-READER-" + world.salt,
                "PARTNER_READ", "VEHICLE_READ", "DRIVER_READ", "TERMINAL_READ");
        Account nobody = user(demo.getTenantId(), demo.getOrgUnitId(), demo.getOrgPath(),
                "nobody-" + tag + "@demo.local", "Nobody " + world.salt, "E2E-NOBODY-" + world.salt);
        return new Cast(demo, other, otherAdmin, reader,
                login(HttpWorld.DEMO_TENANT, reader.email()), nobody,
                login(HttpWorld.DEMO_TENANT, nobody.email()));
    }

    // --------------------------------------------------------- sessions

    AuthResult login(String tenantCode, String email) {
        return login(tenantCode, email, PASSWORD);
    }

    AuthResult login(String tenantCode, String email, String password) {
        return new HttpWorld(port).client(null, AuthenticationApi.class).login(new LoginRequest()
                .tenantCode(tenantCode).email(email).password(password));
    }

    // ------------------------------------------------- reading answers

    /** The call must be refused with this status and problem code; returns the problem document. */
    static JsonNode refusedWith(int status, String code, Runnable call) {
        FeignException refused = null;
        try {
            call.run();
        } catch (FeignException e) {
            refused = e;
        }
        assertThat(refused).as("the call is refused with " + status + " " + code).isNotNull();
        assertThat(refused.status()).isEqualTo(status);
        JsonNode problem = JSON.readTree(refused.contentUTF8());
        assertThat(problem.get("code").asString()).as("problem code").isEqualTo(code);
        assertThat(problem.get("status").asInt()).isEqualTo(status);
        return problem;
    }

    /** What a raw call answered: the status, and the problem document if there was a body. */
    record Raw(int status, JsonNode body) {

        Raw refused(int expectedStatus, String expectedCode) {
            assertThat(status).as("status of " + body).isEqualTo(expectedStatus);
            assertThat(body).as("a problem document").isNotNull();
            assertThat(body.get("code").asString()).as("problem code").isEqualTo(expectedCode);
            assertThat(body.get("status").asInt()).isEqualTo(expectedStatus);
            return this;
        }
    }

    /**
     * A POST made without the generated client. The client's transport cannot
     * read the body of a 401 to a POST (the JDK refuses to retry the
     * authentication challenge of a streamed request), and the problem code of
     * a refused sign-in or refresh is exactly what these scenarios assert.
     */
    Raw rawPost(String token, String path, String json) {
        try {
            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                    .header("Content-Type", "application/json").header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json));
            if (token != null) {
                request.header("Authorization", "Bearer " + token);
            }
            HttpResponse<String> response = HttpClient.newHttpClient().send(request.build(),
                    HttpResponse.BodyHandlers.ofString());
            String body = response.body();
            return new Raw(response.statusCode(), body == null || body.isBlank() ? null : JSON.readTree(body));
        } catch (IOException e) {
            throw new AssertionError("POST " + path + " failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("POST " + path + " interrupted", e);
        }
    }

    Raw rawLogin(String tenantCode, String email, String password) {
        return rawPost(null, "/api/v1/auth/login", JSON.writeValueAsString(
                java.util.Map.of("tenantCode", tenantCode, "email", email, "password", password)));
    }

    Raw rawRefresh(String refreshToken) {
        return rawPost(null, "/api/v1/auth/refresh",
                JSON.writeValueAsString(java.util.Map.of("refreshToken", refreshToken)));
    }

    /** The claims of a signed access token, read without verifying it. */
    static JsonNode claims(String accessToken) {
        String payload = accessToken.split("\\.")[1];
        return JSON.readTree(new String(Base64.getUrlDecoder().decode(payload)));
    }

    /** A GET made without the generated client, for the answers its model cannot hold. */
    JsonNode rawGet(String token, String path) {
        try {
            HttpResponse<String> response = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                            .header("Authorization", "Bearer " + token)
                            .header("Accept", "application/json").GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).as("GET " + path).isEqualTo(200);
            return JSON.readTree(response.body());
        } catch (IOException e) {
            throw new AssertionError("GET " + path + " failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("GET " + path + " interrupted", e);
        }
    }

    static List<String> texts(JsonNode array, String field) {
        List<String> values = new ArrayList<>();
        for (JsonNode node : array) {
            values.add(node.get(field).asString());
        }
        return values;
    }
}
