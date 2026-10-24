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
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.yaml.snakeyaml.Yaml;

import com.lms.shared.tenant.TenantContext;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Plumbing for the API-contract scenarios: raw HTTP, so that status codes,
 * headers and problem documents can be read exactly as a consumer sees them,
 * and the in-process provisioning of the extra callers those scenarios need
 * (a user with one permission, a second tenant).
 *
 * <p>Raw HTTP rather than the generated client on purpose. The generated client
 * turns every non-2xx answer into an exception and parses 2xx bodies into
 * models, which is exactly what must not stand between this suite and a
 * header, a content type or a malformed body. Everything else in the suite
 * keeps using the client; here the wire is the subject.
 *
 * <p>JSON is read with SnakeYAML (JSON is YAML), which the contract test
 * already depends on, so this class adds no library.
 */
final class ContractSupport {

    static final String PASSWORD = "contract password, not a real one";

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10)).build();

    /** What came back, read the way a consumer reads it. */
    record Reply(int status, String contentType, Map<String, List<String>> headers, String body) {

        String header(String name) {
            return headers.entrySet().stream()
                    .filter(e -> e.getKey().equalsIgnoreCase(name))
                    .flatMap(e -> e.getValue().stream()).findFirst().orElse(null);
        }

        boolean isProblem() {
            return contentType != null && contentType.startsWith("application/problem+json");
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> json() {
            Object parsed = new Yaml().load(body);
            assertThat(parsed).as("a JSON object in: " + body).isInstanceOf(Map.class);
            return (Map<String, Object>) parsed;
        }

        @SuppressWarnings("unchecked")
        List<Object> jsonList() {
            Object parsed = new Yaml().load(body);
            assertThat(parsed).as("a JSON array in: " + body).isInstanceOf(List.class);
            return (List<Object>) parsed;
        }

        String code() {
            return String.valueOf(json().get("code"));
        }

        UUID id() {
            return UUID.fromString((String) json().get("id"));
        }

        @Override
        public String toString() {
            return status + " " + contentType + " " + body;
        }
    }

    private final int port;
    private final JdbcClient jdbc;
    private final PasswordEncoder passwordEncoder;
    private final PlatformTransactionManager transactionManager;
    private final JwtEncoder jwtEncoder;

    ContractSupport(int port, JdbcClient jdbc, PasswordEncoder passwordEncoder,
                    PlatformTransactionManager transactionManager, JwtEncoder jwtEncoder) {
        this.port = port;
        this.jdbc = jdbc;
        this.passwordEncoder = passwordEncoder;
        this.transactionManager = transactionManager;
        this.jwtEncoder = jwtEncoder;
    }

    // --------------------------------------------------------------- wire

    Reply send(String method, String path, String token, String body, String... headerPairs) {
        HttpRequest.Builder request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(60));
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        if (body != null) {
            request.header("Content-Type", "application/json");
        }
        for (int i = 0; i + 1 < headerPairs.length; i += 2) {
            // setHeader, so that a caller can replace the default Content-Type.
            request.setHeader(headerPairs[i], headerPairs[i + 1]);
        }
        request.method(method, body == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        try {
            HttpResponse<String> response = HTTP.send(request.build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return new Reply(response.statusCode(),
                    response.headers().firstValue("Content-Type").orElse(null),
                    response.headers().map(), response.body());
        } catch (java.io.IOException e) {
            throw new AssertionError(method + " " + path + " failed on the wire", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(method + " " + path + " interrupted", e);
        }
    }

    Reply get(String path, String token) {
        return send("GET", path, token, null);
    }

    Reply post(String path, String token, String body, String... headerPairs) {
        return send("POST", path, token, body, headerPairs);
    }

    /** The access token a real sign-in issues. */
    String login(String tenantCode, String email, String password) {
        Reply reply = post("/api/v1/auth/login", null, """
                {"tenantCode":"%s","email":"%s","password":"%s"}"""
                .formatted(tenantCode, email, password));
        assertThat(reply.status()).as("login: " + reply).isEqualTo(200);
        return (String) reply.json().get("accessToken");
    }

    // ------------------------------------------------------------- tokens

    /**
     * A token the server really did sign, claims as a sign-in would put them,
     * but already expired. Distinct from a garbage string: this one fails only
     * because of its expiry.
     */
    String expiredToken(UUID tenantId, String orgPath, List<String> permissions) {
        Instant past = Instant.now().minusSeconds(3600);
        return mint(tenantId, orgPath, permissions, past.minusSeconds(3600), past);
    }

    String mint(UUID tenantId, String orgPath, List<String> permissions,
                Instant issuedAt, Instant expiresAt) {
        JwtClaimsSet.Builder claims = JwtClaimsSet.builder()
                .issuer("lms-platform")
                .issuedAt(issuedAt).expiresAt(expiresAt)
                .subject(UUID.randomUUID().toString())
                .claim("org", orgPath)
                .claim("perms", permissions);
        if (tenantId != null) {
            claims.claim("tenant", tenantId.toString());
        }
        return jwtEncoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(SignatureAlgorithm.RS256).build(), claims.build())).getTokenValue();
    }

    UUID tenantIdOf(String tenantCode) {
        return jdbc.sql("SELECT resolve_tenant_by_code(:code)")
                .param("code", tenantCode).query(UUID.class).single();
    }

    // ------------------------------------------------------ provisioning

    /** A user in the demo tenant holding exactly the named permissions. */
    record Caller(UUID tenantId, UUID userId, UUID roleId, String tenantCode, String email) {
    }

    Caller provisionUser(String tenantCode, UUID orgUnitId, String salt, List<String> permissions) {
        UUID tenantId = tenantIdOf(tenantCode);
        UUID userId = UUID.randomUUID();
        UUID roleId = UUID.randomUUID();
        String email = "contract-" + salt.toLowerCase(Locale.ROOT) + "-"
                + userId.toString().substring(0, 8) + "@" + tenantCode + ".local";
        inTenant(tenantId, null, () -> {
            jdbc.sql("INSERT INTO role (id, tenant_id, code, name) VALUES (:id, :t, :code, :name)")
                    .param("id", roleId).param("t", tenantId)
                    .param("code", "CONTRACT-" + salt + "-" + roleId.toString().substring(0, 6))
                    .param("name", "Contract test role").update();
            for (String permission : permissions) {
                int granted = jdbc.sql("""
                                INSERT INTO role_permission (tenant_id, role_id, permission_id)
                                SELECT :t, :r, id FROM permission WHERE code = :code
                                """)
                        .param("t", tenantId).param("r", roleId).param("code", permission)
                        .update();
                assertThat(granted).as("permission " + permission + " exists").isEqualTo(1);
            }
            jdbc.sql("""
                            INSERT INTO app_user (id, tenant_id, org_unit_id, email, password_hash,
                                                  full_name, status)
                            VALUES (:id, :t, :org, :email, :hash, 'Contract Caller', 'ACTIVE')
                            """)
                    .param("id", userId).param("t", tenantId).param("org", orgUnitId)
                    .param("email", email).param("hash", passwordEncoder.encode(PASSWORD)).update();
            jdbc.sql("INSERT INTO user_role (tenant_id, user_id, role_id) VALUES (:t, :u, :r)")
                    .param("t", tenantId).param("u", userId).param("r", roleId).update();
        });
        return new Caller(tenantId, userId, roleId, tenantCode, email);
    }

    void remove(Caller caller) {
        inTenant(caller.tenantId(), null, () -> {
            jdbc.sql("DELETE FROM user_role WHERE tenant_id = :t AND user_id = :u")
                    .param("t", caller.tenantId()).param("u", caller.userId()).update();
            jdbc.sql("DELETE FROM refresh_token WHERE tenant_id = :t AND user_id = :u")
                    .param("t", caller.tenantId()).param("u", caller.userId()).update();
            jdbc.sql("DELETE FROM app_user WHERE tenant_id = :t AND id = :u")
                    .param("t", caller.tenantId()).param("u", caller.userId()).update();
            jdbc.sql("DELETE FROM role_permission WHERE tenant_id = :t AND role_id = :r")
                    .param("t", caller.tenantId()).param("r", caller.roleId()).update();
            jdbc.sql("DELETE FROM role WHERE tenant_id = :t AND id = :r")
                    .param("t", caller.tenantId()).param("r", caller.roleId()).update();
        });
    }

    /** A tenant of its own with an administrator holding every permission. */
    record OtherTenant(UUID tenantId, UUID orgUnitId, String code, String email) {
    }

    OtherTenant provisionTenant(String salt) {
        UUID tenantId = UUID.randomUUID();
        UUID orgUnitId = UUID.randomUUID();
        UUID roleId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        String slug = "contract-" + salt.toLowerCase(Locale.ROOT);
        String email = "admin@" + slug + ".local";
        inTenant(tenantId, "/" + slug + "/", () -> {
            jdbc.sql("INSERT INTO tenant (id, code, name, status) VALUES (:id, :code, :name, 'ACTIVE')")
                    .param("id", tenantId).param("code", slug).param("name", "Contract " + salt)
                    .update();
            jdbc.sql("""
                            INSERT INTO org_unit (id, tenant_id, parent_id, slug, path, name, unit_type)
                            VALUES (:id, :t, NULL, :slug, :path, 'Head Office', 'HQ')
                            """)
                    .param("id", orgUnitId).param("t", tenantId).param("slug", slug)
                    .param("path", "/" + slug + "/").update();
            jdbc.sql("INSERT INTO role (id, tenant_id, code, name) VALUES (:id, :t, 'ADMIN', 'Administrator')")
                    .param("id", roleId).param("t", tenantId).update();
            jdbc.sql("""
                            INSERT INTO role_permission (tenant_id, role_id, permission_id)
                            SELECT :t, :r, id FROM permission
                            """)
                    .param("t", tenantId).param("r", roleId).update();
            jdbc.sql("""
                            INSERT INTO app_user (id, tenant_id, org_unit_id, email, password_hash,
                                                  full_name, status)
                            VALUES (:id, :t, :org, :email, :hash, 'Other Tenant Admin', 'ACTIVE')
                            """)
                    .param("id", userId).param("t", tenantId).param("org", orgUnitId)
                    .param("email", email).param("hash", passwordEncoder.encode(PASSWORD)).update();
            jdbc.sql("INSERT INTO user_role (tenant_id, user_id, role_id) VALUES (:t, :u, :r)")
                    .param("t", tenantId).param("u", userId).param("r", roleId).update();
        });
        return new OtherTenant(tenantId, orgUnitId, slug, email);
    }

    private void inTenant(UUID tenantId, String path, Runnable work) {
        TransactionTemplate transactions = new TransactionTemplate(transactionManager);
        // Scope first, transaction second: AGENTS.md, "Bind the tenant scope
        // BEFORE the transaction opens".
        TenantContext.runWith(tenantId, path, () ->
                transactions.executeWithoutResult(status -> work.run()));
    }

    // --------------------------------------------------------- utilities

    static Map<String, Object> obj(Object... keyValues) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            map.put((String) keyValues[i], keyValues[i + 1]);
        }
        return map;
    }
}
