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
package com.lms.events;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.lms.acceptance.TestFixtures;
import com.lms.events.command.EventRetrier;
import com.lms.identity.security.TokenService;
import com.lms.web.ApiTestKit;
import com.lms.web.ApiTestKit.Outcome;
import com.lms.web.ApiTestKit.Tenant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.testcontainers.containers.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The parked list over HTTP, with real permissions. Events are parked the real
 * way -- a probe listener that fails, a retrier pass -- with one attempt
 * allowed, so a single pass parks whatever failed.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EventDeadLetterApiTest {

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
        registry.add("lms.test.probe-listener", () -> "true");
        registry.add("lms.events.retry.enabled", () -> "false");
        registry.add("lms.events.retry.max-attempts", () -> "1");
        registry.add("lms.events.retry.initial-backoff", () -> "PT1S");
        registry.add("lms.events.retry.grace-period", () -> "PT1S");
    }

    private static final String READ = "EVENT_DEAD_LETTER_READ";
    private static final String MANAGE = "EVENT_DEAD_LETTER_MANAGE";

    @LocalServerPort
    private int port;
    @Autowired
    private TestFixtures fixtures;
    @Autowired
    private TokenService tokens;
    @Autowired
    private ApplicationEventPublisher publisher;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private JdbcClient jdbc;
    @Autowired
    private EventRetrier retrier;
    @Autowired
    private HealthIndicator eventDeadLetter;

    private ApiTestKit kit;
    private ProbeDriver probes;
    private Tenant a;
    private Tenant b;

    @BeforeAll
    void setUp() {
        kit = new ApiTestKit(fixtures, tokens, port);
        probes = new ProbeDriver(publisher, transactionManager, jdbc, retrier);
        a = kit.newTenant("dla");
        b = kit.newTenant("dlb");
    }

    /** Publishes a probe for tenant a that fails as told, and parks it. */
    private UUID parked(String key, int failTimes) {
        probes.publish(a.tenantId(), key, failTimes);
        probes.step();
        UUID id = probes.parkedId(key);
        assertThat(id).as("the probe was parked").isNotNull();
        return id;
    }

    private Outcome replay(Tenant caller, UUID id, String... permissions) {
        return kit.post(caller, "/api/v1/event-dead-letters/" + id + "/replays", "{}", permissions);
    }

    private Outcome discard(Tenant caller, UUID id, String reason, String... permissions) {
        String body = reason == null ? "{}" : "{\"reason\":\"" + reason + "\"}";
        return kit.post(caller, "/api/v1/event-dead-letters/" + id + "/discards", body, permissions);
    }

    @Test
    @DisplayName("a parked event can be read, with its payload, by its own tenant")
    void readOne() {
        String key = "read-" + UUID.randomUUID();
        UUID id = parked(key, ProbeListener.ALWAYS);

        Outcome got = kit.get(a, "/api/v1/event-dead-letters/" + id, READ);
        assertThat(got.status()).as(got.body()).isEqualTo(200);
        JsonNode body = kit.parse(got);
        assertThat(body.get("summary").get("status").asString()).isEqualTo("PARKED");
        assertThat(body.get("summary").get("eventType").asString()).isEqualTo(ProbeEvent.class.getName());
        assertThat(body.get("summary").get("listenerId").asString()).contains("ProbeListener");
        assertThat(body.get("summary").get("attempts").asInt()).isEqualTo(1);
        assertThat(body.get("summary").get("lastError").asString()).contains("was told to fail");
        assertThat(body.get("payload").asString()).contains(key).contains(a.tenantId().toString());
    }

    @Test
    @DisplayName("replaying after the fault is fixed completes the event")
    void replayAfterTheFix() {
        String key = "fixed-" + UUID.randomUUID();
        UUID id = parked(key, ProbeListener.ALWAYS);
        ProbeListener.failTimes(key, 0);

        Outcome replayed = replay(a, id, MANAGE);
        assertThat(replayed.status()).as(replayed.body()).isEqualTo(200);
        assertThat(kit.parse(replayed).get("outcome").asString()).isEqualTo("REPLAYED");
        assertThat(ProbeListener.successes(key)).as("the listener ran to completion").isEqualTo(1);

        JsonNode after = kit.parse(kit.get(a, "/api/v1/event-dead-letters/" + id, READ)).get("summary");
        assertThat(after.get("status").asString()).isEqualTo("REPLAYED");
        assertThat(after.get("resolvedAt").isNull()).isFalse();

        Outcome again = replay(a, id, MANAGE);
        assertThat(again.status()).isEqualTo(409);
        assertThat(again.code()).isEqualTo("dead-letter-not-parked");
    }

    @Test
    @DisplayName("replaying while the fault persists parks it again, one more failure counted")
    void replayStillFailing() {
        String key = "still-" + UUID.randomUUID();
        UUID id = parked(key, ProbeListener.ALWAYS);

        Outcome replayed = replay(a, id, MANAGE);
        assertThat(replayed.status()).as(replayed.body()).isEqualTo(200);
        assertThat(kit.parse(replayed).get("outcome").asString()).isEqualTo("PARKED_AGAIN");
        assertThat(kit.parse(replayed).get("attempts").asInt()).isEqualTo(2);

        JsonNode after = kit.parse(kit.get(a, "/api/v1/event-dead-letters/" + id, READ)).get("summary");
        assertThat(after.get("status").asString()).isEqualTo("PARKED");
        assertThat(after.get("attempts").asInt()).isEqualTo(2);
        assertThat(probes.publicationId(key)).as("not left in the live registry").isNull();
    }

    @Test
    @DisplayName("discarding needs a reason, keeps the event, and is audited with it")
    void discard() {
        UUID id = parked("discard-" + UUID.randomUUID(), ProbeListener.ALWAYS);

        Outcome noReason = discard(a, id, "  ", MANAGE);
        assertThat(noReason.status()).isEqualTo(409);
        assertThat(noReason.code()).isEqualTo("dead-letter-reason-required");

        Outcome done = discard(a, id, "Superseded by a manual correction", MANAGE);
        assertThat(done.status()).as(done.body()).isEqualTo(204);
        JsonNode after = kit.parse(kit.get(a, "/api/v1/event-dead-letters/" + id, READ)).get("summary");
        assertThat(after.get("status").asString()).isEqualTo("DISCARDED");
        assertThat(after.get("resolutionNote").asString()).isEqualTo("Superseded by a manual correction");
        assertThat(jdbc.sql("""
                        SELECT after_state::text FROM audit_log
                         WHERE action = 'EVENT_DISCARDED' AND resource_id = :id
                        """).param("id", id).query(String.class).single())
                .contains("Superseded by a manual correction");

        assertThat(replay(a, id, MANAGE).code()).as("a discarded event is not replayed")
                .isEqualTo("dead-letter-not-parked");
    }

    @Test
    @DisplayName("the list pages by parked order and filters by status and type")
    void listing() {
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            ids.add(parked("page-" + i + "-" + UUID.randomUUID(), ProbeListener.ALWAYS));
        }
        List<String> seen = new ArrayList<>();
        String cursor = null;
        do {
            Outcome page = kit.get(a, "/api/v1/event-dead-letters?limit=2&eventType=" + ProbeEvent.class.getName()
                    + (cursor == null ? "" : "&cursor=" + cursor), READ);
            assertThat(page.status()).as(page.body()).isEqualTo(200);
            JsonNode body = kit.parse(page);
            assertThat(body.get("items").size()).isLessThanOrEqualTo(2);
            body.get("items").forEach(item -> seen.add(item.get("id").asString()));
            cursor = body.get("hasMore").asBoolean() ? body.get("nextCursor").asString() : null;
        } while (cursor != null);
        assertThat(seen).doesNotHaveDuplicates()
                .containsAll(ids.stream().map(UUID::toString).toList());

        Outcome none = kit.get(a, "/api/v1/event-dead-letters?eventType=com.example.Nothing", READ);
        assertThat(kit.parse(none).get("items").size()).isZero();
        Outcome bad = kit.get(a, "/api/v1/event-dead-letters?status=LOST", READ);
        assertThat(bad.status()).isEqualTo(409);
        assertThat(bad.code()).isEqualTo("dead-letter-status-invalid");
    }

    @Test
    @DisplayName("reading and acting need their own permissions")
    void permissions() {
        UUID id = parked("perm-" + UUID.randomUUID(), ProbeListener.ALWAYS);
        assertThat(kit.get(a, "/api/v1/event-dead-letters", MANAGE).status()).isEqualTo(403);
        assertThat(kit.get(a, "/api/v1/event-dead-letters/" + id, MANAGE).status()).isEqualTo(403);
        assertThat(replay(a, id, READ).status()).isEqualTo(403);
        assertThat(discard(a, id, "x", READ).status()).isEqualTo(403);
    }

    @Test
    @DisplayName("another tenant sees none of it, and is told so the way an unknown id is")
    void tenantsAreIsolated() {
        UUID id = parked("iso-" + UUID.randomUUID(), ProbeListener.ALWAYS);

        Outcome theirs = kit.get(b, "/api/v1/event-dead-letters/" + id, READ);
        Outcome unknown = kit.get(b, "/api/v1/event-dead-letters/" + UUID.randomUUID(), READ);
        assertThat(theirs.status()).isEqualTo(404);
        assertThat(theirs.code()).isEqualTo("not-found");
        assertThat(unknown.status()).isEqualTo(404);
        assertThat(unknown.code()).isEqualTo("not-found");
        assertThat(replay(b, id, MANAGE).status()).isEqualTo(404);
        assertThat(discard(b, id, "not mine", MANAGE).status()).isEqualTo(404);
        assertThat(kit.parse(kit.get(b, "/api/v1/event-dead-letters", READ)).get("items").size()).isZero();
    }

    @Test
    @DisplayName("the health detail counts parked events and stays UP")
    void health() {
        parked("health-" + UUID.randomUUID(), ProbeListener.ALWAYS);
        Health health = eventDeadLetter.health();
        assertThat(health.getStatus().getCode()).isEqualTo("UP");
        assertThat((Long) health.getDetails().get("parkedEvents")).isPositive();
        assertThat(health.getDetails().get("degraded")).isEqualTo(true);
    }
}
