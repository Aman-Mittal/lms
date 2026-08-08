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
package com.lms.shared.idempotency;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.Optional;

import com.lms.shared.error.BusinessRuleViolationException;
import com.lms.shared.tenant.TenantContext;
import com.lms.shared.tenant.TenantSweep;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Deduplicates retried state-changing requests.
 *
 * <p>The claim is an {@code INSERT}, not a check-then-insert. Two concurrent
 * retries would both pass a {@code SELECT} and both proceed; letting the
 * primary key decide makes the race impossible rather than unlikely. The loser
 * inserts nothing and reads the winner's outcome.
 *
 * <p>Reuse of a key with a <em>different</em> body is rejected outright. That
 * is a client defect, and silently returning the earlier response would hide it
 * while giving the caller an answer to a question it did not ask.
 */
@Service
public class IdempotencyService {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyService.class);

    /**
     * How long a completed record is kept. Long enough to cover any realistic
     * client retry window, short enough that the 1 GB free database does not
     * fill with them.
     */
    private static final int RETENTION_HOURS = 48;

    private final JdbcClient jdbc;
    private final TenantSweep tenantSweep;

    public IdempotencyService(JdbcClient jdbc, TenantSweep tenantSweep) {
        this.jdbc = jdbc;
        this.tenantSweep = tenantSweep;
    }

    /**
     * Claims a key for this request.
     *
     * @return empty if the caller should proceed, or the previously recorded
     *         outcome if this request has already been handled
     * @throws BusinessRuleViolationException if the key was used for a
     *         different request, or if an identical request is still running
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<RecordedOutcome> claim(String key, String method, String path, String body) {
        String hash = fingerprint(method, path, body);

        // ON CONFLICT DO NOTHING rather than catching a duplicate-key
        // exception. Both make the primary key the arbiter, which is the point
        // -- two concurrent retries would both pass a SELECT, so the insert has
        // to decide. The difference is what happens to the loser: in
        // PostgreSQL a constraint violation aborts the whole transaction, so
        // the follow-up read of the winner's outcome failed with "current
        // transaction is aborted" and the caller got a 500. The conflict clause
        // leaves the transaction usable, so the loser can simply read.
        //
        // This never fired until an HTTP filter started calling it. A retry is
        // the only path that reaches it, and nothing had retried.
        int claimed = jdbc.sql("""
                        INSERT INTO idempotency_key (tenant_id, key, request_hash, state)
                        VALUES (:tenantId, :key, :hash, 'IN_PROGRESS')
                        ON CONFLICT (tenant_id, key) DO NOTHING
                        """)
                .param("tenantId", TenantContext.requireTenantId())
                .param("key", key)
                .param("hash", hash)
                .update();

        return claimed == 1 ? Optional.empty() : Optional.of(existingOutcome(key, hash));
    }

    /** Records the outcome so a later retry receives the original response. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void complete(String key, int httpStatus, String responseBody) {
        jdbc.sql("""
                        UPDATE idempotency_key
                           SET state = 'COMPLETED', http_status = :status,
                               response_body = :body, completed_at = now()
                         WHERE tenant_id = :tenantId AND key = :key
                        """)
                .param("tenantId", TenantContext.requireTenantId())
                .param("key", key)
                .param("status", httpStatus)
                .param("body", responseBody)
                .update();
    }

    /**
     * Releases a claim whose request failed.
     *
     * <p>Without this, a transient failure would poison the key: the client
     * retries, finds the key claimed, and can never succeed.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void release(String key) {
        jdbc.sql("DELETE FROM idempotency_key WHERE tenant_id = :tenantId AND key = :key AND state = 'IN_PROGRESS'")
                .param("tenantId", TenantContext.requireTenantId())
                .param("key", key)
                .update();
    }

    private RecordedOutcome existingOutcome(String key, String expectedHash) {
        var row = jdbc.sql("""
                        SELECT request_hash, state, http_status, response_body
                          FROM idempotency_key
                         WHERE tenant_id = :tenantId AND key = :key
                        """)
                .param("tenantId", TenantContext.requireTenantId())
                .param("key", key)
                .query()
                .singleRow();

        if (!expectedHash.equals(row.get("request_hash"))) {
            throw new BusinessRuleViolationException("idempotency-key-reused",
                    "This Idempotency-Key was already used for a different request");
        }
        if (!"COMPLETED".equals(row.get("state"))) {
            throw new BusinessRuleViolationException("idempotency-in-progress",
                    "An identical request is still being processed; retry shortly");
        }
        return new RecordedOutcome((Integer) row.get("http_status"), (String) row.get("response_body"));
    }

    /**
     * Prunes completed records, one tenant at a time.
     *
     * <p>Also runs at startup: Render's free tier has no cron and the instance
     * sleeps, so a purely wall-clock schedule would simply not fire.
     *
     * <p>The per-tenant loop is not tidiness. This method was originally a
     * single unscoped DELETE, and it deleted nothing: {@code idempotency_key}
     * enforces row-level security, a scheduled thread carries no tenant scope,
     * and the policy therefore matched no rows at all. It reported success
     * every six hours while the table grew. See {@link TenantSweep}.
     */
    @Scheduled(initialDelay = 30_000, fixedDelay = 6 * 60 * 60 * 1000)
    public void pruneExpired() {
        Instant before = Instant.now().minus(RETENTION_HOURS, ChronoUnit.HOURS);
        tenantSweep.forEachTenant("idempotency-prune", tenantId ->
                jdbc.sql("""
                                DELETE FROM idempotency_key
                                 WHERE tenant_id = :tenantId
                                   AND state = 'COMPLETED'
                                   AND created_at < :before
                                """)
                        .param("tenantId", tenantId)
                        .param("before", before)
                        .update());
    }

    private static String fingerprint(String method, String path, String body) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(method.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) '\n');
            digest.update(path.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) '\n');
            if (body != null) {
                digest.update(body.getBytes(StandardCharsets.UTF_8));
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    /** A previously recorded response, replayed verbatim to a retry. */
    public record RecordedOutcome(Integer httpStatus, String responseBody) {
    }
}
