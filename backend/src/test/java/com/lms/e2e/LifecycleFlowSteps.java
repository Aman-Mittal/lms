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
import java.util.List;
import java.util.UUID;

import com.lms.client.api.OrdersApi;
import com.lms.client.api.PlanningApi;
import com.lms.client.api.SourcingApi;
import com.lms.client.api.TripsApi;
import com.lms.client.model.AddOrderLineRequest;
import com.lms.client.model.AddVendorRequest;
import com.lms.client.model.AllocateRequest;
import com.lms.client.model.AssignConsignmentRequest;
import com.lms.client.model.AssignTripRequest;
import com.lms.client.model.CancelOrderRequest;
import com.lms.client.model.DefineGuideRequest;
import com.lms.client.model.IdResponse;
import com.lms.client.model.OpenLoadRequest;
import com.lms.client.model.RaiseOrderRequest;
import com.lms.client.model.RaiseTripRequest;
import com.lms.client.model.RegisterPartnerRequest;
import com.lms.client.model.RegisterPointRadiusTerminalRequest;
import com.lms.client.model.VendorResponseRequest;
import feign.FeignException;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import org.springframework.beans.factory.annotation.Value;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Trip completion and order cancellation over HTTP.
 *
 * <p>Keeps to its own latitude band (30 degrees north), so that its terminals
 * cannot overlap another mode's. Every step text carries "lifecycle", since
 * step expressions must be unique across all step classes.
 */
public class LifecycleFlowSteps {

    @Value("${local.server.port}")
    private int port;

    private HttpWorld world;
    private UUID customer;
    private UUID consignee;
    private UUID vendor;
    private UUID origin;
    private UUID destination;
    private UUID lorry;
    private UUID driver;

    private UUID orderId;
    private UUID consignmentId;
    private UUID loadId;
    private UUID tripId;
    private FeignException refusal;

    @Given("the demo administrator is signed in at the lifecycle desk")
    public void signIn() {
        world = new HttpWorld(port);
        world.signInAsDemoAdministrator();
    }

    @Given("a lifecycle customer, consignee, vendor, two depots and a lorry are registered")
    public void parties() {
        customer = world.partner("LCC", RegisterPartnerRequest.PartnerTypeEnum.CUSTOMER);
        consignee = world.partner("LCN", RegisterPartnerRequest.PartnerTypeEnum.CONSIGNEE);
        vendor = world.activeVendor("LCV");
        origin = world.terminal(RegisterPointRadiusTerminalRequest.CategoryEnum.CUSTOMER_SITE,
                "LC-ORIGIN", 30.10, 72.90);
        destination = world.terminal(RegisterPointRadiusTerminalRequest.CategoryEnum.CUSTOMER_SITE,
                "LC-DEST", 30.30, 73.10);
        lorry = world.lorry("LC", 20000);
        driver = world.driver("LC");
    }

    @When("a lifecycle order of {int} kg is planned onto a load")
    public void plannedOrder(int weightKg) {
        OrdersApi orders = world.api(OrdersApi.class);
        orderId = orders.raiseOrder(new RaiseOrderRequest()
                .orgUnitId(world.orgUnitId())
                .customerPartnerId(customer)
                .orderNo(world.code("SO-LC"))
                .originTerminalId(origin)
                .requestedPickupAt(OffsetDateTime.now(ZoneOffset.UTC).plusHours(4))
                .source(RaiseOrderRequest.SourceEnum.API), null).getId();
        orders.addOrderLine(orderId, new AddOrderLineRequest()
                .lineNo(1)
                .materialCode("GOODS")
                .materialDescription("General goods")
                .materialClass("GENERAL")
                .quantity(new BigDecimal("10"))
                .uom("CASE")
                .deadWeightKg(BigDecimal.valueOf(weightKg))
                .consigneePartnerId(consignee)
                .destinationTerminalId(destination), null);
        orders.validateOrder(orderId, null);

        PlanningApi planning = world.api(PlanningApi.class);
        List<IdResponse> consignments = planning.generateConsignments(orderId, null);
        assertThat(consignments).hasSize(1);
        consignmentId = consignments.get(0).getId();
        loadId = planning.openLoad(new OpenLoadRequest()
                .orgUnitId(world.orgUnitId())
                .loadNo(world.code("LD-LC"))
                .originTerminalId(origin)
                .vehicleId(lorry), null).getId();
        planning.assignConsignment(loadId,
                new AssignConsignmentRequest().consignmentId(consignmentId), null);
        planning.planLoad(loadId, null);
    }

