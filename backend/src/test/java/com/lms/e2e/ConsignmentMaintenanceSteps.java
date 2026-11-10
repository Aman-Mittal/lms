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
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import com.lms.client.api.OrdersApi;
import com.lms.client.api.PlanningApi;
import com.lms.client.api.WebhooksApi;
import com.lms.client.model.AddOrderLineRequest;
import com.lms.client.model.AssignConsignmentRequest;
import com.lms.client.model.BulkCancelConsignmentsRequest;
import com.lms.client.model.CancelConsignmentRequest;
import com.lms.client.model.CancelOrderRequest;
import com.lms.client.model.Consignment;
import com.lms.client.model.ConsignmentAmended;
import com.lms.client.model.ConsignmentAmendmentRequest;
import com.lms.client.model.ConsignmentBulkResult;
import com.lms.client.model.ConsignmentCancellation;
import com.lms.client.model.ConsignmentItemResult;
import com.lms.client.model.HistoryEntry;
import com.lms.client.model.IdResponse;
import com.lms.client.model.OpenLoadRequest;
import com.lms.client.model.OrderLine;
import com.lms.client.model.RaiseOrderRequest;
import com.lms.client.model.RegisterPartnerRequest;
import com.lms.client.model.RegisterPointRadiusTerminalRequest;
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
 * A consignment's particulars amended, and a consignment or an order line
 * cancelled on its own, over HTTP. Latitude band 34 belongs to this feature
 * alone.
 */
public class ConsignmentMaintenanceSteps {

    @Value("${local.server.port}")
    private int port;

    private HttpWorld world;
    private UUID origin;
    private UUID orderId;
    private UUID first;
    private UUID second;
    private UUID load;
    private long amendedVersion;
    private int refusedStatus;
    private String refusedBody;
    private FeignException refusal;
    private ConsignmentBulkResult bulk;
    private WebhookReceiver receiver;
    private UUID subscriptionId;
    private String subscriptionName;

    private PlanningApi planning() {
        return world.api(PlanningApi.class);
    }

    private OrdersApi orders() {
        return world.api(OrdersApi.class);
    }

    @Given("the demo administrator is signed in to maintain consignments")
    public void signIn() {
        world = new HttpWorld(port);
        world.signInAsDemoAdministrator();
    }

    @Given("an order for two consignees has been cut into two consignments")
    public void twoConsignments() {
        var site = RegisterPointRadiusTerminalRequest.CategoryEnum.CUSTOMER_SITE;
        origin = world.terminal(site, "CM-ORIGIN", 34.10, 81.10);
        UUID destination = world.terminal(site, "CM-DEST", 34.30, 81.30);
        UUID customer = world.partner("CMC", RegisterPartnerRequest.PartnerTypeEnum.CUSTOMER);
        UUID one = world.partner("CMA", RegisterPartnerRequest.PartnerTypeEnum.CONSIGNEE);
        UUID two = world.partner("CMB", RegisterPartnerRequest.PartnerTypeEnum.CONSIGNEE);

        orderId = orders().raiseOrder(new RaiseOrderRequest()
                .orgUnitId(world.orgUnitId())
                .customerPartnerId(customer)
                .orderNo("SO-CM-" + world.salt)
                .originTerminalId(origin)
                .requestedPickupAt(OffsetDateTime.now(ZoneOffset.UTC).plusHours(4))
                .source(RaiseOrderRequest.SourceEnum.API), null).getId();
        orders().addOrderLine(orderId, line(1, "4000", one, destination), null);
        orders().addOrderLine(orderId, line(2, "6000", two, destination), null);
        orders().validateOrder(orderId, null);

        List<IdResponse> cut = planning().generateConsignments(orderId, null);
        assertThat(cut).hasSize(2);
        // Which consignee each went to, not the order they were returned in.
        for (IdResponse id : cut) {
            Consignment c = planning().getConsignment(id.getId());
            if (c.getTotalDeadWeightKg().compareTo(new BigDecimal("4000")) == 0) {
                first = c.getId();
            } else {
                second = c.getId();
            }
        }
        assertThat(first).isNotNull();
        assertThat(second).isNotNull();
    }

    private static AddOrderLineRequest line(int lineNo, String weightKg, UUID consignee, UUID destination) {
        return new AddOrderLineRequest().lineNo(lineNo).materialCode("CM-" + lineNo)
                .materialDescription("Cartons").materialClass("GENERAL")
                .quantity(BigDecimal.TEN).uom("CTN").deadWeightKg(new BigDecimal(weightKg))
                .consigneePartnerId(consignee).destinationTerminalId(destination);
    }

    // ------------------------------------------------------------- amending

