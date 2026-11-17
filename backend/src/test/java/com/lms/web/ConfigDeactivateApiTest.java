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
 * Switching allocation rules, organisational units, custom fields and webhook
 * subscriptions off and on over HTTP, and the stale-edit refusal of the edits
 * that now take {@code If-Match} (issue 247).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ConfigDeactivateApiTest {

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

    private static final String TARGET = "https://203.0.113.10/hooks/lms";

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
    private String[] everything;
    private Tenant caller;
    private Seeded seeded;
    private String salt;

    @BeforeAll
    void aTenant() {
        kit = new ApiTestKit(fixtures, tokens, port);
        everything = seeds.allPermissions().toArray(String[]::new);
        seeded = seeds.seed(2.0);
        caller = kit.tenantOf(seeded.tenantId(), seeded.orgUnitId(), seeded.orgPath());
        salt = UUID.randomUUID().toString().substring(0, 6);
    }

    // ---------------------------------------------------------------- helpers

    private static String reason(String text) {
        return "{\"reason\":\"" + text + "\"}";
    }

    private Outcome post(String path, String body, String ifMatch) {
        java.net.http.HttpRequest.Builder request = java.net.http.HttpRequest.newBuilder(
                        java.net.URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + kit.token(caller, everything))
                .POST(java.net.http.HttpRequest.BodyPublishers.ofString(body));
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

    private String created(Outcome outcome) {
        assertThat(outcome.status()).as(outcome.body()).isEqualTo(201);
        return kit.parse(outcome).get("id").asString();
    }

    private boolean listed(String path, String id) {
        for (JsonNode item : kit.parse(kit.get(caller, path, everything)).get("items")) {
            if (id.equals(item.get("id").asString())) {
                return true;
            }
        }
        return false;
    }

    private long audited(String action, String id) {
        return jdbc.sql("SELECT count(*) FROM audit_log WHERE action = :a AND resource_id = CAST(:r AS uuid)"
                + " AND after_state ->> 'reason' IS NOT NULL").param("a", action).param("r", id)
                .query(Long.class).single();
    }

    /** The version a stale edit is made from: one behind the record's own. */
    private String behind(String path) {
        return "\"" + (kit.parse(kit.get(caller, path, everything)).get("version").asLong() + 7) + "\"";
    }

    // ------------------------------------------------------- allocation rules

    @Test
    @DisplayName("an allocation rule is switched off with a reason, leaves the default list, and comes back")
    void allocationRule() {
        UUID vendor = jdbc.sql("SELECT id FROM business_partner WHERE tenant_id = :t AND partner_type = 'VENDOR'")
                .param("t", seeded.tenantId()).query(UUID.class).list().get(0);
        String id = created(kit.post(caller, "/api/v1/allocation-rules", "{\"name\":\"Rule " + salt
                + "\",\"priority\":5,\"action\":\"PREFER\",\"vendorPartnerIds\":[\"" + vendor + "\"]}", everything));
        String path = "/api/v1/allocation-rules/" + id;

        expect("no reason", post(path + "/deactivate", "{}", null), 400, null);
        expect("a stale version", post(path + "/deactivate", reason("Seasonal"), behind(path)), 409,
                "concurrent-modification");
        expect("off", post(path + "/deactivate", reason("Seasonal"), kit.etagOf(caller, path, everything)), 204, null);
        expect("off twice", post(path + "/deactivate", reason("Again"), null), 409, "allocation-rule-already-inactive");
        assertThat(listed("/api/v1/allocation-rules", id)).as("the default list is the active rules").isFalse();
        assertThat(listed("/api/v1/allocation-rules?status=INACTIVE", id)).isTrue();
        assertThat(listed("/api/v1/allocation-rules?status=ALL", id)).isTrue();
        assertThat(listed("/api/v1/allocation-rules?active=false", id)).as("the older flag still answers").isTrue();
        assertThat(kit.parse(kit.get(caller, path, everything)).get("active").asBoolean()).isFalse();
        assertThat(audited("ALLOCATION_RULE_DEACTIVATED", id)).isEqualTo(1);

        expect("on", post(path + "/reactivate", reason("Peak season"), null), 204, null);
        expect("on twice", post(path + "/reactivate", reason("Again"), null), 409, "allocation-rule-not-inactive");
        assertThat(listed("/api/v1/allocation-rules", id)).isTrue();
        assertThat(audited("ALLOCATION_RULE_REACTIVATED", id)).isEqualTo(1);

        String body = "{\"name\":\"Rule " + salt + "\",\"priority\":6,\"action\":\"PREFER\",\"vendorPartnerIds\":[\""
                + vendor + "\"]}";
        expect("an edit from a stale version", kit.putMatching(caller, path, behind(path), body, everything), 409,
                "concurrent-modification");
        expect("an edit without a version", kit.put(caller, path, body, everything), 400, null);
        expect("an edit from the version read", kit.putMatching(caller, path, kit.etagOf(caller, path, everything), body,
                everything), 204, null);
    }

    // ------------------------------------------------------------- org units

    @Test
    @DisplayName("an organisational unit with active units or users beneath it is in use; one switched off takes no children")
    void orgUnit() {
        String branch = created(kit.post(caller, "/api/v1/org-units", "{\"parentId\":\"" + seeded.orgUnitId()
                + "\",\"slug\":\"br-" + salt + "\",\"name\":\"Branch " + salt + "\",\"unitType\":\"BRANCH\"}",
                everything));
        String hub = created(kit.post(caller, "/api/v1/org-units", "{\"parentId\":\"" + branch + "\",\"slug\":\"hub-"
                + salt + "\",\"name\":\"Hub " + salt + "\",\"unitType\":\"HUB\"}", everything));
        String branchPath = "/api/v1/org-units/" + branch;
        String hubPath = "/api/v1/org-units/" + hub;

        expect("the top unit", post("/api/v1/org-units/" + seeded.orgUnitId() + "/deactivate", reason("Closing"), null),
                409, "org-unit-root");
        expect("an active unit beneath", post(branchPath + "/deactivate", reason("Closing"), null), 409,
                "org-unit-in-use");
        UUID user = fixtures.createUser(seeded.tenantId(), UUID.fromString(hub), seeded.orgPath() + "br-" + salt
                + "/hub-" + salt + "/", "hub-" + salt + "@acme.test", "not-used-here");
        expect("an active user in it", post(hubPath + "/deactivate", reason("Closing"), null), 409, "org-unit-in-use");
        jdbc.sql("UPDATE app_user SET status = 'SUSPENDED' WHERE id = :id").param("id", user).update();

        expect("no reason", post(hubPath + "/deactivate", "{}", null), 400, null);
        expect("the hub off", post(hubPath + "/deactivate", reason("Hub closed"), kit.etagOf(caller, hubPath, everything)),
                204, null);
        expect("off twice", post(hubPath + "/deactivate", reason("Again"), null), 409, "org-unit-already-inactive");
        expect("the branch off", post(branchPath + "/deactivate", reason("Branch closed"), null), 204, null);
        expect("nothing beneath an inactive unit", kit.post(caller, "/api/v1/org-units", "{\"parentId\":\"" + branch
                + "\",\"slug\":\"x-" + salt + "\",\"name\":\"X\",\"unitType\":\"HUB\"}", everything), 409,
                "org-unit-inactive");
        assertThat(listed("/api/v1/org-units?limit=200", branch)).as("the default list is the active units").isFalse();
        assertThat(listed("/api/v1/org-units?limit=200&status=INACTIVE", branch)).isTrue();
        assertThat(listed("/api/v1/org-units?limit=200&status=ALL", hub)).isTrue();
        assertThat(kit.parse(kit.get(caller, branchPath, everything)).get("active").asBoolean()).isFalse();
        assertThat(audited("ORG_UNIT_DEACTIVATED", branch)).isEqualTo(1);

        expect("the hub before its branch", post(hubPath + "/reactivate", reason("Reopen"), null), 409,
                "org-unit-parent-inactive");
        expect("the branch on", post(branchPath + "/reactivate", reason("Reopened"), null), 204, null);
        expect("the hub on", post(hubPath + "/reactivate", reason("Reopened"), null), 204, null);
        expect("on twice", post(hubPath + "/reactivate", reason("Again"), null), 409, "org-unit-not-inactive");
        assertThat(audited("ORG_UNIT_REACTIVATED", hub)).isEqualTo(1);
    }

    // ---------------------------------------------------------- custom fields

    @Test
    @DisplayName("a custom field is switched off with a reason and comes back; a stale edit is refused")
    void customField() {
        String id = created(kit.post(caller, "/api/v1/custom-fields/definitions", "{\"entityType\":\"PARTNER\","
                + "\"key\":\"note_" + salt + "\",\"label\":\"Note\",\"fieldType\":\"TEXT\"}", everything));
        String path = "/api/v1/custom-fields/definitions/" + id;

        expect("no reason", post(path + "/deactivate", "{}", null), 400, null);
        expect("off", post(path + "/deactivate", reason("Not used"), kit.etagOf(caller, path, everything)), 204, null);
        expect("off twice", post(path + "/deactivate", reason("Again"), null), 409, "custom-field-already-inactive");
        assertThat(listed("/api/v1/custom-fields/definitions", id)).as("the default list is the active fields").isFalse();
        assertThat(listed("/api/v1/custom-fields/definitions?status=INACTIVE", id)).isTrue();
        assertThat(listed("/api/v1/custom-fields/definitions?status=ALL", id)).isTrue();
        assertThat(audited("CUSTOM_FIELD_DEACTIVATED", id)).isEqualTo(1);
        expect("on", post(path + "/reactivate", reason("Needed"), null), 204, null);
        expect("on twice", post(path + "/reactivate", reason("Again"), null), 409, "custom-field-not-inactive");
        assertThat(listed("/api/v1/custom-fields/definitions", id)).isTrue();

        String body = "{\"label\":\"Note v2\",\"required\":false}";
        expect("a stale edit", kit.putMatching(caller, path, behind(path), body, everything), 409,
                "concurrent-modification");
        expect("no version", kit.put(caller, path, body, everything), 400, null);
        expect("the version read", kit.putMatching(caller, path, kit.etagOf(caller, path, everything), body, everything),
                204, null);
    }

    // --------------------------------------------------------------- webhooks

    @Test
    @DisplayName("a webhook subscription is switched off with a reason, queues and sends nothing, and comes back")
    void webhook() {
        String id = created(kit.post(caller, "/api/v1/webhooks/subscriptions", "{\"name\":\"hook-" + salt
                + "\",\"url\":\"" + TARGET + "\",\"eventTypes\":[\"TRIP_COMPLETED\"]}", everything));
        String path = "/api/v1/webhooks/subscriptions/" + id;

        expect("no reason", post(path + "/deactivate", "{}", null), 400, null);
        expect("a stale version", post(path + "/deactivate", reason("Paused"), behind(path)), 409,
                "concurrent-modification");
        expect("off", post(path + "/deactivate", reason("Receiver down"), kit.etagOf(caller, path, everything)), 204,
                null);
        expect("off twice", post(path + "/deactivate", reason("Again"), null), 409, "webhook-already-inactive");
        expect("a ping goes nowhere", post(path + "/pings", "{}", null), 409, "webhook-subscription-inactive");
        assertThat(jdbc.sql("SELECT count(*) FROM webhook_subscription WHERE id = CAST(:id AS uuid) AND active")
                .param("id", id).query(Long.class).single()).isZero();
        assertThat(listed("/api/v1/webhooks/subscriptions", id)).as("the default list is the active ones").isFalse();
        assertThat(listed("/api/v1/webhooks/subscriptions?status=INACTIVE", id)).isTrue();
        assertThat(audited("WEBHOOK_DEACTIVATED", id)).isEqualTo(1);

        expect("on", post(path + "/reactivate", reason("Receiver back"), null), 204, null);
        expect("on twice", post(path + "/reactivate", reason("Again"), null), 409, "webhook-not-inactive");
        expect("a ping goes out", post(path + "/pings", "{}", null), 202, null);
        assertThat(listed("/api/v1/webhooks/subscriptions", id)).isTrue();

        String body = "{\"name\":\"hook-" + salt + "\",\"url\":\"" + TARGET + "\",\"eventTypes\":[\"TRIP_COMPLETED\"],"
                + "\"active\":true}";
        expect("a stale edit", kit.putMatching(caller, path, behind(path), body, everything), 409,
                "concurrent-modification");
        expect("no version", kit.put(caller, path, body, everything), 400, null);
        expect("the version read", kit.putMatching(caller, path, kit.etagOf(caller, path, everything), body, everything),
                204, null);
    }

    // ------------------------------------------------- access policy, series

    @Test
    @DisplayName("an access policy and a number series are edited from the version that was read, and a stale edit is a 409")
    void staleEdits() {
        String policy = created(kit.post(caller, "/api/v1/access-policies", "{\"code\":\"p-" + salt
                + "\",\"action\":\"INVOICE_APPROVE\",\"effect\":\"DENY\",\"description\":\"No Sunday approvals\","
                + "\"condition\":{\"attr\":\"env.dayOfWeek\",\"op\":\"eq\",\"value\":\"SUNDAY\"}}", everything));
        String policyPath = "/api/v1/access-policies/" + policy;
        String revision = "{\"effect\":\"DENY\",\"description\":\"No Sunday approvals, revised\",\"condition\":"
                + "{\"attr\":\"env.dayOfWeek\",\"op\":\"eq\",\"value\":\"SUNDAY\"},\"enabled\":true}";
        expect("a stale policy edit", kit.putMatching(caller, policyPath, behind(policyPath), revision, everything), 409,
                "concurrent-modification");
        expect("no version", kit.put(caller, policyPath, revision, everything), 400, null);
        expect("the policy as read", kit.putMatching(caller, policyPath, kit.etagOf(caller, policyPath, everything),
                revision, everything), 204, null);
        assertThat(kit.parse(kit.get(caller, policyPath, everything)).get("description").asString())
                .isEqualTo("No Sunday approvals, revised");

        String series = created(kit.post(caller, "/api/v1/number-series", "{\"code\":\"S-" + salt
                + "\",\"documentType\":\"TRIP\",\"prefix\":\"TR" + salt.substring(0, 3).toUpperCase()
                + "\",\"yearCode\":\"NONE\"}", everything));
        String seriesPath = "/api/v1/number-series/" + series;
        String edit = "{\"prefix\":\"TR" + salt.substring(0, 3).toUpperCase() + "\",\"yearCode\":\"NONE\","
                + "\"reuseReleased\":true,\"description\":\"Reuse\"}";
        expect("a stale series edit", kit.putMatching(caller, seriesPath, behind(seriesPath), edit, everything), 409,
                "concurrent-modification");
        expect("no version", kit.put(caller, seriesPath, edit, everything), 400, null);
        expect("the series as read", kit.putMatching(caller, seriesPath, kit.etagOf(caller, seriesPath, everything),
                edit, everything), 204, null);
        assertThat(kit.parse(kit.get(caller, seriesPath, everything)).get("reuseReleased").asBoolean()).isTrue();
    }
}
