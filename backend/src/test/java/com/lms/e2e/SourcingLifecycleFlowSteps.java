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

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.lms.client.api.OrdersApi;
import com.lms.client.api.PlanningApi;
import com.lms.client.api.SourcingApi;
import com.lms.client.api.TripsApi;
import com.lms.client.model.AddOrderLineRequest;
import com.lms.client.model.AddVendorRequest;
import com.lms.client.model.AllocateRequest;
import com.lms.client.model.Allocation;
import com.lms.client.model.AssignConsignmentRequest;
import com.lms.client.model.CancelOrderRequest;
import com.lms.client.model.DefineGuideRequest;
import com.lms.client.model.IdResponse;
import com.lms.client.model.Offer;
import com.lms.client.model.OpenLoadRequest;
import com.lms.client.model.RaiseOrderRequest;
import com.lms.client.model.RaiseTripRequest;
import com.lms.client.model.RegisterPartnerRequest;
import com.lms.client.model.RegisterPointRadiusTerminalRequest;
import com.lms.client.model.VendorResponseRequest;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import org.springframework.beans.factory.annotation.Value;
import tools.jackson.databind.JsonNode;

import static com.lms.e2e.PlanningFlowSupport.assertProblem;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Cancelling an award, and cancelling an order, over HTTP.
 *
 * <p>Every step text carries "sourcing-lifecycle" because Cucumber glue is
 * global across the package and a step phrase may exist only once. The
 * geography stays in the 70 to 74 degrees north band.
 */
public class SourcingLifecycleFlowSteps {

    private static final double LON = 10.90;

    @Value("${local.server.port}")
    private int port;

    private HttpWorld world;
    /** Raw HTTP and tokens only; provisioning is not needed here. */
    private PlanningFlowSupport support;

    private UUID customer;
    private UUID consignee;
    private final Map<String, UUID> vendors = new HashMap<>();
    private UUID origin;
    private UUID destination;
    private UUID lorry;

    private UUID orderId;
    private UUID loadId;
    private UUID firstAllocationId;
    private UUID allocationId;

    @Given("the demo administrator is signed in at the sourcing-lifecycle desk")
    public void signIn() {
        world = new HttpWorld(port);
        world.signInAsDemoAdministrator();
        support = new PlanningFlowSupport(world, port, null, null, null);
    }

    @Given("sourcing-lifecycle parties, vendor {string}, two depots and a lorry are registered")
    public void parties(String vendor) {
        customer = world.partner("SLC", RegisterPartnerRequest.PartnerTypeEnum.CUSTOMER);
        consignee = world.partner("SLN", RegisterPartnerRequest.PartnerTypeEnum.CONSIGNEE);
        vendors.put(vendor, world.activeVendor("SL" + vendor));
        origin = world.terminal(RegisterPointRadiusTerminalRequest.CategoryEnum.CUSTOMER_SITE,
                "SL-ORIGIN", 70.10, LON);
        destination = world.terminal(RegisterPointRadiusTerminalRequest.CategoryEnum.CUSTOMER_SITE,
                "SL-DEST", 70.30, LON + 0.20);
        lorry = world.lorry("SL", 20000);
    }

    @Given("a sourcing-lifecycle load is planned and offered to {string}")
    public void plannedAndOffered(String vendor) {
        OrdersApi orders = world.api(OrdersApi.class);
        orderId = orders.raiseOrder(new RaiseOrderRequest()
                .orgUnitId(world.orgUnitId())
                .customerPartnerId(customer)
                .orderNo(world.code("SO-SL"))
                .originTerminalId(origin)
                .requestedPickupAt(OffsetDateTime.now(ZoneOffset.UTC).plusHours(4))
                .source(RaiseOrderRequest.SourceEnum.API), null).getId();
        orders.addOrderLine(orderId, new AddOrderLineRequest()
                .lineNo(1)
                .materialCode("COIL")
                .materialDescription("Steel coil")
                .materialClass("GENERAL")
                .quantity(new BigDecimal("10"))
                .uom("UNIT")
                .deadWeightKg(new BigDecimal("4000"))
                .consigneePartnerId(consignee)
                .destinationTerminalId(destination), null);
        orders.validateOrder(orderId, null);

        PlanningApi planning = world.api(PlanningApi.class);
        List<IdResponse> generated = planning.generateConsignments(orderId, null);
        loadId = planning.openLoad(new OpenLoadRequest()
                .orgUnitId(world.orgUnitId())
                .loadNo(world.code("LD-SL"))
                .originTerminalId(origin)
                .vehicleId(lorry), null).getId();
        planning.assignConsignment(loadId,
                new AssignConsignmentRequest().consignmentId(generated.get(0).getId()), null);
        planning.planLoad(loadId, null);

        SourcingApi sourcing = world.api(SourcingApi.class);
        UUID guideId = sourcing.defineRoutingGuide(new DefineGuideRequest()
                .orgUnitId(world.orgUnitId())
                .originTerminalId(origin)
                .destinationTerminalId(destination)
                .vehicleType(HttpWorld.VEHICLE_TYPE), null).getId();
        sourcing.addRoutingGuideVendor(guideId, new AddVendorRequest()
                .vendorPartnerId(vendors.get(vendor)).rankNo(1).responseSlaMinutes(60)
                .agreedRate(new BigDecimal("20000")), null);
        offer();
        firstAllocationId = allocationId;
    }

