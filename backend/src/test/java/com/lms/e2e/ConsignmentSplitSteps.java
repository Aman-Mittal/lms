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

import java.io.IOException;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import com.lms.client.api.OrdersApi;
import com.lms.client.api.PlanningApi;
import com.lms.client.api.PublicTrackingApi;
import com.lms.client.api.SourcingApi;
import com.lms.client.api.TrackingLinksApi;
import com.lms.client.api.TripsApi;
import com.lms.client.api.WebhooksApi;
import com.lms.client.model.AddOrderLineRequest;
import com.lms.client.model.AddVendorRequest;
import com.lms.client.model.AllocateRequest;
import com.lms.client.model.AssignConsignmentRequest;
import com.lms.client.model.AssignTripRequest;
import com.lms.client.model.Consignment;
import com.lms.client.model.ConsignmentPart;
import com.lms.client.model.ConsignmentSlice;
import com.lms.client.model.DefineGuideRequest;
import com.lms.client.model.IdResponse;
import com.lms.client.model.IssueTrackingLinkRequest;
import com.lms.client.model.OpenLoadRequest;
import com.lms.client.model.PublicTracking;
import com.lms.client.model.RaiseOrderRequest;
import com.lms.client.model.RaiseTripRequest;
import com.lms.client.model.RegisterPartnerRequest;
import com.lms.client.model.RegisterPointRadiusTerminalRequest;
import com.lms.client.model.SplitConsignmentRequest;
import com.lms.client.model.SplitPartRequest;
import com.lms.client.model.VendorResponseRequest;
import com.lms.client.model.WebhookEventType;
import com.lms.client.model.WebhookRevisionRequest;
import com.lms.client.model.WebhookSubscriptionRequest;
import com.lms.webhooks.WebhookReceiver;
import feign.FeignException;
import io.cucumber.java.After;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import org.springframework.beans.factory.annotation.Value;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A consignment split across vehicles, over HTTP, with the parts loaded, and
 * some of them dispatched and delivered by real trips raised through the API.
 *
 * <p>Latitude band 24 belongs to this feature alone. Most of the hundred loads
 * share one planning lorry, since a load is planned against a vehicle without
 * holding it; the three that leave each get a lorry and a driver of their own.
 */
public class ConsignmentSplitSteps {

    private static final BigDecimal TONNE = new BigDecimal("1000");

    @Value("${local.server.port}")
    private int port;

    private HttpWorld world;
    private UUID origin;
    private UUID destination;
    private UUID vendor;
    private UUID orderId;
    private UUID whole;
    private String wholeRef;
    private UUID planningLorry;
    private List<ConsignmentPart> parts = List.of();
    private final List<UUID> loads = new ArrayList<>();
    private FeignException refusal;
    private WebhookReceiver receiver;
    private UUID subscriptionId;
    private String subscriptionName;

    private PlanningApi planning() {
        return world.api(PlanningApi.class);
    }

    @Given("the demo administrator is signed in to split consignments")
    public void signIn() {
        world = new HttpWorld(port);
        world.signInAsDemoAdministrator();
    }

    @Given("an order for {int} tonnes of coil to one consignee has been cut into one consignment")
    public void bulkOrder(int tonnes) {
        var site = RegisterPointRadiusTerminalRequest.CategoryEnum.CUSTOMER_SITE;
        origin = world.terminal(site, "SP-ORIGIN", 24.10, 72.90);
        destination = world.terminal(site, "SP-DEST", 24.30, 73.10);
        vendor = world.activeVendor("SPV");
        UUID customer = world.partner("SPC", RegisterPartnerRequest.PartnerTypeEnum.CUSTOMER);
        UUID consignee = world.partner("SPN", RegisterPartnerRequest.PartnerTypeEnum.CONSIGNEE);
        planningLorry = world.lorry("SP", 20000);

        OrdersApi orders = world.api(OrdersApi.class);
        orderId = orders.raiseOrder(new RaiseOrderRequest()
                .orgUnitId(world.orgUnitId())
                .customerPartnerId(customer)
                .orderNo("SO-SP-" + world.salt)
                .originTerminalId(origin)
                .requestedPickupAt(OffsetDateTime.now(ZoneOffset.UTC).plusHours(4))
                .source(RaiseOrderRequest.SourceEnum.API), null).getId();
        orders.addOrderLine(orderId, new AddOrderLineRequest()
                .lineNo(1)
                .materialCode("COIL")
                .materialDescription("Hot-rolled coil")
                .materialClass("GENERAL")
                .quantity(new BigDecimal("100"))
                .uom("COIL")
                .deadWeightKg(TONNE.multiply(BigDecimal.valueOf(tonnes)))
                .consigneePartnerId(consignee)
                .destinationTerminalId(destination), null);
        orders.validateOrder(orderId, null);

        List<IdResponse> cut = planning().generateConsignments(orderId, null);
        assertThat(cut).as("one consignee and one destination make one consignment").hasSize(1);
        whole = cut.get(0).getId();
        Consignment read = planning().getConsignment(whole);
        wholeRef = read.getTrackingRef();
        assertThat(read.getTotalDeadWeightKg()).isEqualByComparingTo(TONNE.multiply(BigDecimal.valueOf(tonnes)));
    }

