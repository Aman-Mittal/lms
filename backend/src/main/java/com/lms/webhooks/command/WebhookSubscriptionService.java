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

import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import com.lms.shared.audit.TransitionAudit;
import com.lms.shared.config.Json;
import com.lms.shared.error.BusinessRuleViolationException;
import com.lms.shared.error.ResourceNotFoundException;
import com.lms.shared.tenant.TenantContext;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Subscribing, revising, rotating the secret, pinging and redelivering.
 *
 * <p>The secret is returned by the call that creates or rotates it and never
 * again; reading a subscription shows that it has one, not what it is.
 */
@Service
public class WebhookSubscriptionService {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int MAX_NAME = 120;

    private final JdbcClient jdbc;
    private final WebhookTargetGuard guard;
    private final WebhookOutbox outbox;
    private final TransitionAudit audit;

    public WebhookSubscriptionService(JdbcClient jdbc, WebhookTargetGuard guard, WebhookOutbox outbox,
                                      TransitionAudit audit) {
        this.jdbc = jdbc;
        this.guard = guard;
        this.outbox = outbox;
        this.audit = audit;
    }

    /** @param secret shown to the caller once */
    public record Created(UUID id, String secret) {
    }

    @Transactional
    @PreAuthorize("hasAuthority('WEBHOOK_MANAGE')")
    public Created subscribe(String name, String url, List<String> eventTypes) {
        UUID tenantId = TenantContext.requireTenantId();
        String cleanName = requireName(name);
        String target = guard.requireAcceptable(url).toString();
        String[] types = requireTypes(eventTypes);
        UUID id = UUID.randomUUID();
        String secret = newSecret();
        try {
            jdbc.sql("""
                            INSERT INTO webhook_subscription (id, tenant_id, name, url, event_types, secret, version)
                            VALUES (:id, :tenantId, :name, :url, :types, :secret, 0)
                            """)
                    .param("id", id).param("tenantId", tenantId).param("name", cleanName)
                    .param("url", target).param("types", types).param("secret", secret)
                    .update();
        } catch (DuplicateKeyException e) {
            throw nameTaken(cleanName);
        }
        audit.record("WEBHOOK_SUBSCRIBED", "WEBHOOK_SUBSCRIPTION", id, null,
                Json.object("name", cleanName, "url", target, "eventTypes", String.join(",", types)));
        return new Created(id, secret);
    }

    /**
     * Replaces the subscription's name, address, types and active flag.
     * Reactivating clears a pause and its failure count.
     */
    @Transactional
    @PreAuthorize("hasAuthority('WEBHOOK_MANAGE')")
    public void revise(UUID id, String name, String url, List<String> eventTypes, boolean active) {
        Current current = current(id);
        String cleanName = requireName(name);
        String target = guard.requireAcceptable(url).toString();
        String[] types = requireTypes(eventTypes);
        try {
            jdbc.sql("""
                            UPDATE webhook_subscription
                               SET name = :name, url = :url, event_types = :types, active = :active,
                                   paused_reason = CASE WHEN :active THEN NULL ELSE paused_reason END,
                                   consecutive_failures = CASE WHEN :active AND NOT active THEN 0
                                                               ELSE consecutive_failures END,
                                   version = version + 1, updated_at = now()
                             WHERE id = :id
                            """)
                    .param("id", id).param("name", cleanName).param("url", target)
                    .param("types", types).param("active", active)
                    .update();
        } catch (DuplicateKeyException e) {
            throw nameTaken(cleanName);
        }
        audit.record("WEBHOOK_REVISED", "WEBHOOK_SUBSCRIPTION", id,
                Json.object("active", Boolean.toString(current.active())),
                Json.object("name", cleanName, "url", target, "eventTypes", String.join(",", types),
                        "active", Boolean.toString(active)));
    }

