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

package com.lms.order.command.enquiry;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import com.lms.shared.audit.AuditTrail;
import com.lms.shared.config.Json;
import com.lms.shared.tenant.TenantSweep;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Closes the enquiries whose deadline has passed (FR-ORD-031).
 *
 * <p>The deadline is the one stored on the enquiry when it was created, so a
 * later change of policy moves nothing and there is no sliding window. What
 * has been won is never touched: an enquiry with won lines keeps them, loses
 * its open lines to a cancellation and becomes WON or PARTIAL by what remains.
 * With nothing won the whole enquiry is cancelled, saying why, and the quotes
 * a customer still held are withdrawn.
 *
 * <p>In-process and per tenant through {@link TenantSweep}, and run shortly
 * after start-up as well as every few minutes, because the free instance
 * sleeps. Idempotent: an enquiry that is no longer open is not selected.
 */
@Component
public class EnquiryDeadlines {

    /** Written on the enquiry and on its audit entry when the sweep closes it. */
    public static final String DEADLINE_REASON = "Response deadline passed";

    private static final Logger log = LoggerFactory.getLogger(EnquiryDeadlines.class);
    private static final int BATCH = 200;

    private final TenantSweep tenantSweep;
    private final JdbcClient jdbc;
    private final EnquiryStore store;
    private final AuditTrail audit;

    public EnquiryDeadlines(TenantSweep tenantSweep, JdbcClient jdbc, EnquiryStore store, AuditTrail audit) {
        this.tenantSweep = tenantSweep;
        this.jdbc = jdbc;
        this.store = store;
        this.audit = audit;
    }

    @Scheduled(initialDelay = 25_000, fixedDelay = 300_000)
    public void scheduled() {
        sweep(Instant.now());
    }

    /** @return how many enquiries were closed, across every tenant */
    public int sweep(Instant now) {
        return tenantSweep.forEachTenant("enquiry-deadlines", tenantId -> expireDue(now));
    }

    private int expireDue(Instant now) {
        List<UUID> due = jdbc.sql("""
                        SELECT id FROM enquiry
                         WHERE tenant_id = app_current_tenant() AND status = 'OPEN' AND deadline_at <= :now
                         ORDER BY deadline_at, id
                         LIMIT :batch
                         FOR UPDATE SKIP LOCKED
                        """)
                .param("now", now.atOffset(ZoneOffset.UTC)).param("batch", BATCH)
                .query(UUID.class).list();
        int closed = 0;
        for (UUID id : due) {
            if (expire(id)) {
                closed++;
            }
        }
        if (closed > 0) {
            log.info("Enquiry deadlines closed {} enquiries", closed);
        }
        return closed;
    }

    private boolean expire(UUID id) {
        EnquiryStore.Header header = store.lock(id).orElse(null);
        if (header == null || header.state() != EnquiryStatus.OPEN) {
            return false;
        }
        int cancelled = store.cancelOpenLines(id);
        int withdrawn = store.revokeIssued(id, DEADLINE_REASON);
        List<LineStatus> lines = store.lines(id).stream().map(EnquiryStore.Line::state).toList();
        boolean anyWon = lines.stream().anyMatch(LineStatus::isWon);
        if (anyWon) {
            store.advance(id, EnquiryRules.derive(lines));
        } else {
            store.cancelHeader(id, DEADLINE_REASON);
        }
        audit.recordAtomically("ENQUIRY_DEADLINE_PASSED", EnquiryService.AUDIT_TYPE, id,
                Json.object("status", header.status()),
                Json.object("status", anyWon ? EnquiryRules.derive(lines).name() : "CANCELLED",
                        "linesCancelled", String.valueOf(cancelled), "quotesWithdrawn", String.valueOf(withdrawn),
                        "deadlineAt", header.deadlineAt().toString()));
        return true;
    }
}
