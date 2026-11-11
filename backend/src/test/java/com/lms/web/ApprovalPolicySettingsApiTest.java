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
 * Approval policies as profile settings, over HTTP: a unit's, a customer's and
 * a combination's profile asking for approval where the organisation default
 * does not, the mandatory proof reversal that no profile can switch off, the
 * legacy policy endpoints now reading and writing the organisation default,
 * and the snapshot a request keeps through a later profile change.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ApprovalPolicySettingsApiTest {

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

    private static final String PROFILES = "/api/v1/settings/profiles";
    private static final String ORDER_KEY = "approvals.policy.orderCancellation";
    private static final String POD_KEY = "approvals.policy.podReversal";
    private static final String MANAGE = "SETTINGS_MANAGE";
    private static final String NAME_PREFIX = "Approvals test ";

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
    private Seeded a;
    private Tenant requester;
    private Tenant approver;
    private UUID customer;
    private UUID origin;

    @BeforeAll
    void aTenant() {
        kit = new ApiTestKit(fixtures, tokens, port);
        everything = seeds.allPermissions().toArray(String[]::new);
        a = seeds.seed(0.0);
        requester = kit.tenantOf(a.tenantId(), a.orgUnitId(), a.orgPath());
        approver = kit.tenantOf(a.tenantId(), a.orgUnitId(), a.orgPath());
        customer = jdbc.sql("SELECT customer_partner_id FROM sales_order WHERE id = :id")
                .param("id", a.id("sales_order")).query(UUID.class).single();
        origin = jdbc.sql("SELECT origin_terminal_id FROM sales_order WHERE id = :id")
                .param("id", a.id("sales_order")).query(UUID.class).single();
    }

    @BeforeEach
    void noPolicySettings() {
        jdbc.sql("DELETE FROM setting_profile WHERE tenant_id = :t AND name LIKE :n")
                .param("t", a.tenantId()).param("n", NAME_PREFIX + "%").update();
        jdbc.sql("DELETE FROM setting_value WHERE tenant_id = :t AND setting_key LIKE 'approvals.policy.%'")
                .param("t", a.tenantId()).update();
    }

    // ------------------------------------------------------------- helpers

    private UUID draftOrder() {
        Outcome raised = kit.post(requester, "/api/v1/orders", """
                {"orgUnitId":"%s","customerPartnerId":"%s","originTerminalId":"%s"}
                """.formatted(a.orgUnitId(), customer, origin), everything);
        assertThat(raised.status()).as(raised.body()).isEqualTo(201);
        return UUID.fromString(kit.parse(raised).get("id").asString());
    }

    private Outcome cancel(UUID order) {
        return kit.post(requester, "/api/v1/orders/" + order + "/cancel", "{}", everything);
    }

    private void assertCancelsAtOnce(String why) {
        assertThat(cancel(draftOrder()).status()).as(why).isEqualTo(204);
    }

    private UUID assertAsksForApproval(String why) {
        Outcome asked = cancel(draftOrder());
        assertThat(asked.status()).as(why + ": " + asked.body()).isEqualTo(202);
        return UUID.fromString(kit.parse(asked).get("id").asString());
    }

    private Outcome createProfile(String scopeType, String scopeOrConditions, String name, String values) {
        return kit.post(requester, PROFILES, """
                {"scopeType":"%s",%s"name":"%s","values":%s}
                """.formatted(scopeType, scopeOrConditions, NAME_PREFIX + name, values), MANAGE);
    }

    private UUID profile(Outcome created) {
        assertThat(created.status()).as(created.body()).isEqualTo(201);
        return UUID.fromString(kit.parse(created).get("id").asString());
    }

    private Outcome patchProfile(UUID id, String values) {
        Outcome read = kit.get(requester, PROFILES + "/" + id, "SETTINGS_READ");
        String etag = "\"" + kit.parse(read).get("version").asLong() + "\"";
        return kit.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + PROFILES + "/" + id))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + kit.token(requester, MANAGE))
                .header("If-Match", etag)
                .method("PATCH", HttpRequest.BodyPublishers.ofString("{\"values\":" + values + "}")).build());
    }

    private JsonNode detail(UUID id) {
        Outcome got = kit.get(approver, "/api/v1/approvals/" + id, everything);
        assertThat(got.status()).as(got.body()).isEqualTo(200);
        return kit.parse(got).get("approval");
    }

    private JsonNode policyOf(String subjectType) {
        for (JsonNode policy : kit.parse(kit.get(requester, "/api/v1/approvals/policies", everything))) {
            if (subjectType.equals(policy.get("subjectType").asString())) {
                return policy;
            }
        }
        throw new AssertionError("No policy for " + subjectType);
    }

    private static String unit(UUID id) {
        return "\"scopeId\":\"" + id + "\",";
    }

    // --------------------------------------------------------------- tests

    @Test
    @DisplayName("a unit's profile asks for approval where the organisation default does not")
    void unitProfile() {
        assertCancelsAtOnce("no profile");
        UUID id = profile(createProfile("ORG_UNIT", unit(a.orgUnitId()), "unit",
                "{\"" + ORDER_KEY + "\":{\"required\":true}}"));
        assertAsksForApproval("the unit's profile requires it");
        // Back to not required, by the profile.
        assertThat(patchProfile(id, "{\"" + ORDER_KEY + "\":{\"required\":false}}").status()).isEqualTo(200);
        assertCancelsAtOnce("the unit's profile says no");
    }

    @Test
    @DisplayName("a unit's profile overrides an organisation default that requires approval")
    void unitProfileBeatsTheDefault() {
        assertThat(kit.put(requester, "/api/v1/approvals/policies/ORDER_CANCELLATION", "{\"required\":true}",
                "APPROVAL_POLICY_MANAGE").status()).isEqualTo(200);
        assertAsksForApproval("the organisation default requires it");
        profile(createProfile("ORG_UNIT", unit(a.orgUnitId()), "unit",
                "{\"" + ORDER_KEY + "\":{\"required\":false}}"));
        assertCancelsAtOnce("the unit says no");
    }

    @Test
    @DisplayName("a customer's profile applies to that customer's orders")
    void customerProfile() {
        assertCancelsAtOnce("no profile");
        profile(createProfile("CUSTOMER", unit(customer), "customer",
                "{\"" + ORDER_KEY + "\":{\"required\":true,\"steps\":\"ORDER_APPROVE\",\"dueAfter\":\"PT24H\"}}"));
        UUID ticket = assertAsksForApproval("the customer's profile requires it");
        JsonNode raised = detail(ticket);
        assertThat(raised.get("approvalsRequired").asInt()).isEqualTo(1);
        assertThat(raised.get("dueAt").isNull()).isFalse();
    }

    @Test
    @DisplayName("a combination naming the order's freight mode applies; one naming another mode does not")
    void combinationProfile() {
        profile(createProfile("COMBINATION", "\"conditions\":{\"orgUnitId\":\"" + a.orgUnitId()
                + "\",\"freightMode\":\"LTL\"},", "ltl", "{\"" + ORDER_KEY + "\":{\"required\":true}}"));
        assertCancelsAtOnce("the order moves FTL, the combination names LTL");
        profile(createProfile("COMBINATION", "\"conditions\":{\"orgUnitId\":\"" + a.orgUnitId()
                + "\",\"freightMode\":\"FTL\"},", "ftl", "{\"" + ORDER_KEY + "\":{\"required\":true}}"));
        assertAsksForApproval("the combination names the order's mode");
    }

    @Test
    @DisplayName("a profile cannot switch off the mandatory proof reversal, nor name a permission that does not exist")
    void mandatoryAndUnknownPermissions() {
        Outcome off = createProfile("ORG_UNIT", unit(a.orgUnitId()), "off",
                "{\"" + POD_KEY + "\":{\"required\":false}}");
        assertThat(off.status()).as(off.body()).isEqualTo(409);
        assertThat(off.code()).isEqualTo("setting-invalid-value");

        Outcome unknown = createProfile("ORG_UNIT", unit(a.orgUnitId()), "unknown",
                "{\"" + ORDER_KEY + "\":{\"required\":true,\"steps\":\"NOT_A_RIGHT\"}}");
        assertThat(unknown.status()).as(unknown.body()).isEqualTo(409);
        assertThat(unknown.code()).isEqualTo("setting-invalid-value");

        for (String bad : new String[] {"{\"required\":true,\"steps\":\"\"}", "{\"required\":true,\"onExpiry\":\"X\"}",
                "{\"required\":true,\"dueAfter\":\"soon\"}", "{\"steps\":\"ORDER_APPROVE\"}",
                "{\"required\":\"yes\"}", "{\"required\":true,\"approvers\":2}", "true"}) {
            Outcome refused = createProfile("ORG_UNIT", unit(a.orgUnitId()), "bad",
                    "{\"" + ORDER_KEY + "\":" + bad + "}");
            assertThat(refused.status()).as(bad + ": " + refused.body()).isEqualTo(409);
            assertThat(refused.code()).as(bad).isEqualTo("setting-invalid-value");
        }

        // The profile that does stand sets POD_REVERSAL's steps, and it is still required.
        profile(createProfile("ORG_UNIT", unit(a.orgUnitId()), "pod",
                "{\"" + POD_KEY + "\":{\"required\":true,\"dueAfter\":\"PT48H\"}}"));
        assertThat(policyOf("POD_REVERSAL").get("required").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("the legacy policy endpoints read and write the organisation default profile")
    void legacyEndpoints() {
        assertThat(policyOf("ORDER_CANCELLATION").get("required").asBoolean()).isFalse();
        Outcome set = kit.put(requester, "/api/v1/approvals/policies/ORDER_CANCELLATION",
                "{\"required\":true,\"decidePermissions\":[\"ORDER_APPROVE\"],\"dueMinutes\":120,"
                        + "\"onExpiry\":\"AUTO_APPROVE\"}", "APPROVAL_POLICY_MANAGE");
        assertThat(set.status()).as(set.body()).isEqualTo(200);
        assertThat(kit.parse(set).get("required").asBoolean()).isTrue();

        JsonNode stored = jdbc.sql("""
                        SELECT v.value::text FROM setting_value v
                          JOIN setting_profile p ON p.id = v.profile_id
                         WHERE v.tenant_id = :t AND p.scope_type = 'ORG_DEFAULT' AND v.setting_key = :k
                        """)
                .param("t", a.tenantId()).param("k", ORDER_KEY).query(String.class)
                .optional().map(kit::parseText).orElseThrow();
        assertThat(stored.get("required").asBoolean()).isTrue();
        assertThat(stored.get("steps").asString()).isEqualTo("ORDER_APPROVE");
        assertThat(stored.get("dueAfter").asString()).isEqualTo("PT2H");
        assertThat(policyOf("ORDER_CANCELLATION").get("onExpiry").asString()).isEqualTo("AUTO_APPROVE");
        assertAsksForApproval("the organisation default requires it");

        Outcome reset = kit.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port
                        + "/api/v1/approvals/policies/ORDER_CANCELLATION"))
                .header("Authorization", "Bearer " + kit.token(requester, "APPROVAL_POLICY_MANAGE"))
                .DELETE().build());
        assertThat(reset.status()).as(reset.body()).isEqualTo(200);
        assertThat(kit.parse(reset).get("required").asBoolean()).isFalse();
        assertThat(jdbc.sql("SELECT count(*) FROM setting_value WHERE tenant_id = :t AND setting_key = :k")
                .param("t", a.tenantId()).param("k", ORDER_KEY).query(Long.class).single()).isZero();
        assertCancelsAtOnce("the code default again");

        assertThat(kit.put(requester, "/api/v1/approvals/policies/POD_REVERSAL", "{\"required\":false}",
                "APPROVAL_POLICY_MANAGE").code()).isEqualTo("approval-mandatory");
    }

    @Test
    @DisplayName("an open request keeps the policy it was raised under through a profile change")
    void snapshotSurvivesAProfileChange() {
        UUID id = profile(createProfile("ORG_UNIT", unit(a.orgUnitId()), "snapshot",
                "{\"" + ORDER_KEY + "\":{\"required\":true,\"steps\":\"ORDER_APPROVE\"}}"));
        UUID first = assertAsksForApproval("one step");
        assertThat(patchProfile(id, "{\"" + ORDER_KEY + "\":{\"required\":true,"
                + "\"steps\":\"ORDER_APPROVE,ORDER_APPROVE\"}}").status()).isEqualTo(200);
        UUID later = assertAsksForApproval("two steps");
        assertThat(detail(first).get("approvalsRequired").asInt()).isEqualTo(1);
        assertThat(detail(later).get("approvalsRequired").asInt()).isEqualTo(2);
    }
}