    // ------------------------------------------------------------ splitting

    @Given("no lorry can take the whole consignment")
    public void noLorryTakesTheWhole() {
        UUID load = openLoad("LD-SP-WHOLE", planningLorry);
        FeignException e = refused(() -> planning().assignConsignment(load,
                new AssignConsignmentRequest().consignmentId(whole), null));
        assertThat(e.status()).isEqualTo(409);
        assertThat(e.contentUTF8()).contains("load-capacity-exceeded");
    }

    @When("the planner splits it into {int} parts of {int} tonnes")
    public void splitEvenly(int count, int tonnes) {
        split(Collections.nCopies(count, part(TONNE.multiply(BigDecimal.valueOf(tonnes)))));
    }

    @When("the planner splits it into parts weighing {int} tonnes and {bigdecimal} tonnes")
    public void splitUnevenly(int first, BigDecimal second) {
        split(List.of(part(TONNE.multiply(BigDecimal.valueOf(first))), part(TONNE.multiply(second))));
    }

    private static SplitPartRequest part(BigDecimal weightKg) {
        return new SplitPartRequest().deadWeightKg(weightKg).volumeM3(BigDecimal.ZERO);
    }

    private void split(List<SplitPartRequest> requested) {
        refusal = null;
        try {
            parts = planning().splitConsignment(whole, new SplitConsignmentRequest().parts(requested), null);
        } catch (FeignException e) {
            refusal = e;
        }
    }

    @Then("the parts are numbered {string} to {string} under the consignment's reference")
    public void numbered(String first, String last) {
        assertThat(refusal).as("the split was accepted").isNull();
        assertThat(parts.get(0).getTrackingRef()).isEqualTo(wholeRef + first);
        assertThat(parts.get(parts.size() - 1).getTrackingRef()).isEqualTo(wholeRef + last);
        assertThat(parts).extracting(ConsignmentPart::getTrackingRef).doesNotHaveDuplicates();
    }

    @Then("every part is on a load of its own, {int} loads in all")
    public void eachOnItsOwnLoad(int count) {
        for (int i = 0; i < parts.size(); i++) {
            UUID load = openLoad("LD-SP-" + i, planningLorry);
            planning().assignConsignment(load,
                    new AssignConsignmentRequest().consignmentId(parts.get(i).getId()), null);
            planning().planLoad(load, null);
            loads.add(load);
        }
        assertThat(loads).hasSize(count).doesNotHaveDuplicates();
        assertThat(planning().listLoadConsignments(loads.get(count - 1)))
                .singleElement().satisfies(c -> {
                    assertThat(c.getTrackingRef()).isEqualTo(parts.get(count - 1).getTrackingRef());
                    assertThat(c.getParentConsignmentId()).isEqualTo(whole);
                    assertThat(c.getParentTrackingRef()).isEqualTo(wholeRef);
                });
    }

    @When("{int} of the parts are carried by trips that are dispatched and delivered")
    public void carried(int count) {
        SourcingApi sourcing = world.api(SourcingApi.class);
        UUID guide = sourcing.defineRoutingGuide(new DefineGuideRequest()
                .orgUnitId(world.orgUnitId())
                .originTerminalId(origin)
                .destinationTerminalId(destination)
                .vehicleType(HttpWorld.VEHICLE_TYPE), null).getId();
        sourcing.addRoutingGuideVendor(guide, new AddVendorRequest()
                .vendorPartnerId(vendor).rankNo(1).responseSlaMinutes(60)
                .agreedRate(new BigDecimal("20000")), null);

        TripsApi trips = world.api(TripsApi.class);
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        for (int i = 0; i < count; i++) {
            UUID allocation = sourcing.allocateLoad(new AllocateRequest().loadId(loads.get(i)), null).getId();
            sourcing.acceptOffer(allocation, new VendorResponseRequest().vendorPartnerId(vendor), null);
            UUID lorry = world.lorry("SP" + i, 20000);
            UUID driver = world.driver("SP" + i);
            UUID trip = trips.raiseTrip(new RaiseTripRequest().tripNo("TR-SP-" + i + "-" + world.salt)
                    .loadId(loads.get(i)).plannedStartAt(now.minusHours(60)), null).getId();
            trips.assignTrip(trip, new AssignTripRequest().vehicleId(lorry).driverId(driver), null);
            world.dispatch(trip, origin, 20000, now.minusHours(6));
            world.deliver(trip, now.minusHours(1));
        }
    }