    @When("vendor {string} accepts the sourcing-lifecycle offer")
    public void accepts(String vendor) {
        world.api(SourcingApi.class).acceptOffer(allocationId,
                new VendorResponseRequest().vendorPartnerId(vendors.get(vendor)), null);
    }

    @When("the sourcing-lifecycle allocation is abandoned")
    public void cancelAllocation() {
        world.api(SourcingApi.class).cancelAllocation(allocationId, null);
    }

    @When("the sourcing-lifecycle load is sent out for offers again")
    public void offerAgain() {
        offer();
    }

    @Given("a sourcing-lifecycle trip is raised on the load")
    public void tripRaised() {
        world.api(TripsApi.class).raiseTrip(new RaiseTripRequest().tripNo(world.code("TR-SL"))
                .loadId(loadId).plannedStartAt(OffsetDateTime.now(ZoneOffset.UTC).plusHours(2)), null);
    }

    @When("the sourcing-lifecycle order is withdrawn")
    public void cancelOrder() {
        world.api(OrdersApi.class).cancelOrder(orderId, null,
                new CancelOrderRequest().reason("customer withdrew the indent"));
    }

    @Then("the sourcing-lifecycle load has status {word}")
    public void loadStatus(String status) {
        assertThat(world.api(PlanningApi.class).getLoad(loadId).getStatus()).isEqualTo(status);
    }

    @Then("the sourcing-lifecycle allocation has status {word}")
    public void allocationStatus(String status) {
        assertThat(world.api(SourcingApi.class).getAllocation(allocationId).getStatus())
                .isEqualTo(status);
    }

    @Then("the sourcing-lifecycle second allocation is a new one, OFFERED to {string}")
    public void secondAllocation(String vendor) {
        assertThat(allocationId).isNotEqualTo(firstAllocationId);
        assertThat(world.api(SourcingApi.class).getAllocation(allocationId).getStatus())
                .isEqualTo("OFFERED");
        List<Offer> offers = world.api(SourcingApi.class).listOffers(allocationId);
        assertThat(offers).hasSize(1);
        assertThat(offers.get(0).getVendorPartnerId()).isEqualTo(vendors.get(vendor));
    }

    @Then("the sourcing-lifecycle first allocation is still listed as cancelled")
    public void firstStillCancelled() {
        assertThat(world.api(SourcingApi.class).getAllocation(firstAllocationId).getStatus())
                .isEqualTo("CANCELLED");
        assertThat(world.api(SourcingApi.class).listAllocations("CANCELLED", null, 200).getItems())
                .extracting(Allocation::getId).contains(firstAllocationId);
    }

    @Then("the sourcing-lifecycle offer to {string} ended as {word}")
    public void offerOutcome(String vendor, String outcome) {
        // The contract has no outcome on an offer; the wire carries it.
        JsonNode wire = support.get("/api/v1/allocations/" + allocationId + "/offers",
                support.demoAdministratorToken()).json();
        assertThat(wire.size()).isEqualTo(1);
        assertThat(wire.get(0).path("vendorPartnerId").asString())
                .isEqualTo(vendors.get(vendor).toString());
        assertThat(wire.get(0).path("outcome").asString()).isEqualTo(outcome);
    }

    @Then("cancelling the sourcing-lifecycle allocation is refused with {string}")
    public void cancelRefused(String code) {
        assertProblem(() -> world.api(SourcingApi.class).cancelAllocation(allocationId, null), 409, code);
    }

    @Then("cancelling the sourcing-lifecycle order is refused with {string}")
    public void orderCancelRefused(String code) {
        assertProblem(() -> world.api(OrdersApi.class).cancelOrder(orderId, null,
                new CancelOrderRequest()), 409, code);
    }

    private void offer() {
        allocationId = world.api(SourcingApi.class)
                .allocateLoad(new AllocateRequest().loadId(loadId), null).getId();
    }
}
