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

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.lms.acceptance.TestFixtures;
import com.lms.identity.command.domain.AppUser;
import com.lms.identity.security.TokenService;
import com.lms.shared.tenant.TenantContext;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Administering users and roles over HTTP (issue 258, demo mode): the login id
 * and password typed by the administrator, the standard roles, the
 * administrator guards and their problem codes, and the audit trail.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class UsersRolesApiTest {

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
    }

    /** One tenant with the standard roles and a real administrator, who is the caller. */
    record World(UUID tenantId, String code, String path, UUID adminId, Tenant caller, Tenant ghost) {
    }

    @LocalServerPort
    private int port;
    @Autowired
    private TestFixtures fixtures;
    @Autowired
    private TokenService tokens;
    @Autowired
    private JdbcClient jdbc;
    @Autowired
    private PlatformTransactionManager transactionManager;

    private ApiTestKit kit;
    private String[] everything;

    @BeforeAll
    void kit() {
        kit = new ApiTestKit(fixtures, tokens, port);
        everything = jdbc.sql("SELECT code FROM permission").query(String.class).list().toArray(String[]::new);
    }

    // ---------------------------------------------------------------- helpers

    /** A password built at run time: letters and digits, long enough, never written in the source. */
    private static String secret() {
        return "Zq7" + UUID.randomUUID();
    }

    private <T> T inTenant(World w, java.util.function.Supplier<T> work) {
        return TenantContext.callWith(w.tenantId(), w.path(),
                () -> new TransactionTemplate(transactionManager).execute(s -> work.get()));
    }

    private World world() {
        String salt = UUID.randomUUID().toString().substring(0, 8);
        String code = "ur-" + salt;
        String path = "/" + code + "/";
        UUID tenantId = fixtures.createTenant(code, "Users and roles " + salt);
        UUID unit = fixtures.createOrgUnit(tenantId, path, "HQ", "HQ", null);
        UUID adminId = fixtures.createUser(tenantId, unit, path, "admin@" + salt + ".test", secret());
        World bare = new World(tenantId, code, path, adminId, null, null);
        inTenant(bare, () -> {
            jdbc.sql("SELECT count(*) FROM (SELECT seed_standard_roles(:t)) s").param("t", tenantId)
                    .query(Long.class).single();
            jdbc.sql("""
                            INSERT INTO user_role (tenant_id, user_id, role_id)
                            SELECT :t, :u, id FROM role WHERE tenant_id = :t AND code = 'ADMIN'
                            """).param("t", tenantId).param("u", adminId).update();
            return null;
        });
        AppUser self = new AppUser(adminId, tenantId, unit, "admin@" + salt + ".test", "x", "Admin",
                AppUser.UserStatus.ACTIVE, null, 0, null, null, null, Instant.now(), Instant.now());
        AppUser stranger = new AppUser(UUID.randomUUID(), tenantId, unit, "svc@" + salt + ".test", "x", "Svc",
                AppUser.UserStatus.ACTIVE, null, 0, null, null, null, Instant.now(), Instant.now());
        return new World(tenantId, code, path, adminId, new Tenant(tenantId, unit, path, self),
                new Tenant(tenantId, unit, path, stranger));
    }

    private Outcome post(Tenant as, String path, String body, String... perms) {
        return kit.post(as, path, body, perms);
    }

    private Outcome postMatching(Tenant as, String path, String ifMatch, String body) {
        java.net.http.HttpRequest.Builder request = java.net.http.HttpRequest.newBuilder(
                        java.net.URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + kit.token(as, everything))
                .POST(java.net.http.HttpRequest.BodyPublishers.ofString(body));
        if (ifMatch != null) {
            request.header("If-Match", ifMatch);
        }
        return kit.send(request.build());
    }

    private Outcome delete(Tenant as, String path) {
        return kit.send(java.net.http.HttpRequest.newBuilder(
                        java.net.URI.create("http://localhost:" + port + path))
                .header("Authorization", "Bearer " + kit.token(as, everything)).DELETE().build());
    }

    private Outcome putPlain(Tenant as, String path, String ifMatch, String body) {
        java.net.http.HttpRequest.Builder request = java.net.http.HttpRequest.newBuilder(
                        java.net.URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + kit.token(as, everything))
                .PUT(java.net.http.HttpRequest.BodyPublishers.ofString(body));
        if (ifMatch != null) {
            request.header("If-Match", ifMatch);
        }
        return kit.send(request.build());
    }

    private void expect(String what, Outcome outcome, int status, String code) {
        assertThat(outcome.status()).as(what + ": " + outcome.body()).isEqualTo(status);
        if (code != null) {
            assertThat(outcome.code()).as(what).isEqualTo(code);
        }
    }

    private static String newUser(String email, String password, String name, String... roles) {
        return "{\"email\":\"" + email + "\",\"password\":\"" + password + "\",\"fullName\":\"" + name
                + "\",\"roles\":[" + String.join(",", java.util.Arrays.stream(roles).map(r -> "\"" + r + "\"").toList())
                + "]}";
    }

    private String createUser(World w, String email, String password, String... roles) {
        Outcome made = post(w.caller(), "/api/v1/users", newUser(email, password, "Person", roles), everything);
        expect("create " + email, made, 201, null);
        return kit.parse(made).get("id").asString();
    }

    private Outcome login(World w, String email, String password) {
        return kit.send(java.net.http.HttpRequest.newBuilder(
                        java.net.URI.create("http://localhost:" + port + "/api/v1/auth/login"))
                .header("Content-Type", "application/json")
                .POST(java.net.http.HttpRequest.BodyPublishers.ofString("{\"tenantCode\":\"" + w.code()
                        + "\",\"email\":\"" + email + "\",\"password\":\"" + password + "\"}")).build());
    }

    private JsonNode user(World w, String id) {
        return kit.parse(kit.get(w.caller(), "/api/v1/users/" + id, everything));
    }

    private long audited(String action, String id) {
        return jdbc.sql("SELECT count(*) FROM audit_log WHERE action = :a AND resource_id = CAST(:r AS uuid)")
                .param("a", action).param("r", id).query(Long.class).single();
    }

    private JsonNode role(World w, String code) {
        for (JsonNode item : kit.parse(kit.get(w.caller(), "/api/v1/roles", everything))) {
            if (code.equals(item.get("code").asString())) {
                return item;
            }
        }
        throw new AssertionError("No role " + code);
    }

    private static String roleBody(String code, String name, String... permissions) {
        String list = String.join(",", java.util.Arrays.stream(permissions).map(p -> "\"" + p + "\"").toList());
        return (code == null ? "{" : "{\"code\":\"" + code + "\",") + "\"name\":\"" + name
                + "\",\"permissions\":[" + list + "]}";
    }

    // ------------------------------------------------------------------ roles

    @Test
    @DisplayName("a tenant starts with the five standard roles, the viewer read-only and the administrator complete")
    void standardRoles() {
        World w = world();
        List<String> codes = new java.util.ArrayList<>();
        kit.parse(kit.get(w.caller(), "/api/v1/roles", everything)).forEach(r -> codes.add(r.get("code").asString()));
        assertThat(codes).containsExactlyInAnyOrder("ADMIN", "PLANNER", "DISPATCHER", "FINANCE", "VIEWER");

        JsonNode viewer = role(w, "VIEWER");
        assertThat(viewer.get("standard").asBoolean()).isTrue();
        viewer.get("permissions").forEach(p -> assertThat(p.asString()).endsWith("_READ"));
        assertThat(viewer.get("permissions").size()).isGreaterThan(10);
        assertThat(role(w, "PLANNER").get("permissions").toString()).contains("ORDER_CREATE", "MILKRUN_PLAN");
        assertThat(role(w, "DISPATCHER").get("permissions").toString()).contains("TRIP_EXECUTE", "GATE_PASS_MANAGE");
        assertThat(role(w, "FINANCE").get("permissions").toString()).contains("INVOICE_APPROVE");
        assertThat(role(w, "ADMIN").get("permissions").size()).isEqualTo(everything.length);
        assertThat(role(w, "ADMIN").get("userCount").asLong()).isEqualTo(1);
    }

    @Test
    @DisplayName("roles are created, edited under If-Match, and deleted only while nobody holds them")
    void roleLifecycle() {
        World w = world();
        Outcome made = post(w.caller(), "/api/v1/roles", roleBody("yard_lead", "Yard lead", "GATE_REGISTER"), everything);
        expect("create", made, 201, null);
        String id = kit.parse(made).get("id").asString();
        assertThat(role(w, "YARD_LEAD").get("permissions").toString()).contains("GATE_REGISTER");

        expect("duplicate code", post(w.caller(), "/api/v1/roles", roleBody("YARD_LEAD", "Again"), everything),
                409, "role-code-taken");
        expect("unknown permission", post(w.caller(), "/api/v1/roles", roleBody("OTHER", "Other", "NOT_A_PERMISSION"),
                everything), 409, "unknown-permission");
        expect("bad code", post(w.caller(), "/api/v1/roles", roleBody("x y", "Bad"), everything), 400, null);

        String path = "/api/v1/roles/" + id;
        expect("no If-Match", putPlain(w.caller(), path, null, roleBody(null, "Yard lead", "GATE_REGISTER")), 400, null);
        String stale = "\"" + (kit.parse(kit.get(w.caller(), path, everything)).get("version").asLong() + 7) + "\"";
        expect("stale", putPlain(w.caller(), path, stale, roleBody(null, "Yard lead", "GATE_REGISTER")),
                409, "concurrent-modification");
        String etag = kit.etagOf(w.caller(), path, everything);
        expect("edit", putPlain(w.caller(), path, etag, roleBody(null, "Yard supervisor", "GATE_REGISTER", "TRIP_READ")),
                204, null);
        JsonNode edited = kit.parse(kit.get(w.caller(), path, everything));
        assertThat(edited.get("name").asString()).isEqualTo("Yard supervisor");
        assertThat(edited.get("permissions").size()).isEqualTo(2);
        expect("the old version is now stale",
                putPlain(w.caller(), path, etag, roleBody(null, "Late", "TRIP_READ")), 409, "concurrent-modification");

        String holder = createUser(w, "holder@" + w.code() + ".test", secret(), "YARD_LEAD");
        expect("in use", delete(w.caller(), path), 409, "role-in-use");
        expect("take it away", putPlain(w.caller(), "/api/v1/users/" + holder,
                kit.etagOf(w.caller(), "/api/v1/users/" + holder, everything),
                "{\"fullName\":\"Person\",\"roles\":[]}"), 204, null);
        expect("delete", delete(w.caller(), path), 204, null);
        expect("gone", kit.get(w.caller(), path, everything), 404, null);
        assertThat(audited("ROLE_CREATED", id)).isEqualTo(1);
        assertThat(audited("ROLE_UPDATED", id)).isEqualTo(1);
        assertThat(audited("ROLE_DELETED", id)).isEqualTo(1);
    }

    @Test
    @DisplayName("the Administrator role can be neither edited nor deleted")
    void administratorRoleIsFixed() {
        World w = world();
        String id = role(w, "ADMIN").get("id").asString();
        String path = "/api/v1/roles/" + id;
        expect("edit", putPlain(w.caller(), path, kit.etagOf(w.caller(), path, everything),
                roleBody(null, "Administrator", "TRIP_READ")), 409, "administrator-role-fixed");
        expect("delete", delete(w.caller(), path), 409, "administrator-role-fixed");
    }

    // ------------------------------------------------------------------ users

    @Test
    @DisplayName("the administrator types a login id and password, and the user can sign in with them")
    void createdUserSignsIn() {
        World w = world();
        String email = "new.user@" + w.code() + ".test";
        String password = secret();
        String id = createUser(w, email, password, "PLANNER");

        JsonNode created = user(w, id);
        assertThat(created.get("email").asString()).isEqualTo(email);
        assertThat(created.get("status").asString()).isEqualTo("ACTIVE");
        assertThat(created.get("roles").toString()).isEqualTo("[\"PLANNER\"]");
        assertThat(jdbc.sql("SELECT password_hash FROM app_user WHERE id = CAST(:i AS uuid)").param("i", id)
                .query(String.class).single()).startsWith("$2").doesNotContain(password);
        expect("sign in", login(w, email, password), 200, null);
        expect("wrong password", login(w, email, secret()), 401, null);
        assertThat(audited("USER_CREATED", id)).isEqualTo(1);
        assertThat(jdbc.sql("SELECT after_state::text FROM audit_log WHERE action = 'USER_CREATED' AND resource_id = CAST(:i AS uuid)")
                .param("i", id).query(String.class).single()).doesNotContain(password);
    }

    @Test
    @DisplayName("a create is refused for a weak password, a taken login id and an unknown role, and without USER_MANAGE")
    void createRefusals() {
        World w = world();
        String email = "p@" + w.code() + ".test";
        expect("short", post(w.caller(), "/api/v1/users", newUser(email, "Ab1", "P"), everything), 409, "password-policy");
        expect("no digit", post(w.caller(), "/api/v1/users", newUser(email, "OnlyLettersHereAbc", "P"), everything),
                409, "password-policy");
        expect("contains login id", post(w.caller(), "/api/v1/users",
                newUser("alexander@" + w.code() + ".test", "Alexander12345", "P"), everything), 409, "password-policy");
        expect("unknown role", post(w.caller(), "/api/v1/users", newUser(email, secret(), "P", "NOPE"), everything),
                409, "unknown-role");
        createUser(w, email, secret());
        expect("taken", post(w.caller(), "/api/v1/users", newUser(email.toUpperCase(), secret(), "P"), everything),
                409, "login-id-taken");
        expect("not an email", post(w.caller(), "/api/v1/users", newUser("plainname", secret(), "P"), everything),
                400, null);
        expect("no right", post(w.caller(), "/api/v1/users", newUser("q@" + w.code() + ".test", secret(), "P"),
                "USER_READ"), 403, null);
    }

    @Test
    @DisplayName("users are listed and searched by name or login id and by status")
    void listAndSearch() {
        World w = world();
        String id = createUser(w, "searchable.person@" + w.code() + ".test", secret(), "VIEWER");
        createUser(w, "other@" + w.code() + ".test", secret());
        JsonNode hit = kit.parse(kit.get(w.caller(), "/api/v1/users?q=SEARCHABLE", everything));
        assertThat(hit.size()).isEqualTo(1);
        assertThat(hit.get(0).get("id").asString()).isEqualTo(id);
        assertThat(hit.get(0).get("roles").toString()).isEqualTo("[\"VIEWER\"]");
        assertThat(kit.parse(kit.get(w.caller(), "/api/v1/users?status=SUSPENDED", everything)).size()).isZero();
        expect("bad status", kit.get(w.caller(), "/api/v1/users?status=GONE", everything), 400, null);
    }

    @Test
    @DisplayName("an edit of name and roles needs the version it was read at")
    void editUnderIfMatch() {
        World w = world();
        String id = createUser(w, "edit@" + w.code() + ".test", secret(), "VIEWER");
        String path = "/api/v1/users/" + id;
        String body = "{\"fullName\":\"Renamed\",\"roles\":[\"FINANCE\",\"VIEWER\"]}";
        expect("no If-Match", putPlain(w.caller(), path, null, body), 400, null);
        String stale = "\"" + (user(w, id).get("version").asLong() + 7) + "\"";
        expect("stale", putPlain(w.caller(), path, stale, body), 409, "concurrent-modification");
        expect("edit", putPlain(w.caller(), path, kit.etagOf(w.caller(), path, everything), body), 204, null);
        JsonNode edited = user(w, id);
        assertThat(edited.get("fullName").asString()).isEqualTo("Renamed");
        assertThat(edited.get("roles").toString()).isEqualTo("[\"FINANCE\",\"VIEWER\"]");
        assertThat(audited("USER_UPDATED", id)).isEqualTo(1);
    }

    @Test
    @DisplayName("a user is deactivated and reactivated, never deleted, and cannot sign in while off")
    void deactivateAndReactivate() {
        World w = world();
        String email = "off@" + w.code() + ".test";
        String password = secret();
        String id = createUser(w, email, password, "VIEWER");
        String path = "/api/v1/users/" + id;
        String reason = "{\"reason\":\"left the company\"}";

        expect("deactivate", postMatching(w.caller(), path + "/deactivate", null, reason), 204, null);
        assertThat(user(w, id).get("status").asString()).isEqualTo("SUSPENDED");
        expect("cannot sign in", login(w, email, password), 401, null);
        expect("twice", postMatching(w.caller(), path + "/deactivate", null, reason), 409, "user-already-inactive");
        expect("listed as suspended", kit.get(w.caller(), "/api/v1/users?status=SUSPENDED", everything), 200, null);
        expect("reactivate", postMatching(w.caller(), path + "/reactivate", null, reason), 204, null);
        expect("active again", login(w, email, password), 200, null);
        expect("not inactive", postMatching(w.caller(), path + "/reactivate", null, reason), 409, "user-not-inactive");
        expect("stale", postMatching(w.caller(), path + "/deactivate", "\"99\"", reason), 409, "concurrent-modification");
        assertThat(audited("USER_DEACTIVATED", id)).isEqualTo(1);
        assertThat(audited("USER_REACTIVATED", id)).isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM app_user WHERE id = CAST(:i AS uuid)").param("i", id)
                .query(Long.class).single()).isEqualTo(1);
    }

    @Test
    @DisplayName("an administrator cannot deactivate themselves")
    void selfDeactivation() {
        World w = world();
        createUser(w, "second.admin@" + w.code() + ".test", secret(), "ADMIN");
        expect("self", postMatching(w.caller(), "/api/v1/users/" + w.adminId() + "/deactivate", null,
                "{\"reason\":\"oops\"}"), 409, "self-deactivation");
    }

    @Test
    @DisplayName("the last active administrator can be neither deactivated nor stripped of the role")
    void lastAdministrator() {
        World w = world();
        String path = "/api/v1/users/" + w.adminId();
        expect("deactivate", postMatching(w.ghost(), path + "/deactivate", null, "{\"reason\":\"x\"}"),
                409, "last-administrator");
        expect("remove role", putPlain(w.ghost(), path, kit.etagOf(w.ghost(), path, everything),
                "{\"fullName\":\"Admin\",\"roles\":[\"VIEWER\"]}"), 409, "last-administrator");

        String second = createUser(w, "second@" + w.code() + ".test", secret(), "ADMIN");
        expect("now a second exists", putPlain(w.ghost(), path, kit.etagOf(w.ghost(), path, everything),
                "{\"fullName\":\"Admin\",\"roles\":[\"VIEWER\"]}"), 204, null);
        expect("but the second is last", postMatching(w.ghost(), "/api/v1/users/" + second + "/deactivate", null,
                "{\"reason\":\"x\"}"), 409, "last-administrator");
    }

    @Test
    @DisplayName("an administrator sets a new password, and the old one stops working")
    void setPassword() {
        World w = world();
        String email = "pw@" + w.code() + ".test";
        String old = secret();
        String id = createUser(w, email, old, "VIEWER");
        String path = "/api/v1/users/" + id + "/password";
        String fresh = secret();
        expect("weak", putPlain(w.caller(), path, null, "{\"password\":\"short1\"}"), 409, "password-policy");
        expect("set", putPlain(w.caller(), path, null, "{\"password\":\"" + fresh + "\"}"), 204, null);
        expect("new works", login(w, email, fresh), 200, null);
        expect("old does not", login(w, email, old), 401, null);
        assertThat(audited("USER_PASSWORD_SET", id)).isEqualTo(1);
        assertThat(jdbc.sql("SELECT string_agg(after_state::text || before_state::text, '') FROM audit_log"
                + " WHERE action = 'USER_PASSWORD_SET' AND resource_id = CAST(:i AS uuid)").param("i", id)
                .query(String.class).single()).doesNotContain(fresh).doesNotContain(old).doesNotContain("$2");
    }
}
