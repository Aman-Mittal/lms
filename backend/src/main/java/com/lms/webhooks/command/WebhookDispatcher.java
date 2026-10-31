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

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.lms.shared.tenant.TenantContext;
import com.lms.shared.tenant.TenantSweep;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Posts due deliveries, records what happened, and schedules the next try.
 *
 * <p>Three steps, so no transaction is open while a receiver is being called:
 * claim a batch per tenant (moving each row's next attempt past the lease so
 * another instance or the next tick does not take it too), post outside any
 * transaction, then record the outcome in a fresh tenant-scoped transaction.
 * A process that dies between the steps leaves the row to be claimed again
 * when the lease runs out: a receiver may see a delivery twice, which is what
 * the {@code X-Lms-Delivery} id is for.
 *
 * <p>Nothing of the receiver's response is kept except its status code; the
 * body could be anything, including something the tenant did not mean to
 * store here.
 */
@Component
public class WebhookDispatcher {

    private static final Logger log = LoggerFactory.getLogger(WebhookDispatcher.class);
    private static final int MAX_ERROR = 300;
    private static final Duration LEASE = Duration.ofMinutes(2);

    private final JdbcClient jdbc;
    private final TenantSweep tenantSweep;
    private final TransactionTemplate transactions;
    private final WebhookTargetGuard guard;
    private final HttpClient http;
    private final RetryBackoff backoff;
    private final boolean enabled;
    private final int batchSize;
    private final int maxAttempts;
    private final int pauseAfterFailures;
    private final Duration readTimeout;

