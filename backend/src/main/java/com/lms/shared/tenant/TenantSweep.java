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
package com.lms.shared.tenant;

import java.util.List;
import java.util.UUID;
import java.util.function.ToIntFunction;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Runs a scheduled job once per tenant, each pass properly scoped.
 *
 * <p>Every periodic concern in this platform runs in-process, because Render's
 * free tier has no background workers. That puts scheduled jobs on a thread
 * with no request behind it and therefore no tenant scope -- and a job with no
 * scope is not a job with wide access, it is a job with <em>none</em>.
 * {@code app_current_tenant()} returns NULL, every policy evaluates
 * {@code tenant_id = NULL} which is NULL rather than true, and the job reads an
 * empty table and reports success.
 *
 * <p>That is a silent no-op, which is the worst available outcome: an operator
 * sees a green scheduler and a growing table. The idempotency prune written in
 * V5 had exactly this defect from the day it was written.
 *
 * <p>The fix is not to let jobs bypass row-level security. It is to make them
 * work the way request handling already does -- pick a tenant, bind the scope,
 * open a transaction, do the work for that tenant -- iterating over the tenant
 * register. Slower by a transaction per tenant, and worth it: a job that
 * bypassed RLS would be the one piece of code in the platform where a bug
 * leaks across tenants rather than returning nothing.
 *
 * <p>One tenant's failure must not stop the others. Each pass is its own
 * transaction, and a failure is logged and stepped over.
 */
@Component
public class TenantSweep {

    private static final Logger log = LoggerFactory.getLogger(TenantSweep.class);

    private final JdbcClient jdbc;
    private final PlatformTransactionManager transactionManager;

    public TenantSweep(JdbcClient jdbc, PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.transactionManager = transactionManager;
    }

    /**
     * Applies {@code action} to every active tenant in turn.
     *
     * @param jobName used only for logging, so a failing sweep names itself
     * @param action  returns how many rows it touched, for the summary log
     * @return the total across all tenants
     */
    public int forEachTenant(String jobName, ToIntFunction<UUID> action) {
        List<UUID> tenantIds = activeTenantIds();
        int total = 0;
        int failed = 0;

        for (UUID tenantId : tenantIds) {
            try {
                // Scope first, transaction second. The transaction manager
                // publishes the scope onto the connection while starting the
                // transaction, so binding it afterwards would arrive too late
                // and the statement would run unscoped -- which is the bug this
                // class exists to prevent.
                Integer touched = TenantContext.callWith(tenantId, null, () ->
                        new TransactionTemplate(transactionManager)
                                .execute(status -> action.applyAsInt(tenantId)));
                total += touched == null ? 0 : touched;
            } catch (RuntimeException e) {
                failed++;
                log.error("Sweep '{}' failed for tenant {}", jobName, tenantId, e);
            }
        }

        if (total > 0 || failed > 0) {
            log.info("Sweep '{}' touched {} rows across {} tenants ({} failed)",
                    jobName, total, tenantIds.size(), failed);
        }
        return total;
    }

    /**
     * The tenant register, read through the SECURITY DEFINER function of V9.
     *
     * <p>Reading it is a precondition of establishing scope, so it cannot
     * itself require scope. The function returns identifiers and nothing else;
     * no business row becomes reachable, because the caller still has to bind a
     * scope to see one.
     */
    private List<UUID> activeTenantIds() {
        return jdbc.sql("SELECT app_active_tenant_ids()")
                .query(UUID.class)
                .list();
    }
}
