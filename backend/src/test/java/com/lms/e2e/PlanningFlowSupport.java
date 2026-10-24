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
import java.util.Collection;
import java.util.List;
import java.util.UUID;

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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Plumbing shared by the planning-flow steps: users with chosen permissions,
 * a second tenant, raw HTTP for the one operation the generated client cannot
 * read, and the assertions on problem documents.
 *
 * <p>The only things done in-process are provisioning, exactly as the
 * existing end-to-end steps do: the API has no user-creation endpoint, so
 * users, roles and a second tenant are inserted directly and then used over
 * HTTP like anybody else.
 */
final class PlanningFlowSupport {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final HttpWorld world;
    private final int port;
    private final JdbcClient jdbc;
    private final PasswordEncoder passwordEncoder;
    private final TransactionTemplate transactions;

    private final List<UUID> ticketsToClose = new ArrayList<>();
    private UUID otherTenantId;
    private String otherTenantToken;

    PlanningFlowSupport(HttpWorld world, int port, JdbcClient jdbc, PasswordEncoder passwordEncoder,
                        PlatformTransactionManager transactionManager) {
        this.world = world;
        this.port = port;
        this.jdbc = jdbc;
        this.passwordEncoder = passwordEncoder;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    // ------------------------------------------------------------ sessions

    String signIn(String tenantCode, String email, String password) {
        AuthResult result = world.client(null, AuthenticationApi.class).login(new LoginRequest()
                .tenantCode(tenantCode).email(email).password(password));
        return result.getAccessToken();
    }

    String demoAdministratorToken() {
        return signIn(HttpWorld.DEMO_TENANT, HttpWorld.DEMO_ADMIN, EndToEndContext.DEMO_PASSWORD);
    }

    /**
     * A user of the demo tenant holding exactly the named permissions
     * (none at all when the list is empty), signed in over HTTP; returns the token.
     */
    String limitedUserToken(String label, Collection<String> permissions) {
        String email = label + "-" + world.salt.toLowerCase() + "@demo.local";
        String password = label + " password " + world.salt;
        UUID tenantId = tenantId(HttpWorld.DEMO_TENANT);
        TenantContext.runWith(tenantId, null, () -> transactions.executeWithoutResult(status -> {
            UUID userId = insertUser(tenantId, world.orgUnitId(), email, password);
            if (!permissions.isEmpty()) {
                UUID roleId = insertRole(tenantId, "PF-" + label.toUpperCase() + "-" + world.salt,
                        permissions);
                grant(tenantId, userId, roleId);
            }
        }));
        return signIn(HttpWorld.DEMO_TENANT, email, password);
    }

    /** A second tenant with its own administrator, signed in over HTTP; returns the token. */
    String otherTenantAdministratorToken() {
        if (otherTenantToken != null) {
            return otherTenantToken;
        }
        String code = "pf-" + world.salt.toLowerCase();
        String email = "admin@" + code + ".local";
        String password = "other tenant " + world.salt;
        UUID tenantId = UUID.randomUUID();
        otherTenantId = tenantId;
        String path = "/" + code + "/";
        TenantContext.runWith(tenantId, path, () -> transactions.executeWithoutResult(status -> {
            jdbc.sql("INSERT INTO tenant (id, code, name, status) VALUES (:id, :code, :name, 'ACTIVE')")
                    .param("id", tenantId).param("code", code).param("name", "PF " + world.salt)
                    .update();
            UUID orgUnitId = UUID.randomUUID();
            jdbc.sql("""
                            INSERT INTO org_unit (id, tenant_id, parent_id, slug, path, name, unit_type)
                            VALUES (:id, :tenantId, NULL, :slug, :path, 'Head Office', 'HQ')
                            """)
                    .param("id", orgUnitId).param("tenantId", tenantId).param("slug", code)
                    .param("path", path).update();
            UUID userId = insertUser(tenantId, orgUnitId, email, password);
            UUID roleId = UUID.randomUUID();
            jdbc.sql("INSERT INTO role (id, tenant_id, code, name) VALUES (:id, :tenantId, 'ADMIN', 'Administrator')")
                    .param("id", roleId).param("tenantId", tenantId).update();
            jdbc.sql("""
                            INSERT INTO role_permission (tenant_id, role_id, permission_id)
                            SELECT :tenantId, :roleId, p.id FROM permission p
                            """)
                    .param("tenantId", tenantId).param("roleId", roleId).update();
            grant(tenantId, userId, roleId);
        }));
        otherTenantToken = signIn(code, email, password);
        return otherTenantToken;
    }

    private UUID insertUser(UUID tenantId, UUID orgUnitId, String email, String password) {
        UUID userId = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO app_user (id, tenant_id, org_unit_id, email, password_hash,
                                              full_name, status)
                        VALUES (:id, :tenantId, :orgUnitId, :email, :hash, 'Planning Flow', 'ACTIVE')
                        """)
                .param("id", userId).param("tenantId", tenantId).param("orgUnitId", orgUnitId)
                .param("email", email).param("hash", passwordEncoder.encode(password)).update();
        return userId;
    }

    private UUID insertRole(UUID tenantId, String code, Collection<String> permissions) {
        UUID roleId = UUID.randomUUID();
        jdbc.sql("INSERT INTO role (id, tenant_id, code, name) VALUES (:id, :tenantId, :code, :code)")
                .param("id", roleId).param("tenantId", tenantId).param("code", code).update();
        jdbc.sql("""
                        INSERT INTO role_permission (tenant_id, role_id, permission_id)
                        SELECT :tenantId, :roleId, p.id FROM permission p WHERE p.code IN (:codes)
                        """)
                .param("tenantId", tenantId).param("roleId", roleId).param("codes", permissions)
                .update();
        return roleId;
    }

    private void grant(UUID tenantId, UUID userId, UUID roleId) {
        jdbc.sql("INSERT INTO user_role (tenant_id, user_id, role_id) VALUES (:t, :u, :r)")
                .param("t", tenantId).param("u", userId).param("r", roleId).update();
    }

    private UUID tenantId(String code) {
        return jdbc.sql("SELECT resolve_tenant_by_code(:code)").param("code", code)
                .query(UUID.class).single();
    }

    // ------------------------------------------------------------- cleanup

    /** Remember a ticket this scenario raised, so a failed scenario cannot leave it open. */
    void remember(UUID ticketId) {
        ticketsToClose.add(ticketId);
    }

    /**
     * Runs whatever the scenario's outcome. Tickets the scenario did not close
     * are closed directly (an open one would sit in every later summary of the
     * shared tenant), and the second tenant is removed.
     */
    void cleanUp() {
        UUID demo = tenantId(HttpWorld.DEMO_TENANT);
        for (UUID ticket : ticketsToClose) {
            try {
                TenantContext.runWith(demo, null, () -> transactions.executeWithoutResult(status ->
                        jdbc.sql("""
                                        UPDATE exception_ticket
                                           SET status = 'CLOSED', closed_by = 'e2e-cleanup',
                                               closed_at = now()
                                         WHERE id = :id AND status <> 'CLOSED'
                                        """)
                                .param("id", ticket).update()));
            } catch (RuntimeException e) {
                // Best effort: the ticket is salted to this scenario's trip either way.
            }
        }
        if (otherTenantId != null) {
            try {
                UUID id = otherTenantId;
                TenantContext.runWith(id, null, () -> transactions.executeWithoutResult(status ->
                        jdbc.sql("DELETE FROM tenant WHERE id = :id").param("id", id).update()));
            } catch (RuntimeException e) {
                // Best effort: a tenant code is salted and nothing else refers to it.
            }
        }
    }

    // ----------------------------------------------------------- raw HTTP

    record Raw(int status, String body) {
        JsonNode json() {
            return JSON.readTree(body);
        }
    }

    Raw get(String path, String token) {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Accept", "application/json").GET();
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        try {
            HttpResponse<String> response = HttpClient.newHttpClient()
                    .send(request.build(), HttpResponse.BodyHandlers.ofString());
            return new Raw(response.statusCode(), response.body());
        } catch (IOException e) {
            throw new AssertionError("GET " + path + " failed: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("GET " + path + " interrupted", e);
        }
    }

    // --------------------------------------------------------- assertions

    /** The call is refused with this status and an RFC 7807 problem carrying this code. */
    static void assertProblem(Runnable call, int status, String code) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(FeignException.class, e -> {
            assertThat(e.status()).as(e.contentUTF8()).isEqualTo(status);
            assertThat(e.contentUTF8()).contains("\"code\":\"" + code + "\"");
            assertThat(e.contentUTF8()).contains("\"status\":" + status);
        });
    }

    static void assertStatus(Runnable call, int status) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(FeignException.class,
                e -> assertThat(e.status()).as(e.contentUTF8()).isEqualTo(status));
    }
}
