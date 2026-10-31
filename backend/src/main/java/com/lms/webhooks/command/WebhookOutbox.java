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
package com.lms.webhooks.command;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Turns one event into one delivery row per subscription that wants it.
 *
 * <p>The row holds the exact body that will be posted, so a redelivery sends
 * the same document byte for byte; only the timestamp and signature change.
 * The body carries no tenant id: the receiver is the tenant, and a body copied
 * somewhere else should not name it.
 */
@Component
public class WebhookOutbox {

    private final JdbcClient jdbc;
    private final JsonMapper json;

    public WebhookOutbox(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /**
     * Records the event for every active subscription of the caller's tenant
     * that subscribed to its type. Runs inside a tenant-scoped transaction.
     *
     * @param eventKey what makes this event itself, e.g. the trip id; with the
     *                 type and time it derives the event id, so recording the
     *                 same event again is a no-op
     * @return how many deliveries were recorded
     */
    public int record(UUID tenantId, WebhookEventType type, String eventKey, Instant occurredAt,
                      Map<String, Object> data) {
        UUID eventId = eventId(type, eventKey, occurredAt);
        String body = body(eventId, type, occurredAt, data);
        return jdbc.sql("""
                        INSERT INTO webhook_delivery (id, tenant_id, subscription_id, event_id, event_type, payload)
                        SELECT gen_random_uuid(), :tenantId, s.id, :eventId, :type, CAST(:payload AS jsonb)
                          FROM webhook_subscription s
                         WHERE s.active
                           AND :type = ANY (s.event_types)
                        ON CONFLICT (subscription_id, event_id) DO NOTHING
                        """)
                .param("tenantId", tenantId)
                .param("eventId", eventId)
                .param("type", type.name())
                .param("payload", body)
                .update();
    }

    /** A test ping for one subscription, whatever it subscribed to. */
    public UUID recordPing(UUID tenantId, UUID subscriptionId) {
        Instant now = Instant.now();
        UUID eventId = UUID.randomUUID();
        UUID deliveryId = UUID.randomUUID();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("subscriptionId", subscriptionId.toString());
        data.put("message", "This is a test delivery.");
        jdbc.sql("""
                        INSERT INTO webhook_delivery (id, tenant_id, subscription_id, event_id, event_type, payload)
                        VALUES (:id, :tenantId, :subscriptionId, :eventId, 'PING', CAST(:payload AS jsonb))
                        """)
                .param("id", deliveryId)
                .param("tenantId", tenantId)
                .param("subscriptionId", subscriptionId)
                .param("eventId", eventId)
                .param("payload", body(eventId, WebhookEventType.PING, now, data))
                .update();
        return deliveryId;
    }

    static UUID eventId(WebhookEventType type, String eventKey, Instant occurredAt) {
        return UUID.nameUUIDFromBytes((type.name() + ":" + eventKey + ":" + occurredAt)
                .getBytes(StandardCharsets.UTF_8));
    }

    private String body(UUID eventId, WebhookEventType type, Instant occurredAt, Map<String, Object> data) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("id", eventId.toString());
        envelope.put("type", type.name());
        envelope.put("occurredAt", occurredAt.toString());
        envelope.put("data", data);
        return json.writeValueAsString(envelope);
    }
}