    public WebhookDispatcher(JdbcClient jdbc, TenantSweep tenantSweep, PlatformTransactionManager transactionManager,
                             WebhookTargetGuard guard,
                             @Value("${lms.webhooks.dispatcher.enabled:true}") boolean enabled,
                             @Value("${lms.webhooks.batch-size:50}") int batchSize,
                             @Value("${lms.webhooks.initial-backoff:PT30S}") Duration initialBackoff,
                             @Value("${lms.webhooks.max-backoff:PT1H}") Duration maxBackoff,
                             @Value("${lms.webhooks.max-attempts:8}") int maxAttempts,
                             @Value("${lms.webhooks.pause-after-failures:5}") int pauseAfterFailures,
                             @Value("${lms.webhooks.connect-timeout:PT5S}") Duration connectTimeout,
                             @Value("${lms.webhooks.read-timeout:PT10S}") Duration readTimeout) {
        this.jdbc = jdbc;
        this.tenantSweep = tenantSweep;
        this.transactions = new TransactionTemplate(transactionManager);
        this.guard = guard;
        this.enabled = enabled;
        this.batchSize = batchSize;
        this.backoff = new RetryBackoff(initialBackoff, maxBackoff);
        this.maxAttempts = maxAttempts;
        this.pauseAfterFailures = pauseAfterFailures;
        this.readTimeout = readTimeout;
        this.http = HttpClient.newBuilder()
                .connectTimeout(connectTimeout)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    @Scheduled(initialDelayString = "${lms.webhooks.dispatcher.initial-delay-ms:20000}",
            fixedDelayString = "${lms.webhooks.dispatcher.interval-ms:5000}")
    public void onSchedule() {
        if (enabled) {
            dispatchDue();
        }
    }

    /** A claimed delivery, with what is needed to post it. */
    record Claimed(UUID tenantId, UUID id, UUID subscriptionId, String eventType, String payload, int attempts,
                   String url, String secret) {
    }

    /** One pass: every due delivery of every tenant, up to the batch size each. @return how many were posted */
    public int dispatchDue() {
        List<Claimed> claimed = new ArrayList<>();
        tenantSweep.forEachTenant("webhook-claim", tenantId -> {
            List<Claimed> batch = claim(tenantId);
            claimed.addAll(batch);
            return batch.size();
        });
        for (Claimed delivery : claimed) {
            Outcome outcome = post(delivery);
            try {
                TenantContext.runWith(delivery.tenantId(), null, () ->
                        transactions.executeWithoutResult(status -> record(delivery, outcome)));
            } catch (RuntimeException e) {
                log.error("Could not record the outcome of webhook delivery {}", delivery.id(), e);
            }
        }
        return claimed.size();
    }

    private List<Claimed> claim(UUID tenantId) {
        return jdbc.sql("""
                        WITH due AS (
                            SELECT d.id
                              FROM webhook_delivery d
                              JOIN webhook_subscription s ON s.id = d.subscription_id
                             WHERE d.status = 'PENDING' AND d.next_attempt_at <= now() AND s.active
                             ORDER BY d.next_attempt_at
                             LIMIT :batch
                             FOR UPDATE OF d SKIP LOCKED),
                        claimed AS (
                            UPDATE webhook_delivery d
                               SET next_attempt_at = now() + make_interval(secs => :lease)
                              FROM due
                             WHERE d.id = due.id
                         RETURNING d.id, d.subscription_id, d.event_type, d.payload, d.attempts)
                        SELECT c.id, c.subscription_id, c.event_type, c.payload::text AS payload, c.attempts,
                               s.url, s.secret
                          FROM claimed c
                          JOIN webhook_subscription s ON s.id = c.subscription_id
                        """)
                .param("batch", batchSize)
                .param("lease", (double) LEASE.toSeconds())
                .query((rs, row) -> new Claimed(tenantId, rs.getObject("id", UUID.class),
                        rs.getObject("subscription_id", UUID.class), rs.getString("event_type"),
                        rs.getString("payload"), rs.getInt("attempts"), rs.getString("url"),
                        rs.getString("secret")))
                .list();
    }

    /** What happened: a status code, or why there was none. */
    record Outcome(Integer statusCode, String error) {

        boolean delivered() {
            return statusCode != null && statusCode >= 200 && statusCode < 300;
        }
    }

    private Outcome post(Claimed delivery) {
        String refusal = guard.refusalFor(delivery.url());
        if (refusal != null) {
            return new Outcome(null, refusal);
        }
        long timestamp = Instant.now().getEpochSecond();
        HttpRequest request = HttpRequest.newBuilder(URI.create(delivery.url()))
                .timeout(readTimeout)
                .header("Content-Type", "application/json")
                .header("User-Agent", "lms-webhooks/1")
                .header("X-Lms-Event", delivery.eventType())
                .header("X-Lms-Delivery", delivery.id().toString())
                .header("X-Lms-Timestamp", Long.toString(timestamp))
                .header("X-Lms-Signature", WebhookSigner.sign(delivery.secret(), timestamp, delivery.payload()))
                .POST(HttpRequest.BodyPublishers.ofString(delivery.payload(), StandardCharsets.UTF_8))
                .build();
        try {
            HttpResponse<Void> response = http.send(request, HttpResponse.BodyHandlers.discarding());
            int code = response.statusCode();
            return new Outcome(code, code >= 200 && code < 300 ? null : "The receiver answered " + code);
        } catch (IOException e) {
            return new Outcome(null, e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Outcome(null, "Interrupted");
        }
    }

    private void record(Claimed delivery, Outcome outcome) {
        int attempts = delivery.attempts() + 1;
        if (outcome.delivered()) {
            jdbc.sql("""
                            UPDATE webhook_delivery
                               SET status = 'DELIVERED', attempts = :attempts, last_status_code = :code,
                                   last_error = NULL, delivered_at = now()
                             WHERE id = :id
                            """)
                    .param("attempts", attempts).param("code", outcome.statusCode()).param("id", delivery.id())
                    .update();
            jdbc.sql("UPDATE webhook_subscription SET consecutive_failures = 0 WHERE id = :id")
                    .param("id", delivery.subscriptionId()).update();
            return;
        }
        String error = truncate(outcome.error());
        if (attempts < maxAttempts) {
            jdbc.sql("""
                            UPDATE webhook_delivery
                               SET attempts = :attempts, last_status_code = :code, last_error = :error,
                                   next_attempt_at = now() + make_interval(secs => :delay)
                             WHERE id = :id
                            """)
                    .param("attempts", attempts).param("code", outcome.statusCode()).param("error", error)
                    .param("delay", (double) backoff.after(attempts).toSeconds()).param("id", delivery.id())
                    .update();
            return;
        }
        jdbc.sql("""
                        UPDATE webhook_delivery
                           SET status = 'FAILED', attempts = :attempts, last_status_code = :code, last_error = :error
                         WHERE id = :id
                        """)
                .param("attempts", attempts).param("code", outcome.statusCode()).param("error", error)
                .param("id", delivery.id())
                .update();
        int paused = jdbc.sql("""
                        UPDATE webhook_subscription
                           SET consecutive_failures = consecutive_failures + 1,
                               active = CASE WHEN consecutive_failures + 1 >= :limit THEN FALSE ELSE active END,
                               paused_reason = CASE WHEN consecutive_failures + 1 >= :limit
                                                    THEN 'Paused after ' || (consecutive_failures + 1)
                                                         || ' deliveries in a row failed'
                                                    ELSE paused_reason END,
                               version = version + 1, updated_at = now()
                         WHERE id = :id AND active AND consecutive_failures + 1 >= :limit
                        """)
                .param("limit", pauseAfterFailures).param("id", delivery.subscriptionId())
                .update();
        if (paused == 0) {
            jdbc.sql("UPDATE webhook_subscription SET consecutive_failures = consecutive_failures + 1 WHERE id = :id")
                    .param("id", delivery.subscriptionId()).update();
        } else {
            log.warn("Webhook subscription {} paused after repeated failed deliveries", delivery.subscriptionId());
        }
    }

    private static String truncate(String error) {
        if (error == null) {
            return null;
        }
        return error.length() <= MAX_ERROR ? error : error.substring(0, MAX_ERROR);
    }
}