    @When("the planner gives the first consignment a contact, a client reference and a delivery window")
    public void amendFirst() {
        long version = planning().getConsignment(first).getVersion();
        ConsignmentAmended amended = planning().amendConsignment(String.valueOf(version), first,
                new ConsignmentAmendmentRequest().contactName("Meera, stores").contactPhone("+91 22 4000 1234")
                        .clientReference("CM-" + world.salt).deliveryWindowFrom("2026-12-01T04:00:00Z")
                        .deliveryWindowTo("2026-12-01T12:00:00Z"), null);
        assertThat(amended.getFields()).extracting(f -> f.getOutcome().getValue()).containsOnly("APPLIED");
        assertThat(amended.getVersion()).isGreaterThan(version);
        amendedVersion = amended.getVersion();
    }

    @Then("the consignment reads back with them, at a new version")
    public void readsBack() {
        Consignment read = planning().getConsignment(first);
        assertThat(read.getVersion()).isEqualTo(amendedVersion);
        assertThat(read.getContactName()).isEqualTo("Meera, stores");
        assertThat(read.getClientReference()).isEqualTo("CM-" + world.salt);
        assertThat(read.getDeliveryWindowFrom()).isNotNull();
        assertThat(read.getTotalDeadWeightKg()).isEqualByComparingTo("4000");
    }

    @Then("its history shows the amendment")
    public void historyShowsAmendment() {
        assertThat(planning().getConsignmentHistory(first, null, null).getItems())
                .extracting(HistoryEntry::getAction).first().isEqualTo("CONSIGNMENT_AMENDED");
    }

    @When("the planner tries to change the first consignment's weight")
    public void changeWeight() throws IOException, InterruptedException {
        long version = planning().getConsignment(first).getVersion();
        try (HttpClient http = HttpClient.newHttpClient()) {
            HttpResponse<String> response = http.send(HttpRequest.newBuilder(
                            URI.create("http://localhost:" + port + "/api/v1/consignments/" + first))
                    .header("Authorization", "Bearer " + world.accessToken())
                    .header("Content-Type", "application/json")
                    .header("If-Match", "\"" + version + "\"")
                    .method("PATCH", HttpRequest.BodyPublishers.ofString("{\"totalDeadWeightKg\":3500}"))
                    .build(), HttpResponse.BodyHandlers.ofString());
            refusedStatus = response.statusCode();
            refusedBody = response.body();
        }
    }

    @Then("the amendment is refused as {string} naming {string}")
    public void amendmentRefused(String code, String field) {
        assertThat(refusedStatus).isEqualTo(409);
        assertThat(refusedBody).contains("\"code\":\"" + code + "\"").contains("\"" + field + "\"");
        assertThat(planning().getConsignment(first).getTotalDeadWeightKg()).isEqualByComparingTo("4000");
    }

    // ----------------------------------------------------------- cancelling

    @Given("both consignments are on one load")
    public void bothOnOneLoad() {
        UUID lorry = world.lorry("CM", 20000);
        load = planning().openLoad(new OpenLoadRequest()
                .orgUnitId(world.orgUnitId())
                .loadNo("LD-CM-" + world.salt)
                .originTerminalId(origin)
                .vehicleId(lorry), null).getId();
        planning().assignConsignment(load, new AssignConsignmentRequest().consignmentId(first), null);
        planning().assignConsignment(load, new AssignConsignmentRequest().consignmentId(second), null);
        assertThat(planning().getLoad(load).getPlannedWeightKg()).isEqualByComparingTo("10000");
    }

    @Given("a webhook receiver is listening for consignment cancellations")
    public void listen() throws IOException {
        receiver = new WebhookReceiver();
        subscriptionName = "e2e-cancel-" + world.salt;
        subscriptionId = world.api(WebhooksApi.class).subscribeWebhook(new WebhookSubscriptionRequest()
                .name(subscriptionName).url(receiver.url())
                .eventTypes(List.of(WebhookEventType.CONSIGNMENT_CANCELLED)), null).getId();
    }

    @When("the planner cancels the first consignment because {string}")
    public void cancelFirst(String reason) {
        ConsignmentCancellation done = planning().cancelConsignment(first, null,
                new CancelConsignmentRequest().reason(reason));
        assertThat(done.getLoad()).isNotNull();
        assertThat(done.getLoad().getLoadId()).isEqualTo(load);
    }

    @Then("the load carries only the second consignment's weight")
    public void loadShrinks() {
        assertThat(planning().getLoad(load).getPlannedWeightKg()).isEqualByComparingTo("6000");
        assertThat(planning().listLoadConsignments(load)).extracting(Consignment::getId).containsExactly(second);
        assertThat(planning().getConsignment(first).getStatus()).isEqualTo("CANCELLED");
    }