    @Transactional
    @PreAuthorize("hasAuthority('WEBHOOK_MANAGE')")
    public String rotateSecret(UUID id) {
        current(id);
        String secret = newSecret();
        jdbc.sql("UPDATE webhook_subscription SET secret = :secret, version = version + 1, updated_at = now() "
                        + "WHERE id = :id")
                .param("id", id).param("secret", secret).update();
        audit.record("WEBHOOK_SECRET_ROTATED", "WEBHOOK_SUBSCRIPTION", id, null,
                Json.object("rotated", "true"));
        return secret;
    }

    @Transactional
    @PreAuthorize("hasAuthority('WEBHOOK_MANAGE')")
    public UUID ping(UUID id) {
        Current current = current(id);
        requireActive(current);
        UUID deliveryId = outbox.recordPing(TenantContext.requireTenantId(), id);
        audit.record("WEBHOOK_PINGED", "WEBHOOK_SUBSCRIPTION", id, null,
                Json.object("deliveryId", deliveryId.toString()));
        return deliveryId;
    }

    /** Sends a delivery again from scratch: attempts reset, due now. */
    @Transactional
    @PreAuthorize("hasAuthority('WEBHOOK_MANAGE')")
    public void redeliver(UUID deliveryId) {
        UUID subscriptionId = jdbc.sql("SELECT subscription_id FROM webhook_delivery WHERE id = :id")
                .param("id", deliveryId).query(UUID.class).optional()
                .orElseThrow(() -> new ResourceNotFoundException("Webhook delivery", deliveryId));
        requireActive(current(subscriptionId));
        jdbc.sql("""
                        UPDATE webhook_delivery
                           SET status = 'PENDING', attempts = 0, next_attempt_at = now(),
                               last_status_code = NULL, last_error = NULL, delivered_at = NULL
                         WHERE id = :id
                        """)
                .param("id", deliveryId).update();
        audit.record("WEBHOOK_REDELIVERY_REQUESTED", "WEBHOOK_DELIVERY", deliveryId, null,
                Json.object("status", "PENDING"));
    }

    private record Current(boolean active) {
    }

    private Current current(UUID id) {
        return jdbc.sql("SELECT active FROM webhook_subscription WHERE id = :id")
                .param("id", id)
                .query((rs, row) -> new Current(rs.getBoolean("active")))
                .optional()
                .orElseThrow(() -> new ResourceNotFoundException("Webhook subscription", id));
    }

    private static void requireActive(Current current) {
        if (!current.active()) {
            throw new BusinessRuleViolationException("webhook-subscription-inactive",
                    "The subscription is not active; reactivate it first");
        }
    }

    private static String requireName(String name) {
        String clean = name == null ? "" : name.strip();
        if (clean.isEmpty() || clean.length() > MAX_NAME) {
            throw new BusinessRuleViolationException("webhook-name-invalid",
                    "A subscription name is 1 to " + MAX_NAME + " characters");
        }
        return clean;
    }

    private static String[] requireTypes(List<String> eventTypes) {
        if (eventTypes == null || eventTypes.isEmpty()) {
            throw new BusinessRuleViolationException("webhook-event-types-invalid",
                    "Subscribe to at least one event type");
        }
        Set<String> seen = new LinkedHashSet<>();
        for (String raw : eventTypes) {
            WebhookEventType type;
            try {
                type = WebhookEventType.valueOf(raw == null ? "" : raw.strip().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw new BusinessRuleViolationException("webhook-event-types-invalid",
                        "Unknown event type: " + raw);
            }
            if (!type.subscribable()) {
                throw new BusinessRuleViolationException("webhook-event-types-invalid",
                        type + " cannot be subscribed to; use the ping action");
            }
            seen.add(type.name());
        }
        return seen.toArray(String[]::new);
    }

    private static BusinessRuleViolationException nameTaken(String name) {
        return new BusinessRuleViolationException("webhook-name-taken",
                "A subscription named '" + name + "' already exists");
    }

    private static String newSecret() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
