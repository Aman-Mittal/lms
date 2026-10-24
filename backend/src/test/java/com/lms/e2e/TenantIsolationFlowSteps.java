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

import java.util.UUID;
import java.util.function.Supplier;

import com.lms.acceptance.TestFixtures;
import com.lms.client.api.AuthenticationApi;
import com.lms.client.api.OrdersApi;
import com.lms.client.api.PlanningApi;
import com.lms.client.api.SourcingApi;
import com.lms.client.api.TripsApi;
import com.lms.client.model.AuthResult;
import com.lms.client.model.LoginRequest;
import com.lms.client.model.RegisterPartnerRequest;
import com.lms.client.model.RegisterPointRadiusTerminalRequest;
import feign.FeignException;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tenant isolation over HTTP.
 *
 * <p>The one thing done in-process is provisioning the second company: the
 * platform has no self-service tenant registration (the demo seeder exists for
 * exactly that reason), so a tenant, its head office, an administrator role
 * holding every permission and one user are written directly -- the same
 * footing the demo tenant is created on. Everything the two companies then do,
 * the first planning freight and the second probing for it, crosses the wire
 * with the client generated from the contract.
 *
 * <p>Latitude band 25: no other scenario uses it.
 */
public class TenantIsolationFlowSteps {

    private static final String PASSWORD = "second company e2e password, not a real one";

    @Value("${local.server.port}")
    private int port;
    @Autowired
    private TestFixtures fixtures;
    @Autowired
    private JdbcClient jdbc;

    private HttpWorld demo;
    private UUID orderId;
    private UUID consignmentId;
    private UUID loadId;
    private UUID tripId;
    private UUID allocationId;
    private String secondToken;
    private String secondTenantCode;

    @Given("the demo administrator has an awarded trip with its order, consignment and load")
    public void anAwardedTrip() {
        demo = new HttpWorld(port);
        demo.signInAsDemoAdministrator();

        var site = RegisterPointRadiusTerminalRequest.CategoryEnum.CUSTOMER_SITE;
        UUID origin = demo.terminal(site, "ISO-ORIGIN", 25.10, 72.90);
        UUID destination = demo.terminal(site, "ISO-DEST", 25.30, 73.10);
        UUID vendor = demo.activeVendor("ISV");
        UUID customer = demo.partner("ISC", RegisterPartnerRequest.PartnerTypeEnum.CUSTOMER);
        UUID consignee = demo.partner("ISN", RegisterPartnerRequest.PartnerTypeEnum.CONSIGNEE);
        UUID lorry = demo.lorry("IS", 20000);
        UUID driver = demo.driver("IS");
        tripId = demo.assignedTrip("IS", customer, consignee, origin, destination, vendor, lorry,
                driver, 5000);

        String orderNo = "SO-IS-" + demo.salt;
        orderId = first(() -> demo.api(OrdersApi.class)
                .listOrders(null, null, 200).getItems().stream()
                .filter(o -> orderNo.equals(o.getOrderNo())).findFirst().orElseThrow().getId());
        String loadNo = "LD-IS-" + demo.salt;
        loadId = first(() -> demo.api(PlanningApi.class)
                .listLoads(null, null, 200).getItems().stream()
                .filter(l -> loadNo.equals(l.getLoadNo())).findFirst().orElseThrow().getId());
        // Read straight from the table: the spec declares GET /loads/{id}/consignments as
        // answering one Consignment, while the server answers an array, so the generated
        // client cannot read it. The id is only needed to ask for it by id afterwards.
        consignmentId = jdbc.sql("SELECT id FROM consignment WHERE order_id = :o")
                .param("o", orderId).query(UUID.class).single();
        allocationId = first(() -> demo.api(SourcingApi.class)
                .listAllocations(null, null, 200).getItems().stream()
                .filter(a -> loadId.equals(a.getLoadId())).findFirst().orElseThrow().getId());
    }

