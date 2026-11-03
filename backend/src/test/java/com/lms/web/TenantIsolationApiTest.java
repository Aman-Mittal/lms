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

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
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
 * One tenant must not be able to see, touch or even confirm the existence of
 * another tenant's by-id resources, over the real filter chain and a real
 * database.
 *
 * <p>The existing scenarios cover orders and users. This covers every other
 * resource a caller can name by id: consignments, loads, trips, allocations
 * and their offers, freight bills, client invoices, exception tickets, and the
 * warehouse's ASNs, outbound orders and handling units. The answer to all of
 * them is 404 -- not 403, which would confirm the id exists, and not data.
 *
 * <p>Both callers hold every permission, so a refusal can only be isolation:
 * the owner's own request for the same id is the control that proves the id,
 * the path and the permission are right, and that the 404 is not a typo.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TenantIsolationApiTest {

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

    /** One resource a caller can name by id, and where it is listed. */
    record Resource(String table, String byId, String list) {
        @Override
        public String toString() {
            return table;
        }
    }

    private static final List<Resource> RESOURCES = List.of(
            new Resource("sales_order", "/api/v1/orders/{id}", "/api/v1/orders"),
            new Resource("consignment", "/api/v1/consignments/{id}", "/api/v1/consignments/unassigned"),
            new Resource("load_unit", "/api/v1/loads/{id}", "/api/v1/loads"),
            new Resource("trip", "/api/v1/trips/{id}", "/api/v1/trips"),
            new Resource("allocation", "/api/v1/allocations/{id}", "/api/v1/allocations"),
            new Resource("freight_bill", "/api/v1/freight-bills/{id}", "/api/v1/freight-bills"),
            new Resource("exception_ticket", "/api/v1/exceptions/{id}", "/api/v1/exceptions"),
            new Resource("asn", "/api/v1/asns/{id}", "/api/v1/asns?warehouseId={warehouse}"),
            new Resource("outbound_order", "/api/v1/outbound-orders/{id}", "/api/v1/outbound-orders?warehouseId={warehouse}"),
            new Resource("handling_unit", "/api/v1/handling-units/{id}", "/api/v1/handling-units?warehouseId={warehouse}"),
            new Resource("client_invoice", "/api/v1/client-billing/invoices/{id}", null));

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
    private Tenant callerA;
    private Tenant callerB;

    static Stream<Resource> resources() {
        return RESOURCES.stream();
    }

    @BeforeAll
    void twoTenants() {
        kit = new ApiTestKit(fixtures, tokens, port);
        everything = seeds.allPermissions().toArray(String[]::new);
        a = seeds.seed(0.0);
        b = seeds.seed(1.0);
        callerA = kit.tenantOf(a.tenantId(), a.orgUnitId(), a.orgPath());
        callerB = kit.tenantOf(b.tenantId(), b.orgUnitId(), b.orgPath());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("resources")
    @DisplayName("the owner reads its own resource by id, so the probes below are not typos")
    void ownerSeesItsOwn(Resource resource) {
        Outcome own = kit.get(callerA, path(resource, a), everything);

        assertThat(own.status()).isEqualTo(200);
        assertThat(kit.parse(own).get("id").asString()).isEqualTo(a.id(resource.table()).toString());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("resources")
    @DisplayName("another tenant asking for it by id gets 404 -- not 403, not data")
    void strangerGetsNotFound(Resource resource) {
        Outcome stranger = kit.get(callerB, path(resource, a), everything);

        assertThat(stranger.status()).isEqualTo(404);
        assertThat(stranger.code()).isEqualTo("not-found");
        // Nothing about the real row: not its tenant, and not the tenant that is asking.
        assertThat(stranger.body()).doesNotContain(a.tenantId().toString());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("resources")
    @DisplayName("the stranger's own listing never includes the owner's rows")
    void listingsAreConfined(Resource resource) {
        if (resource.list() == null) {
            return;
        }
        Outcome listing = kit.get(callerB, listing(resource, a) + "limit=200", everything);

        assertThat(listing.status()).isEqualTo(200);
        assertThat(listing.body()).doesNotContain(a.id(resource.table()).toString());
        assertThat(listing.body()).doesNotContain(a.tenantId().toString());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("resources")
    @DisplayName("the owner's listing does include the row, so the confinement above is not an empty page")
    void ownerListingContainsTheRow(Resource resource) {
        // The unassigned-consignment queue and the paths with no flat listing
        // hold nothing for the scaffold's rows: its consignment is on a load.
        if (resource.list() == null || resource.table().equals("consignment")) {
            return;
        }
        Outcome listing = kit.get(callerA, listing(resource, a) + "limit=200", everything);

        assertThat(listing.status()).isEqualTo(200);
        assertThat(listing.body()).contains(a.id(resource.table()).toString());
    }

    @Test
    @DisplayName("a client's invoices are listed to its tenant and to nobody else")
    void invoiceListingsAreConfined() {
        String path = "/api/v1/client-billing/clients/" + a.clientId() + "/invoices";

        assertThat(kit.get(callerA, path, everything).body())
                .contains(a.id("client_invoice").toString());
        assertThat(kit.get(callerB, path, everything).body())
                .doesNotContain(a.id("client_invoice").toString());
    }

    @Test
    @DisplayName("a consignment cannot be tracked by its reference from another tenant")
    void trackingReferenceIsTenantScoped() {
        String path = "/api/v1/consignments/by-tracking-ref/" + a.trackingRef();

        assertThat(kit.get(callerA, path, everything).status()).isEqualTo(200);
        Outcome stranger = kit.get(callerB, path, everything);
        assertThat(stranger.status()).isEqualTo(404);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("subCollections")
    @DisplayName("a resource's children are not readable through the owner's id either")
    void childrenAreNotReadableByAStranger(String name, String path, boolean ownerHasRows) {
        Outcome owner = kit.get(callerA, path.replace("{id}", idFor(name)), everything);
        assertThat(owner.status()).isEqualTo(200);
        if (ownerHasRows) {
            assertThat(kit.parse(owner).size()).isPositive();
        }

        Outcome stranger = kit.get(callerB, path.replace("{id}", idFor(name)), everything);

        // Either answer reveals nothing: 404, or 200 with no rows because the
        // database filtered every one of them out.
        if (stranger.status() == 200) {
            assertThat(kit.parse(stranger).size()).isZero();
        } else {
            assertThat(stranger.status()).isEqualTo(404);
        }
    }

    static Stream<org.junit.jupiter.params.provider.Arguments> subCollections() {
        return Stream.of(
                org.junit.jupiter.params.provider.Arguments.of("load_unit",
                        "/api/v1/loads/{id}/consignments", true),
                org.junit.jupiter.params.provider.Arguments.of("sales_order",
                        "/api/v1/orders/{id}/lines", true),
                org.junit.jupiter.params.provider.Arguments.of("allocation",
                        "/api/v1/allocations/{id}/offers", true),
                org.junit.jupiter.params.provider.Arguments.of("exception_ticket",
                        "/api/v1/exceptions/{id}/history", true),
                org.junit.jupiter.params.provider.Arguments.of("freight_bill",
                        "/api/v1/freight-bills/{id}/lines", false),
                org.junit.jupiter.params.provider.Arguments.of("client_invoice",
                        "/api/v1/client-billing/invoices/{id}/lines", false));
    }

    /** The listing path, ending where a query parameter can be appended. */
    private static String listing(Resource resource, Seeded owner) {
        String path = resource.list().replace("{warehouse}", owner.id("warehouse").toString());
        return path + (path.contains("?") ? "&" : "?");
    }

    private static String path(Resource resource, Seeded owner) {
        return resource.byId().replace("{id}", owner.id(resource.table()).toString());
    }

    private String idFor(String table) {
        return a.id(table).toString();
    }

    @Test
    @DisplayName("a stranger cannot change a resource it cannot see")
    void strangerCannotWrite() {
        String statusBefore = jdbc.sql("SELECT status FROM exception_ticket WHERE id = :id")
                .param("id", a.id("exception_ticket")).query(String.class).single();
        String allocationBefore = jdbc.sql("SELECT status FROM allocation WHERE id = :id")
                .param("id", a.id("allocation")).query(String.class).single();
        assertThat(kit.post(callerB, "/api/v1/exceptions/" + a.id("exception_ticket") + "/assign",
                "{\"assigneeUserId\":\"" + UUID.randomUUID() + "\"}", everything).status())
                .isEqualTo(404);
        assertThat(kit.post(callerB, "/api/v1/freight-bills/" + a.id("freight_bill") + "/resolve",
                "{\"reason\":\"x\"}", everything).status()).isEqualTo(404);
        assertThat(kit.post(callerB, "/api/v1/allocations/" + a.id("allocation") + "/cancel",
                "{}", everything).status()).isEqualTo(404);

        // And what the owner has is exactly what it had.
        assertThat(kit.get(callerA, "/api/v1/exceptions/" + a.id("exception_ticket"), everything)
                .status()).isEqualTo(200);
        assertThat(jdbc.sql("SELECT status FROM exception_ticket WHERE id = :id")
                .param("id", a.id("exception_ticket")).query(String.class).single())
                .isEqualTo(statusBefore);
        assertThat(jdbc.sql("SELECT status FROM allocation WHERE id = :id")
                .param("id", a.id("allocation")).query(String.class).single())
                .isEqualTo(allocationBefore);
    }

    // ----------------------------------------- properties the body must not steer

    @Test
    @DisplayName("tenant and org properties smuggled into a body do not move the row to another tenant")
    void bodyCannotNameTheTenant() {
        String code = "SMUG-" + UUID.randomUUID().toString().substring(0, 6);
        // An unknown property is ignored (the mapper does not fail on them), and the
        // tenant is taken from the token, never from the body.
        String body = """
                {"orgUnitId":"%s","code":"%s","legalName":"Smuggler Ltd","partnerType":"CUSTOMER",
                 "tenantId":"%s","tenant_id":"%s","orgId":"%s","orgPath":"%s","ownerId":"%s"}
                """.formatted(b.orgUnitId(), code, a.tenantId(), a.tenantId(), a.orgUnitId(),
                a.orgPath(), a.tenantId());

        Outcome created = kit.post(callerB, "/api/v1/partners", body, everything);

        assertThat(created.status()).isEqualTo(201);
        UUID id = UUID.fromString(kit.parse(created).get("id").asString());
        assertThat(jdbc.sql("SELECT tenant_id FROM business_partner WHERE id = :id")
                .param("id", id).query(UUID.class).single()).isEqualTo(b.tenantId());
        assertThat(jdbc.sql("SELECT org_unit_id FROM business_partner WHERE id = :id")
                .param("id", id).query(UUID.class).single()).isEqualTo(b.orgUnitId());
        assertThat(jdbc.sql("SELECT count(*) FROM business_partner WHERE tenant_id = :t AND code = :c")
                .param("t", a.tenantId()).param("c", code).query(Integer.class).single()).isZero();
    }

    @Test
    @DisplayName("an org unit that belongs to another tenant cannot be named as the owner of a new row")
    void foreignOrgUnitIsRefused() {
        String code = "FOREIGN-" + UUID.randomUUID().toString().substring(0, 6);
        String body = """
                {"orgUnitId":"%s","code":"%s","legalName":"Foreign Ltd","partnerType":"CUSTOMER"}
                """.formatted(a.orgUnitId(), code);

        Outcome refused = kit.post(callerB, "/api/v1/partners", body, everything);

        // The assertion is the safe outcome, whatever its status: no row that
        // belongs to B may point into A's organisation.
        int pointingIntoA = jdbc.sql("""
                        SELECT count(*) FROM business_partner
                         WHERE tenant_id = :b AND org_unit_id = :a
                        """).param("b", b.tenantId()).param("a", a.orgUnitId())
                .query(Integer.class).single();
        assertThat(pointingIntoA).as("rows of B referencing A's org unit (status %d: %s)",
                refused.status(), refused.body()).isZero();
        // Forbidden, identically for a foreign unit and for one that does not exist,
        // so the answer does not confirm which ids are real.
        assertThat(refused.status()).isEqualTo(403);
        assertThat(refused.code()).isEqualTo("access-denied");
        String nobodys = """
                {"orgUnitId":"%s","code":"%s-2","legalName":"Nobody Ltd","partnerType":"CUSTOMER"}
                """.formatted(UUID.randomUUID(), code);
        Outcome unknown = kit.post(callerB, "/api/v1/partners", nobodys, everything);
        assertThat(unknown.status()).isEqualTo(403);
        assertThat(unknown.code()).isEqualTo("access-denied");
    }
}
