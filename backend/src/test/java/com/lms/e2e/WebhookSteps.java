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
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import com.lms.client.api.WebhooksApi;
import com.lms.client.model.RegisterPartnerRequest;
import com.lms.client.model.RegisterPointRadiusTerminalRequest;
import com.lms.client.model.WebhookDelivery;
import com.lms.client.model.WebhookEventType;
import com.lms.client.model.WebhookRevisionRequest;
import com.lms.client.model.WebhookSecretIssued;
import com.lms.client.model.WebhookSubscriptionRequest;
import com.lms.webhooks.WebhookReceiver;
import com.lms.webhooks.command.WebhookSigner;
import io.cucumber.java.After;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Outbound webhooks over HTTP: a subscription made with the generated client,
 * deliveries posted by the platform's own timer to a local receiver, and the
 * signature checked the way an integrator would check it.
 *
 * <p>Latitude band 27 belongs to this feature alone. Each scenario switches its
 * subscription off afterwards, so later scenarios' events are not sent to a
 * receiver that has gone.
 */
public class WebhookSteps {

    @Value("${local.server.port}")
    private int port;
    @Autowired
    private JdbcClient jdbc;

    private HttpWorld world;
    private WebhookReceiver receiver;
    private UUID subscriptionId;
    private String subscriptionName;
    private String secret;
    private UUID pingId;
    private UUID tripId;
    private String tripNo;

    private WebhooksApi webhooks() {
        return world.api(WebhooksApi.class);
    }

    @Given("the demo administrator is signed in to manage webhooks")
    public void signIn() {
        world = new HttpWorld(port);
        world.signInAsDemoAdministrator();
    }

    @Given("a webhook receiver is listening")
    public void listen() throws IOException {
        receiver = new WebhookReceiver();
    }

    @Given("a webhook subscription to trip events points at the receiver")
    public void subscribe() {
        subscriptionName = "e2e-" + world.salt;
        WebhookSecretIssued issued = webhooks().subscribeWebhook(new WebhookSubscriptionRequest()
                .name(subscriptionName).url(receiver.url())
                .eventTypes(List.of(WebhookEventType.TRIP_DISPATCHED, WebhookEventType.TRIP_COMPLETED)), null);
        subscriptionId = issued.getId();
        secret = issued.getSecret();
        assertThat(secret).isNotBlank();
    }

    @After("@webhooks")
    public void switchOff() {
        if (subscriptionId != null) {
            webhooks().reviseWebhookSubscription(subscriptionId, new WebhookRevisionRequest()
                    .name(subscriptionName).url(receiver.url())
                    .eventTypes(List.of(WebhookEventType.TRIP_DISPATCHED)).active(false), null);
        }
        if (receiver != null) {
            receiver.close();
        }
    }

    @Given("the receiver refuses the next delivery")
    public void refuseOnce() {
        receiver.answer(500);
    }

    @When("the administrator sends a test ping")
    public void ping() {
        pingId = webhooks().pingWebhook(subscriptionId, null).getDeliveryId();
    }

    @When("a trip is dispatched with the webhook subscriber listening")
    public void dispatchTrip() {
        var site = RegisterPointRadiusTerminalRequest.CategoryEnum.CUSTOMER_SITE;
        UUID origin = world.terminal(site, "WH-ORIGIN", 27.10, 72.90);
        UUID destination = world.terminal(site, "WH-DEST", 27.30, 73.10);
        UUID vendor = world.activeVendor("WHV");
        UUID customer = world.partner("WHC", RegisterPartnerRequest.PartnerTypeEnum.CUSTOMER);
        UUID consignee = world.partner("WHN", RegisterPartnerRequest.PartnerTypeEnum.CONSIGNEE);
        UUID lorry = world.lorry("WH", 20000);
        UUID driver = world.driver("WH");
        tripId = world.assignedTrip("WH", customer, consignee, origin, destination, vendor, lorry, driver, 5000);
        tripNo = jdbc.sql("SELECT trip_no FROM trip WHERE id = :id").param("id", tripId).query(String.class).single();
        world.dispatch(tripId, origin, 5000, OffsetDateTime.now(ZoneOffset.UTC).minusHours(2));
    }

    private WebhookReceiver.Received received(String type) {
        return HttpWorld.eventually("a " + type + " delivery reaches the receiver", () -> {
            List<WebhookReceiver.Received> matching = receiver.received().stream()
                    .filter(r -> type.equals(r.headers().get("x-lms-event"))).toList();
            assertThat(matching).as("deliveries of " + type).isNotEmpty();
            return matching.get(matching.size() - 1);
        });
    }

    @Then("the receiver gets a {string} delivery signed with the secret")
    public void signed(String type) {
        WebhookReceiver.Received r = received(type);
        long timestamp = Long.parseLong(r.headers().get("x-lms-timestamp"));
        assertThat(Math.abs(Instant.now().getEpochSecond() - timestamp)).isLessThan(120);
        assertThat(r.headers().get("x-lms-signature")).isEqualTo(WebhookSigner.sign(secret, timestamp, r.body()));
        assertThat(r.body()).contains("\"type\":\"" + type + "\"");
    }

    @Then("that delivery names the dispatched trip and no tenant")
    public void namesTrip() {
        WebhookReceiver.Received r = received("TRIP_DISPATCHED");
        assertThat(r.body()).contains(tripId.toString()).contains(tripNo).doesNotContain("tenant");
    }

    @Then("the ping is listed as delivered after {int} attempt(s)")
    public void delivered(int attempts) {
        HttpWorld.eventually("the ping is delivered", () -> {
            var found = webhooks().listWebhookDeliveries(subscriptionId, null, null, 50).getItems()
                    .stream().filter(d -> d.getId().equals(pingId)).findFirst();
            assertThat(found).as("the ping is listed").isPresent();
            WebhookDelivery delivery = found.get();
            assertThat(delivery.getStatus()).isEqualTo(WebhookDelivery.StatusEnum.DELIVERED);
            assertThat(delivery.getAttempts()).isEqualTo(attempts);
            return delivery;
        });
    }

    @Then("the receiver saw the same delivery id both times")
    public void sameId() {
        List<String> ids = receiver.received().stream()
                .filter(r -> "PING".equals(r.headers().get("x-lms-event")))
                .map(r -> r.headers().get("x-lms-delivery")).toList();
        assertThat(ids).hasSize(2).containsOnly(pingId.toString());
    }
}