    @Then("the order is partially planned, with the first consignment's line waiting again")
    public void orderStepsBack() {
        assertThat(orders().getOrder(orderId).getStatus()).isEqualTo("PARTIALLY_PLANNED");
        assertThat(orders().listOrderLines(orderId)).extracting(OrderLine::getLineNo, OrderLine::getPlanned)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(1, false),
                        org.assertj.core.groups.Tuple.tuple(2, true));
    }

    @Then("the receiver is told of the cancellation with the reason")
    public void toldOfCancellation() {
        WebhookReceiver.Received r = HttpWorld.eventually("the cancellation reaches the receiver", () -> {
            List<WebhookReceiver.Received> matching = receiver.received().stream()
                    .filter(x -> "CONSIGNMENT_CANCELLED".equals(x.headers().get("x-lms-event"))).toList();
            assertThat(matching).isNotEmpty();
            return matching.get(matching.size() - 1);
        });
        assertThat(r.body()).contains(first.toString()).contains("Consignee closed for stocktake")
                .contains(load.toString()).doesNotContain("tenant");
    }

    @When("the planner cancels the first consignment again")
    public void cancelAgain() {
        refusal = null;
        try {
            planning().cancelConsignment(first, null, new CancelConsignmentRequest());
        } catch (FeignException e) {
            refusal = e;
        }
    }

    @Then("the cancellation is refused as {string}")
    public void cancellationRefused(String code) {
        assertThat(refusal).as("the cancellation was refused").isNotNull();
        assertThat(refusal.status()).isEqualTo(409);
        assertThat(refusal.contentUTF8()).contains(code);
    }

    @When("the order is planned again")
    public void planAgain() {
        List<IdResponse> cut = planning().generateConsignments(orderId, null);
        assertThat(cut).hasSize(1);
        second = cut.get(0).getId();
    }

    @Then("a new consignment carries the freed line")
    public void newConsignment() {
        Consignment fresh = planning().getConsignment(second);
        assertThat(fresh.getId()).isNotEqualTo(first);
        assertThat(fresh.getTotalDeadWeightKg()).isEqualByComparingTo("4000");
        assertThat(orders().getOrder(orderId).getStatus()).isEqualTo("FULLY_PLANNED");
    }

    // ---------------------------------------------------------- order lines

    @When("the planner cancels the order's first line")
    public void cancelFirstLine() {
        OrderLine line = orders().listOrderLines(orderId).getFirst();
        long version = orders().getOrder(orderId).getVersion();
        var done = orders().cancelOrderLine(String.valueOf(version), orderId, line.getId(), null,
                new CancelOrderRequest().reason("Customer no longer needs it"));
        assertThat(done.getCancelledConsignmentIds()).containsExactly(first);
        assertThat(done.getReleasedLineIds()).isEmpty();
    }

    @Then("the first consignment is cancelled and the second is untouched")
    public void firstCancelledSecondKept() {
        assertThat(planning().getConsignment(first).getStatus()).isEqualTo("CANCELLED");
        assertThat(planning().getConsignment(second).getStatus()).isEqualTo("PLANNED");
    }

    @Then("the order counts one line")
    public void oneLine() {
        assertThat(orders().getOrder(orderId).getLineCount()).isEqualTo(1);
        assertThat(orders().getOrder(orderId).getStatus()).isEqualTo("FULLY_PLANNED");
    }

    // ----------------------------------------------------------------- bulk

    @Given("the second consignment has already been cancelled")
    public void secondCancelled() {
        planning().cancelConsignment(second, null, new CancelConsignmentRequest());
    }

    @When("the planner cancels both consignments together")
    public void cancelBoth() {
        bulk = planning().cancelConsignments(new BulkCancelConsignmentsRequest()
                .consignmentIds(List.of(first, second)).reason("Season over"), null);
    }

    @Then("the first is cancelled and the second is refused as {string}")
    public void perItem(String code) {
        assertThat(bulk.getItems()).extracting(ConsignmentItemResult::getConsignmentId)
                .containsExactly(first, second);
        assertThat(bulk.getItems()).extracting(i -> i.getOutcome().getValue())
                .containsExactly("CANCELLED", "REFUSED");
        assertThat(bulk.getItems().get(1).getCode()).isEqualTo(code);
        assertThat(planning().getConsignment(first).getStatus()).isEqualTo("CANCELLED");
    }

    @After("@consignment_maintenance")
    public void switchOff() {
        if (subscriptionId != null) {
            world.api(WebhooksApi.class).reviseWebhookSubscription(subscriptionId, new WebhookRevisionRequest()
                    .name(subscriptionName).url(receiver.url())
                    .eventTypes(List.of(WebhookEventType.CONSIGNMENT_CANCELLED)).active(false), null);
            subscriptionId = null;
        }
        if (receiver != null) {
            receiver.close();
            receiver = null;
        }
    }
}
