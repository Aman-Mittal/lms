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

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.IntSupplier;

import com.lms.acceptance.TenantSeeds;
import com.lms.shared.tenant.TenantContext;
import com.lms.webhooks.command.WebhookEventType;
import com.lms.webhooks.command.WebhookOutbox;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A batch of events reaches the outbox in one statement, and lands exactly as
 * recording them one by one would: one delivery per event and subscription,
 * the same derived event ids, and nothing twice.
 */
@SpringBootTest
class WebhookOutboxBatchTest {

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

    @Autowired
    private TenantSeeds seeds;
    @Autowired
    private JdbcClient jdbc;
    @Autowired
    private WebhookOutbox outbox;
    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    @DisplayName("a batch records one delivery per event, as one by one would, and repeats nothing")
    void batchAsOneByOne() {
        UUID tenantId = seeds.seed(0.0).tenantId();
        UUID subscription = subscribe(tenantId, "CONSIGNMENT_DELIVERED");
        UUID other = subscribe(tenantId, "TRIP_CANCELLED");
        Instant at = Instant.parse("2026-10-01T10:15:30Z");

        inTenant(tenantId, () -> outbox.record(tenantId, WebhookEventType.CONSIGNMENT_DELIVERED, "c-1", at,
                Map.of("consignmentId", "c-1")));
        List<WebhookOutbox.Event> batch = List.of(
                new WebhookOutbox.Event("c-1", at, Map.of("consignmentId", "c-1")),
                new WebhookOutbox.Event("c-2", at, Map.of("consignmentId", "c-2")),
                new WebhookOutbox.Event("c-3", at, Map.of("consignmentId", "c-3")));
        int first = inTenant(tenantId,
                () -> outbox.recordAll(tenantId, WebhookEventType.CONSIGNMENT_DELIVERED, batch));
        int again = inTenant(tenantId,
                () -> outbox.recordAll(tenantId, WebhookEventType.CONSIGNMENT_DELIVERED, batch));

        assertThat(first).as("c-1 was already recorded one by one").isEqualTo(2);
        assertThat(again).as("a repeated batch records nothing").isZero();
        List<Map<String, Object>> rows = jdbc.sql("""
                        SELECT event_id::text AS event_id, event_type,
                               payload ->> 'id' AS body_id, payload -> 'data' ->> 'consignmentId' AS consignment
                          FROM webhook_delivery WHERE subscription_id = :s ORDER BY consignment
                        """)
                .param("s", subscription).query().listOfRows();
        assertThat(rows).extracting(r -> r.get("consignment")).containsExactly("c-1", "c-2", "c-3");
        assertThat(rows).allSatisfy(r -> {
            assertThat(r.get("event_type")).isEqualTo("CONSIGNMENT_DELIVERED");
            assertThat(r.get("body_id")).isEqualTo(r.get("event_id"));
        });
        assertThat(jdbc.sql("SELECT count(*) FROM webhook_delivery WHERE subscription_id = :s")
                .param("s", other).query(Integer.class).single())
                .as("a subscription to another type gets none").isZero();
        assertThat(inTenant(tenantId,
                () -> outbox.recordAll(tenantId, WebhookEventType.CONSIGNMENT_DELIVERED, List.of()))).isZero();
    }

    private UUID subscribe(UUID tenantId, String type) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO webhook_subscription (id, tenant_id, name, url, event_types, secret)
                        VALUES (:id, :tenantId, :name, 'https://receiver.example/hook', ARRAY[:type], :signingKey)
                        """)
                .param("id", id)
                .param("tenantId", tenantId)
                .param("name", "batch-" + id)
                .param("type", type)
                .param("signingKey", UUID.randomUUID().toString())
                .update();
        return id;
    }

    private int inTenant(UUID tenantId, IntSupplier work) {
        int[] result = new int[1];
        TenantContext.runWith(tenantId, null, () -> new TransactionTemplate(transactionManager)
                .executeWithoutResult(status -> result[0] = work.getAsInt()));
        return result[0];
    }
}
