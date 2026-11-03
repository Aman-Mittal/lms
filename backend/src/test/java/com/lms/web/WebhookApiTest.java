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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Webhook subscriptions over HTTP with real permissions, under the production
 * address rules (https only, public addresses only). Nothing is posted here:
 * the dispatcher is switched off and the delivery side has its own test.
 *
 * <p>Addresses use the documentation range 203.0.113.0/24, which is public as
 * far as the guard is concerned and needs no DNS lookup.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WebhookApiTest {

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
    }

    private static final String MANAGE = "WEBHOOK_MANAGE";
    private static final String READ = "WEBHOOK_READ";
    private static final String TARGET = "https://203.0.113.10/hooks/lms";

    @LocalServerPort
    private int port;
    @Autowired
    private TestFixtures fixtures;
    @Autowired
    private TokenService tokens;

    private ApiTestKit kit;
    private Tenant a;
    private Tenant b;

    @BeforeAll
    void twoTenants() {
        kit = new ApiTestKit(fixtures, tokens, port);
        a = kit.newTenant("wha");
        b = kit.newTenant("whb");
    }

    private Outcome subscribe(Tenant tenant, String name, String url, String types) {
        return kit.post(tenant, "/api/v1/webhooks/subscriptions",
                "{\"name\":\"" + name + "\",\"url\":\"" + url + "\",\"eventTypes\":" + types + "}",
                MANAGE, READ);
    }

    private UUID created(Outcome outcome) {
        assertThat(outcome.status()).as(outcome.body()).isEqualTo(201);
        return UUID.fromString(kit.parse(outcome).get("id").asString());
    }

    private static String unique(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private void expect(String what, Outcome outcome, int status, String code) {
        assertThat(outcome.status()).as(what + ": " + outcome.body()).isEqualTo(status);
        if (code != null) {
            assertThat(outcome.code()).as(what).isEqualTo(code);
        }
    }

    @Test
    @DisplayName("subscribing returns the secret once; reading never shows it")
    void secretShownOnce() {
        Outcome made = subscribe(a, unique("orders"), TARGET, "[\"ORDER_VALIDATED\",\"TRIP_DISPATCHED\"]");
        UUID id = created(made);
        String secret = kit.parse(made).get("secret").asString();
        assertThat(secret).hasSizeGreaterThanOrEqualTo(40);
        assertThat(made.body()).doesNotContain("tenant");

        Outcome got = kit.get(a, "/api/v1/webhooks/subscriptions/" + id, READ);
        assertThat(got.status()).isEqualTo(200);
        JsonNode view = kit.parse(got);
        assertThat(view.get("url").asString()).isEqualTo(TARGET);
        assertThat(view.get("active").asBoolean()).isTrue();
        assertThat(view.get("eventTypes")).hasSize(2);
        assertThat(got.body()).doesNotContain(secret).doesNotContain("\"secret\"");

        Outcome rotated = kit.post(a, "/api/v1/webhooks/subscriptions/" + id + "/secret-rotations", "{}", MANAGE);
        assertThat(rotated.status()).isEqualTo(200);
        String next = kit.parse(rotated).get("secret").asString();
        assertThat(next).isNotEqualTo(secret);
        assertThat(kit.get(a, "/api/v1/webhooks/subscriptions", READ).body()).doesNotContain(next);
    }

    @Test
    @DisplayName("only https addresses on the public internet are accepted")
    void addressRules() {
        expect("plain http", subscribe(a, unique("h"), "http://203.0.113.10/x", "[\"TRIP_COMPLETED\"]"),
                409, "webhook-url-not-https");
        expect("loopback", subscribe(a, unique("l"), "https://127.0.0.1/x", "[\"TRIP_COMPLETED\"]"),
                409, "webhook-target-not-allowed");
        expect("private range", subscribe(a, unique("p"), "https://10.1.2.3/x", "[\"TRIP_COMPLETED\"]"),
                409, "webhook-target-not-allowed");
        expect("link-local metadata", subscribe(a, unique("m"), "https://169.254.169.254/latest",
                "[\"TRIP_COMPLETED\"]"), 409, "webhook-target-not-allowed");
        expect("credentials in the address", subscribe(a, unique("c"), "https://" + String.join(":", "u", "p") + "@203.0.113.10/x",
                "[\"TRIP_COMPLETED\"]"), 409, "webhook-url-invalid");
        expect("not a url", subscribe(a, unique("n"), "nowhere", "[\"TRIP_COMPLETED\"]"),
                409, "webhook-url-invalid");
    }

    @Test
    @DisplayName("event types must be known and subscribable, and names unique")
    void bodyRules() {
        expect("unknown type", subscribe(a, unique("u"), TARGET, "[\"SOMETHING_ELSE\"]"),
                409, "webhook-event-types-invalid");
        expect("ping is not subscribable", subscribe(a, unique("p"), TARGET, "[\"PING\"]"),
                409, "webhook-event-types-invalid");
        expect("no types", subscribe(a, unique("e"), TARGET, "[]"), 400, null);
        String name = unique("Dup");
        created(subscribe(a, name, TARGET, "[\"TRIP_COMPLETED\"]"));
        expect("same name, other case", subscribe(a, name.toLowerCase(java.util.Locale.ROOT), TARGET,
                "[\"TRIP_COMPLETED\"]"), 409, "webhook-name-taken");
    }

    @Test
    @DisplayName("a revision replaces the subscription, and deactivating stops pings")
    void revision() {
        UUID id = created(subscribe(a, unique("rev"), TARGET, "[\"TRIP_COMPLETED\"]"));
        String revised = unique("revised");
        expect("revise", kit.put(a, "/api/v1/webhooks/subscriptions/" + id,
                "{\"name\":\"" + revised + "\",\"url\":\"https://203.0.113.20/new\","
                        + "\"eventTypes\":[\"CLAIM_RAISED\",\"CLAIM_STATUS_CHANGED\"],\"active\":false}",
                MANAGE), 204, null);
        JsonNode view = kit.parse(kit.get(a, "/api/v1/webhooks/subscriptions/" + id, READ));
        assertThat(view.get("name").asString()).isEqualTo(revised);
        assertThat(view.get("url").asString()).isEqualTo("https://203.0.113.20/new");
        assertThat(view.get("active").asBoolean()).isFalse();
        expect("ping an inactive subscription",
                kit.post(a, "/api/v1/webhooks/subscriptions/" + id + "/pings", "{}", MANAGE),
                409, "webhook-subscription-inactive");
    }

    @Test
    @DisplayName("a ping is queued as a pending delivery and listed, a page at a time")
    void pingAndDeliveries() {
        UUID id = created(subscribe(a, unique("ping"), TARGET, "[\"TRIP_COMPLETED\"]"));
        for (int i = 0; i < 3; i++) {
            Outcome ping = kit.post(a, "/api/v1/webhooks/subscriptions/" + id + "/pings", "{}", MANAGE);
            assertThat(ping.status()).as(ping.body()).isEqualTo(202);
        }
        JsonNode first = kit.parse(kit.get(a, "/api/v1/webhooks/subscriptions/" + id + "/deliveries?limit=2", READ));
        assertThat(first.get("items")).hasSize(2);
        assertThat(first.get("hasMore").asBoolean()).isTrue();
        assertThat(first.get("items").get(0).get("eventType").asString()).isEqualTo("PING");
        assertThat(first.get("items").get(0).get("status").asString()).isEqualTo("PENDING");
        JsonNode second = kit.parse(kit.get(a, "/api/v1/webhooks/subscriptions/" + id + "/deliveries?limit=2&cursor="
                + first.get("nextCursor").asString(), READ));
        assertThat(second.get("items")).hasSize(1);
        assertThat(second.get("hasMore").asBoolean()).isFalse();
        assertThat(kit.parse(kit.get(a, "/api/v1/webhooks/subscriptions/" + id + "/deliveries?status=DELIVERED", READ))
                .get("items")).isEmpty();
        expect("a cursor this listing did not issue",
                kit.get(a, "/api/v1/webhooks/subscriptions/" + id + "/deliveries?cursor=nonsense", READ),
                409, "cursor-invalid");

        UUID delivery = UUID.fromString(first.get("items").get(0).get("id").asString());
        expect("redeliver", kit.post(a, "/api/v1/webhooks/deliveries/" + delivery + "/redeliveries", "{}", MANAGE),
                202, null);
    }

    @Test
    @DisplayName("managing needs WEBHOOK_MANAGE and reading needs WEBHOOK_READ")
    void permissions() {
        UUID id = created(subscribe(a, unique("perm"), TARGET, "[\"TRIP_COMPLETED\"]"));
        expect("subscribe with read only", kit.post(a, "/api/v1/webhooks/subscriptions",
                "{\"name\":\"x\",\"url\":\"" + TARGET + "\",\"eventTypes\":[\"TRIP_COMPLETED\"]}", READ), 403, null);
        expect("rotate with read only",
                kit.post(a, "/api/v1/webhooks/subscriptions/" + id + "/secret-rotations", "{}", READ), 403, null);
        expect("list with manage only", kit.get(a, "/api/v1/webhooks/subscriptions", MANAGE), 403, null);
        expect("deliveries with manage only",
                kit.get(a, "/api/v1/webhooks/subscriptions/" + id + "/deliveries", MANAGE), 403, null);
    }

    @Test
    @DisplayName("another company cannot see or touch a subscription or its deliveries")
    void tenantIsolation() {
        UUID id = created(subscribe(a, unique("iso"), TARGET, "[\"TRIP_COMPLETED\"]"));
        UUID delivery = UUID.fromString(kit.parse(
                kit.post(a, "/api/v1/webhooks/subscriptions/" + id + "/pings", "{}", MANAGE)).get("deliveryId").asString());

        expect("b reads a's subscription", kit.get(b, "/api/v1/webhooks/subscriptions/" + id, READ), 404, "not-found");
        expect("b lists a's deliveries",
                kit.get(b, "/api/v1/webhooks/subscriptions/" + id + "/deliveries", READ), 404, "not-found");
        expect("b revises a's subscription", kit.put(b, "/api/v1/webhooks/subscriptions/" + id,
                "{\"name\":\"x\",\"url\":\"" + TARGET + "\",\"eventTypes\":[\"TRIP_COMPLETED\"],\"active\":true}",
                MANAGE), 404, "not-found");
        expect("b rotates a's secret",
                kit.post(b, "/api/v1/webhooks/subscriptions/" + id + "/secret-rotations", "{}", MANAGE), 404, "not-found");
        expect("b pings a's subscription",
                kit.post(b, "/api/v1/webhooks/subscriptions/" + id + "/pings", "{}", MANAGE), 404, "not-found");
        expect("b redelivers a's delivery",
                kit.post(b, "/api/v1/webhooks/deliveries/" + delivery + "/redeliveries", "{}", MANAGE), 404, "not-found");
        assertThat(kit.parse(kit.get(b, "/api/v1/webhooks/subscriptions", READ)).get("items")).isEmpty();
        expect("a subscription nobody has",
                kit.get(a, "/api/v1/webhooks/subscriptions/" + UUID.randomUUID(), READ), 404, "not-found");
    }
}
