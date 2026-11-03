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
 * Custom fields: a tenant defines what it tracks, then records values that are
 * checked against those definitions.
 *
 * <p>Everything is asked through HTTP with real permissions, so the guards on
 * the services are what is being tested, not just the validation behind them.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CustomFieldApiTest {

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

    private static final String DEFINITIONS = "/api/v1/custom-fields/definitions";

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
    private UUID aPartner;
    private UUID aVehicle;

    @BeforeAll
    void twoTenants() {
        kit = new ApiTestKit(fixtures, tokens, port);
        everything = seeds.allPermissions().toArray(String[]::new);
        Seeded a = seeds.seed(0.0);
        Seeded b = seeds.seed(1.0);
        callerA = kit.tenantOf(a.tenantId(), a.orgUnitId(), a.orgPath());
        callerB = kit.tenantOf(b.tenantId(), b.orgUnitId(), b.orgPath());
        aPartner = first("business_partner", a);
        aVehicle = first("vehicle", a);
    }

    private UUID first(String table, Seeded of) {
        return jdbc.sql("SELECT id FROM " + table + " WHERE tenant_id = :t LIMIT 1")
                .param("t", of.tenantId()).query(UUID.class).list().get(0);
    }

    private Outcome define(Tenant caller, String entityType, String key, String type, boolean required,
                           String options) {
        return kit.post(caller, DEFINITIONS, "{\"entityType\":\"" + entityType + "\",\"key\":\"" + key
                + "\",\"label\":\"" + key + " label\",\"fieldType\":\"" + type + "\",\"required\":"
                + required + (options == null ? "" : ",\"options\":" + options) + "}", everything);
    }

    private Outcome setValues(Tenant caller, String entityType, UUID id, String valuesJson,
                              String... permissions) {
        return kit.put(caller, "/api/v1/custom-fields/values/" + entityType + "/" + id,
                "{\"values\":" + valuesJson + "}", permissions.length == 0 ? everything : permissions);
    }

    private JsonNode valuesOf(Tenant caller, String entityType, UUID id) {
        Outcome got = kit.get(caller, "/api/v1/custom-fields/values/" + entityType + "/" + id, everything);
        assertThat(got.status()).as(got.body()).isEqualTo(200);
        return kit.parse(got).get("values");
    }

    private void accepted(String what, Outcome outcome) {
        assertThat(outcome.status()).as(what + ": " + outcome.body()).isBetween(200, 299);
    }

    private void refused(String what, Outcome outcome, int status) {
        assertThat(outcome.status()).as(what + ": " + outcome.body()).isEqualTo(status);
    }

    @Test
    @DisplayName("a field is defined once per kind of record, with a well-formed key and options that fit its type")
    void definitionRules() {
        accepted("text", define(callerA, "PARTNER", "po_number", "TEXT", false, null));
        refused("same key again", define(callerA, "PARTNER", "po_number", "TEXT", false, null), 409);
        // The same key on another kind of record is a different field.
        accepted("same key, other record kind", define(callerA, "VEHICLE", "po_number", "TEXT", false, null));

        refused("upper case key", define(callerA, "PARTNER", "PoNumber", "TEXT", false, null), 409);
        refused("key too short", define(callerA, "PARTNER", "a", "TEXT", false, null), 409);
        refused("a choice with no options", define(callerA, "PARTNER", "grade", "SELECT", false, null), 409);
        refused("options on a text field",
                define(callerA, "PARTNER", "note", "TEXT", false, "[\"x\"]"), 409);
        refused("duplicate options",
                define(callerA, "PARTNER", "grade", "SELECT", false, "[\"A\",\"A\"]"), 409);
        accepted("a choice", define(callerA, "PARTNER", "grade", "SELECT", false, "[\"A\",\"B\"]"));

        Outcome listed = kit.get(callerA, DEFINITIONS + "?entityType=PARTNER", everything);
        assertThat(listed.status()).isEqualTo(200);
        assertThat(kit.parse(listed).get("items")).as("tests share a tenant, so others may be there too")
                .extracting(n -> n.get("key").asString())
                .contains("po_number", "grade");
    }

    @Test
    @DisplayName("values are checked against their definitions and every problem is reported at once")
    void valueValidation() {
        accepted("text", define(callerA, "VEHICLE", "owner_ref", "TEXT", true, null));
        accepted("number", define(callerA, "VEHICLE", "axle_count", "NUMBER", false, null));
        accepted("date", define(callerA, "VEHICLE", "fitness_on", "DATE", false, null));
        accepted("flag", define(callerA, "VEHICLE", "has_tracker", "BOOLEAN", false, null));
        accepted("choice", define(callerA, "VEHICLE", "body", "SELECT", false, "[\"OPEN\",\"CLOSED\"]"));

        accepted("a valid set", setValues(callerA, "VEHICLE", aVehicle,
                "{\"owner_ref\":\"OR-1\",\"axle_count\":3,\"fitness_on\":\"2026-12-31\","
                        + "\"has_tracker\":true,\"body\":\"OPEN\"}"));
        JsonNode stored = valuesOf(callerA, "VEHICLE", aVehicle);
        assertThat(stored.get("axle_count").asInt()).isEqualTo(3);
        assertThat(stored.get("fitness_on").asString()).isEqualTo("2026-12-31");
        assertThat(stored.get("has_tracker").asBoolean()).isTrue();

        // Four things wrong at once, one answer.
        Outcome bad = setValues(callerA, "VEHICLE", aVehicle,
                "{\"axle_count\":\"three\",\"fitness_on\":\"31/12/2026\",\"body\":\"TANKER\",\"nope\":1}");
        refused("invalid", bad, 409);
        assertThat(bad.code()).isEqualTo("custom-field-invalid");
        assertThat(bad.body()).contains("axle_count", "fitness_on", "body", "nope", "owner_ref");

        // A refused write changed nothing.
        assertThat(valuesOf(callerA, "VEHICLE", aVehicle).get("owner_ref").asString()).isEqualTo("OR-1");
    }

    @Test
    @DisplayName("a write replaces, and retiring a field keeps what was stored but stops requiring it")
    void replaceAndRetire() {
        accepted("required note", define(callerA, "PARTNER", "credit_note", "TEXT", true, null));
        accepted("optional tier", define(callerA, "PARTNER", "tier", "SELECT", false, "[\"GOLD\",\"SILVER\"]"));

        refused("required missing", setValues(callerA, "PARTNER", aPartner, "{\"tier\":\"GOLD\"}"), 409);
        accepted("both", setValues(callerA, "PARTNER", aPartner,
                "{\"credit_note\":\"net 30\",\"tier\":\"GOLD\"}"));
        accepted("replace without tier", setValues(callerA, "PARTNER", aPartner,
                "{\"credit_note\":\"net 45\"}"));
        JsonNode after = valuesOf(callerA, "PARTNER", aPartner);
        assertThat(after.has("tier")).as("tier was left out, so it is cleared").isFalse();
        assertThat(after.get("credit_note").asString()).isEqualTo("net 45");

        // Retire the required field.
        UUID id = idOf("PARTNER", "credit_note");
        accepted("retire", kit.put(callerA, DEFINITIONS + "/" + id,
                "{\"label\":\"Credit note\",\"required\":true,\"active\":false}", everything));
        accepted("no longer required", setValues(callerA, "PARTNER", aPartner, "{\"tier\":\"SILVER\"}"));
        assertThat(valuesOf(callerA, "PARTNER", aPartner).get("credit_note").asString())
                .as("a write that does not mention a retired field keeps what it held").isEqualTo("net 45");
        refused("no longer a field", setValues(callerA, "PARTNER", aPartner,
                "{\"credit_note\":\"x\"}"), 409);
    }

    private UUID idOf(String entityType, String key) {
        Outcome listed = kit.get(callerA, DEFINITIONS + "?entityType=" + entityType, everything);
        for (JsonNode item : kit.parse(listed).get("items")) {
            if (key.equals(item.get("key").asString())) {
                return UUID.fromString(item.get("id").asString());
            }
        }
        throw new AssertionError("no definition " + key);
    }

    @Test
    @DisplayName("defining needs CUSTOM_FIELD_MANAGE, reading needs CUSTOM_FIELD_READ, writing needs CUSTOM_FIELD_WRITE")
    void permissionsAreChecked() {
        String body = "{\"entityType\":\"DRIVER\",\"key\":\"union_id\",\"label\":\"Union\",\"fieldType\":\"TEXT\"}";
        refused("define with read and write only",
                kit.post(callerA, DEFINITIONS, body, "CUSTOM_FIELD_READ", "CUSTOM_FIELD_WRITE"), 403);
        accepted("define with manage",
                kit.post(callerA, DEFINITIONS, body, "CUSTOM_FIELD_MANAGE"));

        refused("list with manage only", kit.get(callerA, DEFINITIONS, "CUSTOM_FIELD_MANAGE"), 403);
        refused("write with read only",
                setValues(callerA, "PARTNER", aPartner, "{}", "CUSTOM_FIELD_READ"), 403);
        refused("read with write only",
                kit.get(callerA, "/api/v1/custom-fields/values/PARTNER/" + aPartner, "CUSTOM_FIELD_WRITE"),
                403);
    }

    @Test
    @DisplayName("another tenant sees none of it and cannot attach values to a record that is not its own")
    void tenantsAreIsolated() {
        accepted("a defines", define(callerA, "TERMINAL", "dock_count", "NUMBER", false, null));

        Outcome bList = kit.get(callerB, DEFINITIONS + "?entityType=TERMINAL", everything);
        assertThat(kit.parse(bList).get("items")).as("b sees no definition of a's").isEmpty();

        // B has no such field, and A's record is invisible to B.
        refused("b writes to a's partner",
                setValues(callerB, "PARTNER", aPartner, "{}"), 404);
        refused("b reads a's partner",
                kit.get(callerB, "/api/v1/custom-fields/values/PARTNER/" + aPartner, everything), 404);
        // A record that does not exist answers the same way.
        refused("a record nobody has",
                setValues(callerA, "PARTNER", UUID.randomUUID(), "{}"), 404);

        // The same key may be defined independently by the other tenant.
        accepted("b defines the same key", define(callerB, "TERMINAL", "dock_count", "TEXT", false, null));
    }
}
