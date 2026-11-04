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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.lms.acceptance.TenantSeeds;
import com.lms.acceptance.TestFixtures;
import com.lms.identity.access.SubjectAttributes;
import com.lms.identity.security.TokenService;
import com.lms.web.ApiTestKit.Outcome;
import com.lms.web.ApiTestKit.Tenant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Who may list consignments: every desk that starts from one, not only the
 * planners. The delivery, claims and tracking desks pick a consignment before
 * they act on it, so each of their read rights opens the list on its own.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ConsignmentListRightsApiTest {

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

    private ApiTestKit kit;
    private Tenant tenant;
    private String[] all;
    private int terminalsMade;

    @BeforeEach
    void aTenant() {
        kit = new ApiTestKit(fixtures, tokens, port);
        tenant = kit.newTenant("cons-rights");
        all = seeds.allPermissions().toArray(String[]::new);
    }

    @ParameterizedTest(name = "{0} alone lists consignments")
    @ValueSource(strings = {"LOAD_READ", "POD_READ", "CLAIM_READ", "TRACKING_LINK_MANAGE"})
    @DisplayName("each desk's read right alone lists consignments")
    void eachDeskRight(String right) {
        String customer = partner("CUST-1", "Steel Works");
        String ref = consignmentFor(customer, "SO-1");

        Outcome listed = kit.get(tenant, "/api/v1/consignments", right);

        assertThat(listed.status()).as(listed.body()).isEqualTo(200);
        assertThat(refs(listed)).containsExactly(ref);
    }

    @Test
    @DisplayName("a caller with none of those rights is refused")
    void noRight() {
        Outcome refused = kit.get(tenant, "/api/v1/consignments", "PARTNER_READ", "ORDER_READ");

        assertThat(refused.status()).as(refused.body()).isEqualTo(403);
    }

    @Test
    @DisplayName("a portal user with a desk right still sees only their own customer's consignments")
    void portalUser() {
        String mine = partner("CUST-MINE", "Own Customer");
        String theirs = partner("CUST-OTHER", "Other Customer");
        String myRef = consignmentFor(mine, "SO-MINE");
        consignmentFor(theirs, "SO-OTHER");

        String token = tokens.issueAccessToken(tenant.user(), tenant.orgPath(), List.of("CLAIM_READ"),
                new SubjectAttributes(null, null, UUID.fromString(mine), null)).value();
        Outcome listed = kit.send(HttpRequest.newBuilder(
                        URI.create("http://localhost:" + port + "/api/v1/consignments"))
                .header("Authorization", "Bearer " + token).GET().build());

        assertThat(listed.status()).as(listed.body()).isEqualTo(200);
        assertThat(refs(listed)).containsExactly(myRef);
    }

    // ------------------------------------------------------------- building

    private String consignmentFor(String customer, String orderNo) {
        String origin = terminal("O-" + orderNo, "Plant " + orderNo);
        String destination = terminal("D-" + orderNo, "Mill " + orderNo);
        String order = created("/api/v1/orders", """
                {"orgUnitId":"%s","customerPartnerId":"%s","orderNo":"%s","originTerminalId":"%s"}"""
                .formatted(tenant.orgUnitId(), customer, orderNo, origin));
        Outcome line = kit.post(tenant, "/api/v1/orders/" + order + "/lines", """
                {"lineNo":1,"materialCode":"COIL","materialDescription":"Coil","materialClass":"GENERAL",
                 "quantity":1,"uom":"EA","deadWeightKg":1000,"consigneePartnerId":"%s",
                 "destinationTerminalId":"%s"}""".formatted(customer, destination), all);
        assertThat(line.status()).as(line.body()).isEqualTo(201);
        Outcome validated = kit.post(tenant, "/api/v1/orders/" + order + "/validate", "{}", all);
        assertThat(validated.status()).as(validated.body()).isBetween(200, 204);
        Outcome cut = kit.post(tenant, "/api/v1/orders/" + order + "/consignments", "{}", all);
        assertThat(cut.status()).as(cut.body()).isEqualTo(201);
        String id = kit.parse(cut).get(0).get("id").asString();
        Outcome read = kit.get(tenant, "/api/v1/consignments/" + id, all);
        assertThat(read.status()).as(read.body()).isEqualTo(200);
        return kit.parse(read).get("trackingRef").asString();
    }

    private String partner(String code, String name) {
        return created("/api/v1/partners", """
                {"orgUnitId":"%s","code":"%s","legalName":"%s","partnerType":"CUSTOMER"}"""
                .formatted(tenant.orgUnitId(), code, name));
    }

    /** Each terminal a degree further east, so none overlaps another of its category. */
    private String terminal(String code, String name) {
        double lon = 70 + terminalsMade++;
        return created("/api/v1/terminals/point-radius", """
                {"orgUnitId":"%s","code":"%s","name":"%s","category":"WAREHOUSE",
                 "centre":{"lat":19.3,"lon":%s},"radiusMetres":400}"""
                .formatted(tenant.orgUnitId(), code, name, lon));
    }

    private String created(String path, String body) {
        Outcome outcome = kit.post(tenant, path, body, all);
        assertThat(outcome.status()).as(path + " -> " + outcome.body()).isEqualTo(201);
        return kit.parse(outcome).get("id").asString();
    }

    private List<String> refs(Outcome outcome) {
        List<String> out = new ArrayList<>();
        kit.parse(outcome).get("items").forEach(item -> out.add(item.get("trackingRef").asString()));
        return out;
    }
}
