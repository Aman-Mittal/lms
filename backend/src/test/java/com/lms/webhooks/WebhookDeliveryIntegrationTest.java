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
package com.lms.webhooks;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.lms.acceptance.TenantSeeds;
import com.lms.acceptance.TenantSeeds.Seeded;
import com.lms.acceptance.TestFixtures;
import com.lms.identity.security.TokenService;
import com.lms.shared.tenant.TenantContext;
import com.lms.web.ApiTestKit;
import com.lms.web.ApiTestKit.Outcome;
import com.lms.web.ApiTestKit.Tenant;
import com.lms.webhooks.command.WebhookDispatcher;
import com.lms.webhooks.command.WebhookEventType;
import com.lms.webhooks.command.WebhookOutbox;
import com.lms.webhooks.command.WebhookSigner;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Deliveries end to end against a real local receiver: events from a real
 * command reach the outbox, the dispatcher posts them signed, retries on
 * failure, gives up after the cap, pauses a subscription that keeps failing,
 * and redelivers on request.
 *
 * <p>The timer is off; each test drives {@link WebhookDispatcher#dispatchDue()}
 * itself and makes a retry due by moving its time, rather than waiting.
 * Plain http and local addresses are allowed here, and only here.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WebhookDeliveryIntegrationTest {

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17.7-alpine")
            .withDatabaseName("lms").withUsername("lms").withPassword("lms");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("lms.webhooks.dispatcher.enabled", () -> "false");
        registry.add("lms.webhooks.allow-http", () -> "true");
        registry.add("lms.webhooks.allow-private-targets", () -> "true");
        registry.add("lms.webhooks.max-attempts", () -> "2");
        registry.add("lms.webhooks.pause-after-failures", () -> "2");
        registry.add("lms.webhooks.initial-backoff", () -> "PT1M");
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
    @Autowired
    private WebhookDispatcher dispatcher;
    @Autowired
    private WebhookOutbox outbox;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private JsonMapper json;

    private ApiTestKit kit;
    private String[] everything;
    private Seeded seeded;
    private Tenant caller;
    private WebhookReceiver receiver;

    @BeforeAll
    void tenant() {
        kit = new ApiTestKit(fixtures, tokens, port);
        everything = seeds.allPermissions().toArray(String[]::new);
        seeded = seeds.seed(0.0);
        caller = kit.tenantOf(seeded.tenantId(), seeded.orgUnitId(), seeded.orgPath());
    }

    @BeforeEach
    void freshReceiver() throws IOException {
        // Each test owns its receiver and its subscription: earlier ones are switched off.
        jdbc.sql("UPDATE webhook_subscription SET active = FALSE WHERE tenant_id = :t")
                .param("t", seeded.tenantId()).update();
        jdbc.sql("DELETE FROM webhook_delivery WHERE tenant_id = :t").param("t", seeded.tenantId()).update();
        jdbc.sql("DELETE FROM claim WHERE tenant_id = :t").param("t", seeded.tenantId()).update();
        receiver = new WebhookReceiver();
    }

    @AfterEach
    void stopReceiver() {
        receiver.close();
    }

    /** @return the subscription id and its secret */
    private String[] subscribe(String types) {
        Outcome made = kit.post(caller, "/api/v1/webhooks/subscriptions",
                "{\"name\":\"it-" + UUID.randomUUID() + "\",\"url\":\"" + receiver.url()
                        + "\",\"eventTypes\":" + types + "}", everything);
        assertThat(made.status()).as(made.body()).isEqualTo(201);
        JsonNode body = kit.parse(made);
        return new String[] {body.get("id").asString(), body.get("secret").asString()};
    }

    private UUID raiseLossClaim() {
        Outcome raised = kit.post(caller, "/api/v1/claims", "{\"consignmentId\":\"" + seeded.id("consignment")
                + "\",\"claimType\":\"LOSS\",\"amount\":125.50,\"description\":\"Never arrived\"}", everything);
        assertThat(raised.status()).as(raised.body()).isEqualTo(201);
        return UUID.fromString(kit.parse(raised).get("id").asString());
    }

    private UUID ping(String subscriptionId) {
        Outcome pinged = kit.post(caller, "/api/v1/webhooks/subscriptions/" + subscriptionId + "/pings", "{}",
                everything);
        assertThat(pinged.status()).as(pinged.body()).isEqualTo(202);
        return UUID.fromString(kit.parse(pinged).get("deliveryId").asString());
    }

    private Map<String, Object> delivery(UUID id) {
        return jdbc.sql("SELECT status, attempts, last_status_code, last_error, next_attempt_at > now() AS later "
                        + "FROM webhook_delivery WHERE id = :id")
                .param("id", id).query().singleRow();
    }

    private Map<String, Object> subscription(String id) {
        return jdbc.sql("SELECT active, paused_reason, consecutive_failures FROM webhook_subscription WHERE id = :id")
                .param("id", UUID.fromString(id)).query().singleRow();
    }

    private void makeDueNow() {
        jdbc.sql("UPDATE webhook_delivery SET next_attempt_at = now() WHERE tenant_id = :t AND status = 'PENDING'")
                .param("t", seeded.tenantId()).update();
    }

    @Test
    @DisplayName("a claim raised and moved is delivered signed, once per event, naming no tenant")
    void claimEventsDeliveredSigned() {
        String[] sub = subscribe("[\"CLAIM_RAISED\",\"CLAIM_STATUS_CHANGED\"]");
        UUID claim = raiseLossClaim();
        Outcome moved = kit.post(caller, "/api/v1/claims/" + claim + "/transitions",
                "{\"target\":\"UNDER_REVIEW\"}", everything);
        assertThat(moved.status()).as(moved.body()).isEqualTo(204);

        assertThat(dispatcher.dispatchDue()).isEqualTo(2);
        List<WebhookReceiver.Received> got = receiver.received();
        assertThat(got).hasSize(2);
        assertThat(got).extracting(r -> r.headers().get("x-lms-event"))
                .containsExactlyInAnyOrder("CLAIM_RAISED", "CLAIM_STATUS_CHANGED");

        for (WebhookReceiver.Received r : got) {
            long timestamp = Long.parseLong(r.headers().get("x-lms-timestamp"));
            assertThat(Math.abs(Instant.now().getEpochSecond() - timestamp)).isLessThan(60);
            assertThat(r.headers().get("x-lms-signature"))
                    .as("the signature verifies with the subscription's secret")
                    .isEqualTo(WebhookSigner.sign(sub[1], timestamp, r.body()));
            assertThat(r.headers().get("x-lms-delivery")).isNotBlank();
            assertThat(r.body()).doesNotContain(seeded.tenantId().toString()).doesNotContain("tenant");
        }
        WebhookReceiver.Received raisedOne = got.stream()
                .filter(r -> r.headers().get("x-lms-event").equals("CLAIM_RAISED")).findFirst().orElseThrow();
        JsonNode envelope = json.readTree(raisedOne.body());
        assertThat(envelope.get("type").asString()).isEqualTo("CLAIM_RAISED");
        assertThat(envelope.get("data").get("claimId").asString()).isEqualTo(claim.toString());
        assertThat(envelope.get("data").get("claimType").asString()).isEqualTo("LOSS");
        assertThat(envelope.get("data").get("amount").decimalValue()).isEqualByComparingTo("125.50");

        assertThat(jdbc.sql("SELECT count(*) FROM webhook_delivery WHERE subscription_id = :s AND status = 'DELIVERED'")
                .param("s", UUID.fromString(sub[0])).query(Integer.class).single()).isEqualTo(2);
        assertThat(dispatcher.dispatchDue()).as("nothing left to send").isZero();
        assertThat(receiver.received()).hasSize(2);
    }

    @Test
    @DisplayName("a refused delivery is retried later and then succeeds")
    void retriedThenDelivered() {
        String[] sub = subscribe("[\"TRIP_COMPLETED\"]");
        receiver.answer(500);
        UUID delivery = ping(sub[0]);

        dispatcher.dispatchDue();
        Map<String, Object> afterFirst = delivery(delivery);
        assertThat(afterFirst.get("status")).isEqualTo("PENDING");
        assertThat(afterFirst.get("attempts")).isEqualTo(1);
        assertThat(afterFirst.get("last_status_code")).isEqualTo(500);
        assertThat(afterFirst.get("later")).as("the retry waits for its backoff").isEqualTo(true);

        dispatcher.dispatchDue();
        assertThat(receiver.received()).as("not due yet, so not sent again").hasSize(1);

        makeDueNow();
        dispatcher.dispatchDue();
        Map<String, Object> afterSecond = delivery(delivery);
        assertThat(afterSecond.get("status")).isEqualTo("DELIVERED");
        assertThat(afterSecond.get("attempts")).isEqualTo(2);
        assertThat(afterSecond.get("last_error")).isNull();
        assertThat(receiver.received()).hasSize(2);
        assertThat(receiver.received().get(0).headers().get("x-lms-delivery"))
                .as("a retry is the same delivery")
                .isEqualTo(receiver.received().get(1).headers().get("x-lms-delivery"));
        assertThat(subscription(sub[0]).get("consecutive_failures")).isEqualTo(0);
    }

    @Test
    @DisplayName("deliveries that keep failing are given up, the subscription pauses, and a redelivery recovers")
    void givesUpPausesAndRecovers() {
        String[] sub = subscribe("[\"CLAIM_RAISED\"]");
        receiver.answerAlways(503);
        UUID first = ping(sub[0]);
        UUID second = ping(sub[0]);

        dispatcher.dispatchDue();
        makeDueNow();
        dispatcher.dispatchDue();

        assertThat(delivery(first).get("status")).isEqualTo("FAILED");
        assertThat(delivery(second).get("status")).isEqualTo("FAILED");
        assertThat(delivery(first).get("attempts")).isEqualTo(2);
        Map<String, Object> paused = subscription(sub[0]);
        assertThat(paused.get("active")).isEqualTo(false);
        assertThat((String) paused.get("paused_reason")).contains("2 deliveries in a row failed");

        // A business change while paused still succeeds, and queues nothing for the paused subscription.
        raiseLossClaim();
        assertThat(jdbc.sql("SELECT count(*) FROM webhook_delivery WHERE subscription_id = :s")
                .param("s", UUID.fromString(sub[0])).query(Integer.class).single()).isEqualTo(2);
        Outcome refused = kit.post(caller, "/api/v1/webhooks/deliveries/" + first + "/redeliveries", "{}", everything);
        assertThat(refused.status()).isEqualTo(409);
        assertThat(refused.code()).isEqualTo("webhook-subscription-inactive");

        receiver.answerAlways(200);
        Outcome reactivated = kit.put(caller, "/api/v1/webhooks/subscriptions/" + sub[0],
                "{\"name\":\"it-" + UUID.randomUUID() + "\",\"url\":\"" + receiver.url()
                        + "\",\"eventTypes\":[\"CLAIM_RAISED\"],\"active\":true}", everything);
        assertThat(reactivated.status()).as(reactivated.body()).isEqualTo(204);
        assertThat(subscription(sub[0]).get("paused_reason")).isNull();
        assertThat(subscription(sub[0]).get("consecutive_failures")).isEqualTo(0);

        Outcome again = kit.post(caller, "/api/v1/webhooks/deliveries/" + first + "/redeliveries", "{}", everything);
        assertThat(again.status()).as(again.body()).isEqualTo(202);
        dispatcher.dispatchDue();
        assertThat(delivery(first).get("status")).isEqualTo("DELIVERED");
        assertThat(delivery(first).get("attempts")).isEqualTo(1);
        assertThat(delivery(second).get("status")).as("only what was asked for is sent again").isEqualTo("FAILED");
    }

    @Test
    @DisplayName("the same event recorded twice is one delivery")
    void sameEventOnce() {
        String[] sub = subscribe("[\"TRIP_CANCELLED\"]");
        Instant at = Instant.parse("2026-10-01T10:15:30Z");
        Map<String, Object> data = Map.of("tripId", "t-1");
        for (int i = 0; i < 2; i++) {
            TenantContext.runWith(seeded.tenantId(), null, () ->
                    new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                            outbox.record(seeded.tenantId(), WebhookEventType.TRIP_CANCELLED, "trip-1", at, data)));
        }
        assertThat(jdbc.sql("SELECT count(*) FROM webhook_delivery WHERE subscription_id = :s")
                .param("s", UUID.fromString(sub[0])).query(Integer.class).single()).isEqualTo(1);
    }
}