    @Then("the consignment reads split, with {int} of {int} parts delivered")
    public void readsSplit(int delivered, int count) {
        Consignment read = HttpWorld.eventually("the parts' deliveries are counted", () -> {
            Consignment c = planning().getConsignment(whole);
            assertThat(c.getPartsDelivered()).isEqualTo(delivered);
            return c;
        });
        assertThat(read.getStatus()).isEqualTo("SPLIT");
        assertThat(read.getPartCount()).isEqualTo(count);
        assertThat(read.getLoadNo()).as("the whole rides on nothing").isNull();

        ConsignmentSlice firstPage = planning().listConsignmentParts(whole, null, 200, null);
        assertThat(firstPage.getItems()).hasSize(count);
        assertThat(firstPage.getHasMore()).isFalse();
        assertThat(firstPage.getItems().stream().filter(p -> "DELIVERED".equals(p.getStatus())))
                .hasSize(delivered);
    }

    @Then("a customer's tracking link on the consignment shows it in transit with {int} of {int} parts delivered")
    public void customerSeesProgress(int delivered, int count) {
        String token = world.api(TrackingLinksApi.class)
                .issueTrackingLink(whole, null, new IssueTrackingLinkRequest()).getToken();
        PublicTracking view = world.client(null, PublicTrackingApi.class).getPublicTracking(token);
        assertThat(view.getReference()).isEqualTo(wholeRef);
        assertThat(view.getStatus()).isEqualTo(PublicTracking.StatusEnum.IN_TRANSIT);
        assertThat(view.getPartsTotal()).isEqualTo(count);
        assertThat(view.getPartsDelivered()).isEqualTo(delivered);
        assertThat(view.getPosition()).as("the parts are on different vehicles").isNull();
    }

    @Then("the order is not yet fulfilled")
    public void notFulfilled() {
        assertThat(world.api(OrdersApi.class).getOrder(orderId).getStatus()).isNotEqualTo("FULFILLED");
    }

    // ------------------------------------------------------------- refusals

    @Then("the split is refused as {string} naming a difference of {string} kg")
    public void refusedWithDifference(String code, String difference) {
        refusedAs(code);
        assertThat(refusal.contentUTF8()).contains("a difference of " + difference + " kg");
    }

    @Then("the split is refused as {string}")
    public void refusedAs(String code) {
        assertThat(refusal).as("the split was refused").isNotNull();
        assertThat(refusal.status()).isEqualTo(409);
        assertThat(refusal.contentUTF8()).contains(code);
    }

    @Then("the consignment is still planned with no parts")
    public void stillPlanned() {
        assertThat(planning().getConsignment(whole).getStatus()).isEqualTo("PLANNED");
        assertThat(planning().listConsignmentParts(whole, null, null, null).getItems()).isEmpty();
    }

    @Then("the parts of a consignment nobody created are not found")
    public void partsOfNothing() {
        FeignException e = refused(() -> planning().listConsignmentParts(UUID.randomUUID(), null, null, null));
        assertThat(e.status()).isEqualTo(404);
        assertThat(e.contentUTF8()).contains("not-found");
    }

    // ------------------------------------------------------------- webhooks

    @Given("a webhook receiver is listening for consignment splits")
    public void listen() throws IOException {
        receiver = new WebhookReceiver();
        subscriptionName = "e2e-split-" + world.salt;
        subscriptionId = world.api(WebhooksApi.class).subscribeWebhook(new WebhookSubscriptionRequest()
                .name(subscriptionName).url(receiver.url())
                .eventTypes(List.of(WebhookEventType.CONSIGNMENT_SPLIT)), null).getId();
    }

    @Then("the receiver is told of the split with both part references")
    public void toldOfSplit() {
        WebhookReceiver.Received r = HttpWorld.eventually("the split reaches the receiver", () -> {
            List<WebhookReceiver.Received> matching = receiver.received().stream()
                    .filter(x -> "CONSIGNMENT_SPLIT".equals(x.headers().get("x-lms-event"))).toList();
            assertThat(matching).isNotEmpty();
            return matching.get(matching.size() - 1);
        });
        assertThat(r.body()).contains(whole.toString()).contains(wholeRef + "-01").contains(wholeRef + "-02")
                .doesNotContain("tenant");
    }

    @After("@consignment_split")
    public void switchOff() {
        if (subscriptionId != null) {
            world.api(WebhooksApi.class).reviseWebhookSubscription(subscriptionId, new WebhookRevisionRequest()
                    .name(subscriptionName).url(receiver.url())
                    .eventTypes(List.of(WebhookEventType.CONSIGNMENT_SPLIT)).active(false), null);
        }
        if (receiver != null) {
            receiver.close();
        }
    }

    // --------------------------------------------------------------- helpers

    private UUID openLoad(String loadNo, UUID vehicle) {
        return planning().openLoad(new OpenLoadRequest()
                .orgUnitId(world.orgUnitId())
                .loadNo(loadNo + "-" + world.salt)
                .originTerminalId(origin)
                .vehicleId(vehicle), null).getId();
    }

    private static FeignException refused(Runnable call) {
        try {
            call.run();
        } catch (FeignException e) {
            return e;
        }
        throw new AssertionError("the request was accepted");
    }
}