    @Given("a second company with its own administrator is signed in")
    public void aSecondCompany() {
        String salt = UUID.randomUUID().toString().substring(0, 8);
        secondTenantCode = "second-" + salt;
        String orgPath = "/" + secondTenantCode + "/";
        String email = "admin@" + secondTenantCode + ".test";

        UUID tenantId = fixtures.createTenant(secondTenantCode, "Second Company " + salt);
        UUID orgUnitId = fixtures.createOrgUnit(tenantId, orgPath, "Head Office", "HQ", null);
        UUID userId = fixtures.createUser(tenantId, orgUnitId, orgPath, email, PASSWORD);

        UUID roleId = UUID.randomUUID();
        jdbc.sql("INSERT INTO role (id, tenant_id, code, name) VALUES (:id, :t, 'ADMIN', 'Administrator')")
                .param("id", roleId).param("t", tenantId).update();
        jdbc.sql("""
                        INSERT INTO role_permission (tenant_id, role_id, permission_id)
                        SELECT :t, :r, p.id FROM permission p
                        """).param("t", tenantId).param("r", roleId).update();
        jdbc.sql("INSERT INTO user_role (tenant_id, user_id, role_id) VALUES (:t, :u, :r)")
                .param("t", tenantId).param("u", userId).param("r", roleId).update();

        AuthResult login = demo.client(null, AuthenticationApi.class).login(new LoginRequest()
                .tenantCode(secondTenantCode).email(email).password(PASSWORD));
        secondToken = login.getAccessToken();
        assertThat(secondToken).isNotBlank();
    }

    @Then("the second company is told the demo company's order, consignment, load, trip and allocation do not exist")
    public void strangerGetsNotFound() {
        assertNotFound("order", () -> second(OrdersApi.class).getOrder(orderId));
        assertNotFound("consignment", () -> second(PlanningApi.class).getConsignment(consignmentId));
        assertNotFound("load", () -> second(PlanningApi.class).getLoad(loadId));
        assertNotFound("trip", () -> second(TripsApi.class).getTrip(tripId));
        assertNotFound("allocation", () -> second(SourcingApi.class).getAllocation(allocationId));
    }

    @Then("none of the second company's listings contain the demo company's freight")
    public void listingsAreConfined() {
        assertThat(second(OrdersApi.class).listOrders(null, null, 200).getItems()).isEmpty();
        assertThat(second(PlanningApi.class).listLoads(null, null, 200).getItems()).isEmpty();
        assertThat(second(TripsApi.class).listTrips(null, null, 200).getItems()).isEmpty();
        assertThat(second(SourcingApi.class).listAllocations(null, null, 200).getItems()).isEmpty();
        assertThat(second(PlanningApi.class).listUnassignedConsignments(null, 200).getItems())
                .isEmpty();
    }

    @Then("the demo administrator reads the order, consignment, load, trip and allocation by id")
    public void ownerReadsEverything() {
        assertThat(demo.api(OrdersApi.class).getOrder(orderId).getId()).isEqualTo(orderId);
        assertThat(demo.api(PlanningApi.class).getConsignment(consignmentId).getId())
                .isEqualTo(consignmentId);
        assertThat(demo.api(PlanningApi.class).getLoad(loadId).getId()).isEqualTo(loadId);
        assertThat(demo.api(TripsApi.class).getTrip(tripId).getId()).isEqualTo(tripId);
        assertThat(demo.api(SourcingApi.class).getAllocation(allocationId).getId())
                .isEqualTo(allocationId);
    }

    private <T extends com.lms.client.ApiClient.Api> T second(Class<T> type) {
        return demo.client(secondToken, type);
    }

    private static void assertNotFound(String what, Runnable call) {
        assertThatThrownBy(call::run).as(what)
                .isInstanceOfSatisfying(FeignException.class, e -> assertThat(e.status()).isEqualTo(404));
    }

    /** The write has returned, so what it wrote is there to be read; no waiting. */
    private static <T> T first(Supplier<T> lookup) {
        return lookup.get();
    }
}
