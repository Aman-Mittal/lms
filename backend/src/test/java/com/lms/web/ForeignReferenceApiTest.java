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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * An id taken from a request body must not be able to name another tenant's
 * row. A foreign key is checked as the table owner, so the database accepts
 * such an insert; only a lookup under row-level security refuses it.
 *
 * <p>Each probe is made by tenant B, naming a row of tenant A in a body, and
 * must be refused (any 4xx, never 2xx). The same request with B's own ids is
 * the control that proves the body and the path are right.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ForeignReferenceApiTest {

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
    private Seeded b;
    private Tenant callerB;
    private UUID aPartner;
    private UUID aTerminal;
    private UUID aVehicle;
    private UUID bPartner;
    private UUID bTerminal;
    private UUID bVehicle;

    @BeforeAll
    void twoTenants() {
        kit = new ApiTestKit(fixtures, tokens, port);
        everything = seeds.allPermissions().toArray(String[]::new);
        a = seeds.seed(0.0);
        b = seeds.seed(1.0);
        callerB = kit.tenantOf(b.tenantId(), b.orgUnitId(), b.orgPath());
        aPartner = first("business_partner", a, " AND partner_type = 'CUSTOMER'");
        bPartner = first("business_partner", b, " AND partner_type = 'CUSTOMER'");
        aTerminal = first("terminal", a, "");
        bTerminal = first("terminal", b, "");
        aVehicle = first("vehicle", a, "");
        bVehicle = first("vehicle", b, "");
    }

    private UUID first(String table, Seeded of, String extra) {
        return jdbc.sql("SELECT id FROM " + table + " WHERE tenant_id = :t" + extra + " LIMIT 1")
                .param("t", of.tenantId()).query(UUID.class).list().get(0);
    }

    private Outcome post(String path, String body) {
        return kit.post(callerB, path, body, everything);
    }

    private static String order(String no, UUID orgUnit, UUID customer, UUID terminal) {
        return "{\"orgUnitId\":\"" + orgUnit + "\",\"customerPartnerId\":\"" + customer
                + "\",\"orderNo\":\"" + no + "\",\"originTerminalId\":\"" + terminal + "\"}";
    }

    private static String line(UUID consignee, UUID terminal) {
        return "{\"lineNo\":1,\"materialCode\":\"M\",\"materialDescription\":\"Goods\",\"materialClass\":\"GENERAL\",\"quantity\":1,"
                + "\"uom\":\"EA\",\"deadWeightKg\":10,"
                + (consignee == null ? "" : "\"consigneePartnerId\":\"" + consignee + "\",")
                + "\"destinationTerminalId\":\"" + terminal + "\"}";
    }

    private void refused(String what, Outcome outcome) {
        assertThat(outcome.status()).as(what + ": " + outcome.body()).isBetween(400, 499);
    }

    private void accepted(String what, Outcome outcome) {
        assertThat(outcome.status()).as(what + ": " + outcome.body()).isBetween(200, 299);
    }

    @Test
    @DisplayName("an order cannot name another tenant's customer or origin terminal")
    void orderReferences() {
        accepted("control", post("/api/v1/orders", order("FR-1", b.orgUnitId(), bPartner, bTerminal)));
        refused("foreign customer",
                post("/api/v1/orders", order("FR-2", b.orgUnitId(), aPartner, bTerminal)));
        refused("foreign origin terminal",
                post("/api/v1/orders", order("FR-3", b.orgUnitId(), bPartner, aTerminal)));
    }

    @Test
    @DisplayName("an order line cannot name another tenant's consignee or destination terminal")
    void lineReferences() {
        String draft = kit.parse(post("/api/v1/orders",
                order("FR-4", b.orgUnitId(), bPartner, bTerminal))).get("id").asString();
        refused("foreign consignee",
                post("/api/v1/orders/" + draft + "/lines", line(aPartner, bTerminal)));
        refused("foreign destination terminal",
                post("/api/v1/orders/" + draft + "/lines", line(bPartner, aTerminal)));
        accepted("control", post("/api/v1/orders/" + draft + "/lines", line(bPartner, bTerminal)));
    }

    @Test
    @DisplayName("a load cannot name another tenant's vehicle or origin terminal")
    void loadReferences() {
        String load = "{\"orgUnitId\":\"" + b.orgUnitId() + "\",\"loadNo\":\"%s\","
                + "\"originTerminalId\":\"%s\",\"vehicleId\":\"%s\"}";
        accepted("control", post("/api/v1/loads", load.formatted("FRL-1", bTerminal, bVehicle)));
        refused("foreign vehicle", post("/api/v1/loads", load.formatted("FRL-2", bTerminal, aVehicle)));
        refused("foreign terminal", post("/api/v1/loads", load.formatted("FRL-3", aTerminal, bVehicle)));
    }

    @Test
    @DisplayName("a routing guide cannot name another tenant's terminals")
    void guideReferences() {
        UUID bOther = b.id("warehouse");
        String guide = "{\"orgUnitId\":\"" + b.orgUnitId() + "\",\"originTerminalId\":\"%s\","
                + "\"destinationTerminalId\":\"%s\",\"vehicleType\":\"RIGID\"}";
        accepted("control", post("/api/v1/routing-guides", guide.formatted(bTerminal, bOther)));
        refused("foreign origin", post("/api/v1/routing-guides", guide.formatted(aTerminal, bOther)));
        refused("foreign destination",
                post("/api/v1/routing-guides", guide.formatted(bTerminal, a.id("warehouse"))));
    }

    @Test
    @DisplayName("trips, allocations, exceptions, consolidation and ASNs refuse another tenant's rows")
    void alreadyRefused() {
        refused("trip on a foreign load", post("/api/v1/trips",
                "{\"tripNo\":\"FRT-1\",\"loadId\":\"" + a.id("load_unit") + "\"}"));
        refused("foreign vehicle on a trip", post("/api/v1/trips/" + b.id("trip") + "/assignment",
                "{\"vehicleId\":\"" + aVehicle + "\"}"));
        refused("gate-in at a foreign terminal", post("/api/v1/trips/" + b.id("trip") + "/gate-in",
                "{\"terminalId\":\"" + aTerminal + "\"}"));
        refused("allocation of a foreign load", post("/api/v1/allocations",
                "{\"loadId\":\"" + a.id("load_unit") + "\"}"));
        refused("exception on a foreign trip", post("/api/v1/exceptions",
                "{\"tripId\":\"" + a.id("trip") + "\",\"category\":\"THEFT\",\"description\":\"x\"}"));
        refused("foreign consignment on a load", post(
                "/api/v1/loads/" + b.id("load_unit") + "/consignments",
                "{\"consignmentId\":\"" + a.id("consignment") + "\"}"));
        refused("ASN into a foreign warehouse", post("/api/v1/asns",
                "{\"warehouseId\":\"" + a.id("warehouse") + "\",\"asnNo\":\"FR-ASN\","
                        + "\"lines\":[{\"skuId\":\"" + UUID.randomUUID() + "\",\"quantity\":1}]}"));
    }

    @Test
    @DisplayName("a rate card and an LTL zone cannot name another tenant's terminal or vendor")
    void financeReferences() {
        UUID aVendor = first("business_partner", a, " AND partner_type = 'VENDOR'");
        UUID bVendor = first("business_partner", b, " AND partner_type = 'VENDOR'");
        UUID bOther = b.id("warehouse");
        String tariff = "{\"orgUnitId\":\"" + b.orgUnitId() + "\",\"code\":\"%s\","
                + "\"vendorPartnerId\":\"%s\",\"originTerminalId\":\"%s\","
                + "\"destinationTerminalId\":\"%s\",\"vehicleType\":\"RIGID\","
                + "\"effectiveFrom\":\"2026-01-01\"}";
        accepted("control", post("/api/v1/tariffs", tariff.formatted("FRT-A", bVendor, bTerminal, bOther)));
        refused("foreign vendor",
                post("/api/v1/tariffs", tariff.formatted("FRT-B", aVendor, bTerminal, bOther)));
        refused("foreign origin",
                post("/api/v1/tariffs", tariff.formatted("FRT-C", bVendor, aTerminal, bOther)));
        refused("foreign destination",
                post("/api/v1/tariffs", tariff.formatted("FRT-D", bVendor, bTerminal, aTerminal)));

        String zone = "{\"zoneCode\":\"WEST\"}";
        accepted("zone control", kit.put(callerB, "/api/v1/ltl/zones/" + bTerminal, zone, everything));
        refused("foreign terminal in a zone",
                kit.put(callerB, "/api/v1/ltl/zones/" + aTerminal, zone, everything));
    }
}