    @When("a lifecycle order of {int} kg is planned onto a load, awarded and dispatched")
    public void dispatchedOrder(int weightKg) {
        plannedOrder(weightKg);

        SourcingApi sourcing = world.api(SourcingApi.class);
        UUID guideId = sourcing.defineRoutingGuide(new DefineGuideRequest()
                .orgUnitId(world.orgUnitId())
                .originTerminalId(origin)
                .destinationTerminalId(destination)
                .vehicleType(HttpWorld.VEHICLE_TYPE), null).getId();
        sourcing.addRoutingGuideVendor(guideId, new AddVendorRequest()
                .vendorPartnerId(vendor).rankNo(1).responseSlaMinutes(60)
                .agreedRate(new BigDecimal("20000")), null);
        UUID allocationId = sourcing.allocateLoad(new AllocateRequest().loadId(loadId), null).getId();
        sourcing.acceptOffer(allocationId, new VendorResponseRequest().vendorPartnerId(vendor), null);

        TripsApi trips = world.api(TripsApi.class);
        tripId = trips.raiseTrip(new RaiseTripRequest().tripNo(world.code("TR-LC"))
                .loadId(loadId).plannedStartAt(OffsetDateTime.now(ZoneOffset.UTC).minusHours(60)),
                null).getId();
        trips.assignTrip(tripId, new AssignTripRequest().vehicleId(lorry).driverId(driver), null);
        world.dispatch(tripId, origin, weightKg, OffsetDateTime.now(ZoneOffset.UTC).minusHours(3));
    }

    @When("the lifecycle trip is completed")
    public void tripCompleted() {
        world.deliver(tripId, OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(5));
    }

    @When("the lifecycle order is cancelled for the reason {string}")
    public void cancelWithReason(String reason) {
        cancel(new CancelOrderRequest().reason(reason));
    }

    @When("the lifecycle order is cancelled without a reason")
    public void cancelWithoutReason() {
        cancel(new CancelOrderRequest());
    }

    @Then("the lifecycle order is {string} and its consignment is {string}")
    public void orderAndConsignment(String orderStatus, String consignmentStatus) {
        assertThat(world.api(OrdersApi.class).getOrder(orderId).getStatus()).isEqualTo(orderStatus);
        assertThat(world.api(PlanningApi.class).getConsignment(consignmentId).getStatus())
                .isEqualTo(consignmentStatus);
    }

    @Then("the lifecycle load is {string}")
    public void loadIs(String status) {
        assertThat(world.api(PlanningApi.class).getLoad(loadId).getStatus()).isEqualTo(status);
    }

    @Then("the lifecycle cancellation is refused with {string}")
    public void refused(String code) {
        assertThat(refusal).as("the cancellation should have been refused").isNotNull();
        assertThat(refusal.status()).isEqualTo(409);
        assertThat(refusal.contentUTF8()).contains(code);
    }

    @Then("cancelling the lifecycle order again changes nothing")
    public void cancelAgain() {
        cancel(new CancelOrderRequest());
        assertThat(refusal).as("a repeat cancellation is a no-op, not a refusal").isNull();
        assertThat(world.api(OrdersApi.class).getOrder(orderId).getStatus()).isEqualTo("CANCELLED");
    }

    private void cancel(CancelOrderRequest body) {
        refusal = null;
        try {
            world.api(OrdersApi.class).cancelOrder(orderId, null, body);
        } catch (FeignException e) {
            refusal = e;
        }
    }
}
