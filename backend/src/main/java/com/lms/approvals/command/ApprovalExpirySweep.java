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
package com.lms.approvals.command;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.lms.shared.error.BusinessRuleViolationException;
import com.lms.shared.tenant.TenantContext;
import com.lms.shared.tenant.TenantSweep;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Closes approval requests that fell due undecided, as each one's policy says.
 *
 * <p>In-process and per tenant through {@link TenantSweep}, like every other
 * periodic job here: a scheduled thread has no tenant scope, and under
 * row-level security an unscoped query finds nothing. Runs shortly after
 * startup as well as every minute, because the free instance sleeps.
 *
 * <p>Each lapsed request is settled in its own transaction, so one whose
 * automatic approval is refused by its handler does not hold the others back.
 * Such a request is then expired, saying why. Idempotent: a request that is
 * no longer open, or is not yet due, is left alone.
 */
@Component
public class ApprovalExpirySweep {

    private static final Logger log = LoggerFactory.getLogger(ApprovalExpirySweep.class);
    private static final int BATCH = 200;

    private final TenantSweep tenantSweep;
    private final JdbcClient jdbc;
    private final ApprovalCommandService approvals;
    private final TransactionTemplate transactions;

    public ApprovalExpirySweep(TenantSweep tenantSweep, JdbcClient jdbc, ApprovalCommandService approvals,
                               PlatformTransactionManager transactionManager) {
        this.tenantSweep = tenantSweep;
        this.jdbc = jdbc;
        this.approvals = approvals;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    @Scheduled(initialDelay = 30_000, fixedDelay = 60_000)
    public void scheduled() {
        sweep(Instant.now());
    }

    /** @return how many requests were closed */
    public int sweep(Instant now) {
        record Due(UUID tenantId, UUID requestId) {
        }
        List<Due> due = new ArrayList<>();
        tenantSweep.forEachTenant("approval-expiry", tenantId -> {
            List<UUID> ids = jdbc.sql("""
                            SELECT id FROM approval_request
                             WHERE tenant_id = :tenantId AND status = 'REQUESTED'
                               AND due_at IS NOT NULL AND due_at <= :now
                             ORDER BY due_at
                             LIMIT :batch
                            """)
                    .param("tenantId", tenantId)
                    .param("now", now.atOffset(ZoneOffset.UTC))
                    .param("batch", BATCH)
                    .query(UUID.class)
                    .list();
            ids.forEach(id -> due.add(new Due(tenantId, id)));
            return 0;
        });

        int closed = 0;
        for (Due d : due) {
            // Scope first, transaction second (AGENTS.md section 4).
            try {
                if (Boolean.TRUE.equals(TenantContext.callWith(d.tenantId(), null, () ->
                        transactions.execute(status -> approvals.settleLapsed(d.requestId(), now))))) {
                    closed++;
                }
            } catch (BusinessRuleViolationException refused) {
                log.info("Approval {} could not be approved at expiry ({}); expiring it", d.requestId(),
                        refused.code());
                try {
                    if (Boolean.TRUE.equals(TenantContext.callWith(d.tenantId(), null, () ->
                            transactions.execute(status ->
                                    approvals.expireRefused(d.requestId(), refused.code(), now))))) {
                        closed++;
                    }
                } catch (RuntimeException e) {
                    log.error("Could not expire approval request {}", d.requestId(), e);
                }
            } catch (RuntimeException e) {
                log.error("Could not settle lapsed approval request {}", d.requestId(), e);
            }
        }
        if (closed > 0) {
            log.info("Approval expiry closed {} requests", closed);
        }
        return closed;
    }
}
