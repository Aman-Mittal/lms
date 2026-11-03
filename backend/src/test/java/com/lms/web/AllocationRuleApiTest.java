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
 * Allocation rules over HTTP, with real permissions: writing and reading them,
 * the validation of a rule, and that one tenant cannot see or use another's.
 *
 * <p>What a rule <em>does</em> to an offer is proved elsewhere: the ordering
 * logic by {@code AllocationRulesTest}, and the offers themselves by the
 * end-to-end scenarios, which build a real load.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AllocationRuleApiTest {

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
    private Tenant callerA;
    private Tenant callerB;
    private Seeded a;
    private Seeded b;
    private UUID vendorA;
    private UUID vendorB;
    private UUID terminalA;

    @BeforeAll
    void twoTenants() {
        kit = new ApiTestKit(fixtures, tokens, port);
        everything = seeds.allPermissions().toArray(String[]::new);
        a = seeds.seed(0.0);
        b = seeds.seed(1.0);
        callerA = kit.tenantOf(a.tenantId(), a.orgUnitId(), a.orgPath());
        callerB = kit.tenantOf(b.tenantId(), b.orgUnitId(), b.orgPath());
        vendorA = vendorOf(a);
        vendorB = vendorOf(b);
        terminalA = jdbc.sql("SELECT id FROM terminal WHERE tenant_id = :t LIMIT 1")
                .param("t", a.tenantId()).query(UUID.class).single();
    }

    @BeforeEach
    void noRules() {
        jdbc.sql("DELETE FROM allocation_rule").update();
    }

    private UUID vendorOf(Seeded seeded) {
        return jdbc.sql("SELECT id FROM business_partner WHERE tenant_id = :t AND partner_type = 'VENDOR'")
                .param("t", seeded.tenantId()).query(UUID.class).list().get(0);
    }

    // ------------------------------------------------------------- helpers

    private static String rule(String name, int priority, String extra, String action, UUID... vendors) {
        StringBuilder ids = new StringBuilder();
        for (UUID v : vendors) {
            ids.append(ids.isEmpty() ? "" : ",").append('"').append(v).append('"');
        }
        return "{\"name\":\"" + name + "\",\"priority\":" + priority + extra
                + ",\"action\":\"" + action + "\",\"vendorPartnerIds\":[" + ids + "]}";
    }

    private UUID create(String body) {
        Outcome created = kit.post(callerA, "/api/v1/allocation-rules", body, everything);
        assertThat(created.status()).as(created.body()).isEqualTo(201);
        return UUID.fromString(kit.parse(created).get("id").asString());
    }

    private void expect(String what, Outcome outcome, int status, String code) {
        assertThat(outcome.status()).as(what + ": " + outcome.body()).isEqualTo(status);
        if (code != null) {
            assertThat(outcome.code()).as(what).isEqualTo(code);
        }
    }

    // --------------------------------------------------------------- rules

    @Test
    @DisplayName("a rule is written, read back with its vendors named, and listed in the order it is tried")
    void writeReadList() {
        UUID late = create(rule("Late", 20, "", "EXCLUDE", vendorA));
        UUID first = create(rule("Heavy loads", 5,
                ",\"minWeightKg\":1000,\"maxWeightKg\":9000.5,\"materialClass\":\"FOOD\","
                        + "\"originTerminalId\":\"" + terminalA + "\"", "PREFER", vendorA));

        JsonNode got = kit.parse(kit.get(callerA, "/api/v1/allocation-rules/" + first, everything));
        assertThat(got.get("name").asString()).isEqualTo("Heavy loads");
        assertThat(got.get("action").asString()).isEqualTo("PREFER");
        assertThat(got.get("active").asBoolean()).isTrue();
        assertThat(got.get("minWeightKg").asDouble()).isEqualTo(1000.0);
        assertThat(got.get("maxWeightKg").asDouble()).isEqualTo(9000.5);
        assertThat(got.get("materialClass").asString()).isEqualTo("FOOD");
        assertThat(got.get("originTerminalId").asString()).isEqualTo(terminalA.toString());
        assertThat(got.get("vendors")).hasSize(1);
        assertThat(got.get("vendors").get(0).get("vendorPartnerId").asString()).isEqualTo(vendorA.toString());
        assertThat(got.get("vendors").get(0).get("vendorName").asString()).isNotBlank();

        JsonNode page = kit.parse(kit.get(callerA, "/api/v1/allocation-rules", everything));
        assertThat(page.get("items")).hasSize(2);
        assertThat(page.get("items").get(0).get("id").asString()).isEqualTo(first.toString());
        assertThat(page.get("items").get(1).get("id").asString()).isEqualTo(late.toString());
    }

    @Test
    @DisplayName("rules are paged in the order they are tried, equal priorities included")
    void paging() {
        create(rule("One", 10, "", "PREFER", vendorA));
        create(rule("Two", 10, "", "PREFER", vendorA));
        create(rule("Three", 1, "", "PREFER", vendorA));

        JsonNode first = kit.parse(kit.get(callerA, "/api/v1/allocation-rules?limit=2", everything));
        assertThat(first.get("items")).hasSize(2);
        assertThat(first.get("hasMore").asBoolean()).isTrue();
        assertThat(first.get("items").get(0).get("name").asString()).isEqualTo("Three");

        JsonNode second = kit.parse(kit.get(callerA,
                "/api/v1/allocation-rules?limit=2&cursor=" + first.get("nextCursor").asString(), everything));
        assertThat(second.get("items")).hasSize(1);
        assertThat(second.get("hasMore").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("a rule's name is unique within a company, whatever its case")
    void nameIsUnique() {
        create(rule("Prefer the north", 10, "", "PREFER", vendorA));
        expect("same name", kit.post(callerA, "/api/v1/allocation-rules",
                rule("prefer THE north", 20, "", "EXCLUDE", vendorA), everything),
                409, "allocation-rule-name-taken");
    }

    @Test
    @DisplayName("a rule must be coherent: its band, class, action, terminals and vendors")
    void validation() {
        expect("a band that runs backwards", kit.post(callerA, "/api/v1/allocation-rules",
                rule("a", 1, ",\"minWeightKg\":500,\"maxWeightKg\":100", "PREFER", vendorA), everything),
                409, "allocation-rule-weight-band-invalid");
        expect("a negative minimum", kit.post(callerA, "/api/v1/allocation-rules",
                rule("b", 1, ",\"minWeightKg\":-1", "PREFER", vendorA), everything),
                409, "allocation-rule-weight-band-invalid");
        expect("a zero maximum", kit.post(callerA, "/api/v1/allocation-rules",
                rule("c", 1, ",\"maxWeightKg\":0", "PREFER", vendorA), everything),
                409, "allocation-rule-weight-band-invalid");
        expect("an unknown class", kit.post(callerA, "/api/v1/allocation-rules",
                rule("d", 1, ",\"materialClass\":\"SHINY\"", "PREFER", vendorA), everything),
                409, "allocation-rule-material-class-invalid");
        expect("a priority out of range", kit.post(callerA, "/api/v1/allocation-rules",
                rule("e", 100001, "", "PREFER", vendorA), everything),
                409, "allocation-rule-priority-invalid");
        expect("a vendor named twice", kit.post(callerA, "/api/v1/allocation-rules",
                rule("f", 1, "", "PREFER", vendorA, vendorA), everything),
                409, "allocation-rule-vendors-invalid");
        expect("a vendor that does not exist", kit.post(callerA, "/api/v1/allocation-rules",
                rule("g", 1, "", "PREFER", UUID.randomUUID()), everything), 409, "vendor-not-active");
        expect("a terminal that does not exist", kit.post(callerA, "/api/v1/allocation-rules",
                rule("h", 1, ",\"originTerminalId\":\"" + UUID.randomUUID() + "\"", "PREFER", vendorA),
                everything), 404, null);
        expect("no vendors at all", kit.post(callerA, "/api/v1/allocation-rules",
                rule("i", 1, "", "PREFER"), everything), 400, null);
        expect("an action nobody defined", kit.post(callerA, "/api/v1/allocation-rules",
                rule("j", 1, "", "AWARD", vendorA), everything), 400, null);
        expect("no name", kit.post(callerA, "/api/v1/allocation-rules",
                rule("  ", 1, "", "PREFER", vendorA), everything), 400, null);
    }

    @Test
    @DisplayName("a rule is revised in whole, and retired rather than deleted")
    void revisingAndRetiring() {
        UUID id = create(rule("Original", 10, ",\"materialClass\":\"FOOD\"", "PREFER", vendorA));

        expect("revise", kit.put(callerA, "/api/v1/allocation-rules/" + id,
                rule("Renamed", 3, ",\"maxWeightKg\":800", "EXCLUDE", vendorA), everything), 204, null);
        JsonNode got = kit.parse(kit.get(callerA, "/api/v1/allocation-rules/" + id, everything));
        assertThat(got.get("name").asString()).isEqualTo("Renamed");
        assertThat(got.get("priority").asInt()).isEqualTo(3);
        assertThat(got.get("action").asString()).isEqualTo("EXCLUDE");
        assertThat(got.get("maxWeightKg").asDouble()).isEqualTo(800.0);
        assertThat(got.has("materialClass") && !got.get("materialClass").isNull())
                .as("a condition left out of the revision is gone").isFalse();

        expect("retire", kit.put(callerA, "/api/v1/allocation-rules/" + id,
                rule("Renamed", 3, ",\"active\":false", "EXCLUDE", vendorA), everything), 204, null);
        assertThat(kit.parse(kit.get(callerA, "/api/v1/allocation-rules?active=true", everything))
                .get("items")).isEmpty();
        assertThat(kit.parse(kit.get(callerA, "/api/v1/allocation-rules?active=false", everything))
                .get("items")).hasSize(1);

        expect("no such rule", kit.put(callerA, "/api/v1/allocation-rules/" + UUID.randomUUID(),
                rule("x", 1, "", "PREFER", vendorA), everything), 404, null);
        create(rule("Other", 9, "", "PREFER", vendorA));
        expect("renaming onto another rule's name", kit.put(callerA, "/api/v1/allocation-rules/" + id,
                rule("other", 3, "", "EXCLUDE", vendorA), everything), 409, "allocation-rule-name-taken");
    }

    @Test
    @DisplayName("writing a rule needs MANAGE; reading and evaluating need READ")
    void permissions() {
        UUID id = create(rule("Guarded", 10, "", "PREFER", vendorA));
        String body = rule("Other", 11, "", "PREFER", vendorA);

        expect("create with READ only", kit.post(callerA, "/api/v1/allocation-rules", body,
                "ALLOCATION_RULE_READ"), 403, null);
        expect("revise with READ only", kit.put(callerA, "/api/v1/allocation-rules/" + id, body,
                "ALLOCATION_RULE_READ"), 403, null);
        expect("list with MANAGE only", kit.get(callerA, "/api/v1/allocation-rules",
                "ALLOCATION_RULE_MANAGE"), 403, null);
        expect("get with MANAGE only", kit.get(callerA, "/api/v1/allocation-rules/" + id,
                "ALLOCATION_RULE_MANAGE"), 403, null);
        expect("evaluate with MANAGE only", kit.post(callerA, "/api/v1/allocation-rules/evaluations",
                "{\"loadId\":\"" + a.id("load_unit") + "\"}", "ALLOCATION_RULE_MANAGE"), 403, null);
        expect("create with MANAGE only", kit.post(callerA, "/api/v1/allocation-rules", body,
                "ALLOCATION_RULE_MANAGE"), 201, null);
    }

    @Test
    @DisplayName("another company can neither see a rule nor name this company's vendors in its own")
    void tenantsAreIsolated() {
        UUID id = create(rule("Mine", 10, "", "PREFER", vendorA));

        expect("b reads a's rule", kit.get(callerB, "/api/v1/allocation-rules/" + id, everything), 404, null);
        expect("b revises a's rule", kit.put(callerB, "/api/v1/allocation-rules/" + id,
                rule("Mine", 10, "", "PREFER", vendorB), everything), 404, null);
        assertThat(kit.parse(kit.get(callerB, "/api/v1/allocation-rules", everything)).get("items")).isEmpty();
        expect("b names a's vendor", kit.post(callerB, "/api/v1/allocation-rules",
                rule("Theirs", 10, "", "PREFER", vendorA), everything), 409, "vendor-not-active");
        expect("b uses a's terminal", kit.post(callerB, "/api/v1/allocation-rules",
                rule("Theirs", 10, ",\"originTerminalId\":\"" + terminalA + "\"", "PREFER", vendorB),
                everything), 404, null);
        expect("b's own rule", kit.post(callerB, "/api/v1/allocation-rules",
                rule("Mine", 10, "", "PREFER", vendorB), everything), 201, null);
    }

    @Test
    @DisplayName("a dry run for a load nobody can see, or one with no guide, is refused clearly")
    void evaluationRefusals() {
        expect("a load that does not exist", kit.post(callerA, "/api/v1/allocation-rules/evaluations",
                "{\"loadId\":\"" + UUID.randomUUID() + "\"}", everything), 404, null);
        expect("another company's load", kit.post(callerB, "/api/v1/allocation-rules/evaluations",
                "{\"loadId\":\"" + a.id("load_unit") + "\"}", everything), 404, null);
        expect("no loadId", kit.post(callerA, "/api/v1/allocation-rules/evaluations", "{}", everything),
                400, null);
    }
}
