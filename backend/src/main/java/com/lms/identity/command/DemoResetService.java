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
package com.lms.identity.command;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import com.lms.shared.audit.TransitionAudit;
import com.lms.shared.config.Json;
import com.lms.shared.error.BusinessRuleViolationException;
import com.lms.shared.error.ResourceNotFoundException;
import com.lms.shared.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Puts the demo tenant back to the state the seeder leaves it in.
 *
 * <p>Every row the demo tenant owns is deleted -- in every table with a
 * {@code tenant_id}, children first ({@link DemoResetPlan}) -- and the seeder
 * runs again, in one transaction: a reset that fails half way leaves the data
 * as it was. Three guards keep it on the demo:
 *
 * <ul>
 *   <li>it is unavailable, as a 404, unless the deployment has the demo on;
 *   <li>the caller must be inside the configured demo tenant, and any other
 *       tenant gets the same 404 rather than a hint that the action exists;
 *   <li>the database refuses to clear a tenant the seeder did not mark as the
 *       demo ({@code demo_reset_wipe_table}, V41).
 * </ul>
 *
 * <p>The caller confirms by naming the demo tenant's code, and two resets at
 * once are serialised by a transaction-scoped advisory lock: the second starts
 * from the seed the first left.
 *
 * <p>Every user is recreated, including the caller's: tokens are stateless, so
 * the one in hand keeps working until it expires, but it names a user that no
 * longer exists, and the answer says to sign in again.
 */
@Service
public class DemoResetService {

    private static final Logger log = LoggerFactory.getLogger(DemoResetService.class);

    private final JdbcClient jdbc;
    private final DemoTenantSeeder seeder;
    private final TransitionAudit audit;
    private final Environment environment;
    private final TransactionTemplate transactions;

    public DemoResetService(JdbcClient jdbc, DemoTenantSeeder seeder, TransitionAudit audit,
                            Environment environment, PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.seeder = seeder;
        this.audit = audit;
        this.environment = environment;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    /** What a reset did. */
    public record Outcome(String tenantCode, long rowsDeleted, boolean signInAgain, Instant resetAt) {
    }

    /** The demo as this deployment configures it. */
    private record Demo(String tenantCode, String email, String password) {

        String rootPath() {
            return "/" + tenantCode + "/";
        }
    }

    /**
     * Resets the caller's tenant, which must be the demo tenant.
     *
     * @param confirmation the demo tenant's code, typed by the caller. A request
     *                     that does not name it is refused, so nothing resets the
     *                     demo by accident -- a script replaying requests, or a
     *                     probe posting an empty body to every route.
     */
    @Transactional
    @PreAuthorize("hasAuthority('DEMO_RESET')")
    public Outcome reset(String confirmation) {
        Demo demo = configured().orElseThrow(DemoResetService::unavailable);
        UUID caller = TenantContext.requireTenantId();
        UUID demoTenant = demoTenantId(demo.tenantCode()).orElseThrow(DemoResetService::unavailable);
        if (!caller.equals(demoTenant)) {
            throw unavailable();
        }
        if (!demo.tenantCode().equals(confirmation == null ? null : confirmation.strip())) {
            throw new BusinessRuleViolationException("demo-reset-unconfirmed",
                    "To reset the demo, confirm with its tenant code, " + demo.tenantCode());
        }
        Optional<UUID> callerUser = callerUserId();
        Outcome outcome = resetTenant(demoTenant, demo);
        boolean signInAgain = callerUser.map(id -> !userExists(id)).orElse(false);
        return new Outcome(outcome.tenantCode(), outcome.rowsDeleted(), signInAgain, outcome.resetAt());
    }

    /**
     * The timed reset ({@code lms.demo.reset-cron}). Does nothing when the demo
     * is off, has no administrator password, or has not been seeded yet.
     */
    public void resetOnSchedule() {
        Optional<Demo> demo;
        try {
            demo = configured();
        } catch (BusinessRuleViolationException notConfigured) {
            log.warn("Scheduled demo reset skipped: {}", notConfigured.getMessage());
            return;
        }
        if (demo.isEmpty()) {
            return;
        }
        Optional<UUID> demoTenant = transactions.execute(status -> demoTenantId(demo.get().tenantCode()));
        if (demoTenant == null || demoTenant.isEmpty()) {
            return;
        }
        Outcome outcome = TenantContext.callWith(demoTenant.get(), demo.get().rootPath(),
                () -> transactions.execute(status -> resetTenant(demoTenant.get(), demo.get())));
        log.info("Scheduled demo reset of '{}' deleted {} rows", demo.get().tenantCode(),
                outcome == null ? 0 : outcome.rowsDeleted());
    }

    private Outcome resetTenant(UUID tenantId, Demo demo) {
        jdbc.sql("SELECT 1 FROM (SELECT pg_advisory_xact_lock(hashtextextended(:key, 0))) held")
                .param("key", "demo-reset:" + tenantId)
                .query(Integer.class)
                .single();

        long deleted = wipe(tenantId);
        seeder.seed(demo.tenantCode(), demo.email(), demo.password());
        // The tenant row survives, so the trigger that gives a new tenant its
        // default access policies does not fire again; the same function does.
        jdbc.sql("SELECT seed_default_access_policies(:tenantId)")
                .param("tenantId", tenantId)
                .query((rs, row) -> row)
                .list();
        audit.record("DEMO_RESET", "TENANT", tenantId, null,
                Json.object("tenantCode", demo.tenantCode(), "rowsDeleted", Long.toString(deleted)));
        return new Outcome(demo.tenantCode(), deleted, false, Instant.now());
    }

    /**
     * Deletes every row the tenant owns, children first. The tenant row itself
     * stays. Runs inside the caller's transaction and tenant scope.
     *
     * @return the number of rows deleted
     */
    long wipe(UUID tenantId) {
        List<String> tenantTables = jdbc.sql("""
                        SELECT c.table_name
                          FROM information_schema.columns c
                          JOIN information_schema.tables t
                            ON t.table_schema = c.table_schema AND t.table_name = c.table_name
                         WHERE c.table_schema = 'public'
                           AND c.column_name = 'tenant_id'
                           AND t.table_type = 'BASE TABLE'
                        """)
                .query(String.class)
                .list();
        List<DemoResetPlan.Reference> references = jdbc.sql("""
                        SELECT child.relname AS child, parent.relname AS parent
                          FROM pg_constraint k
                          JOIN pg_class child ON child.oid = k.conrelid
                          JOIN pg_class parent ON parent.oid = k.confrelid
                          JOIN pg_namespace n ON n.oid = child.relnamespace
                         WHERE k.contype = 'f' AND n.nspname = 'public'
                        """)
                .query((rs, row) -> new DemoResetPlan.Reference(rs.getString("child"), rs.getString("parent")))
                .list();

        long deleted = 0;
        for (String table : DemoResetPlan.deletionOrder(tenantTables, references)) {
            Long rows = jdbc.sql("SELECT demo_reset_wipe_table(:tenantId, :table)")
                    .param("tenantId", tenantId)
                    .param("table", table)
                    .query(Long.class)
                    .single();
            deleted += rows == null ? 0 : rows;
        }
        return deleted;
    }

    private Optional<Demo> configured() {
        if (!environment.getProperty("lms.demo.enabled", Boolean.class, false)) {
            return Optional.empty();
        }
        String password = environment.getProperty("lms.demo.admin-password", "");
        if (password.isBlank()) {
            // Enabled with nobody to sign in as: a reset would leave the demo
            // without its administrator, so refuse rather than half-work.
            throw new BusinessRuleViolationException("demo-not-configured",
                    "The demo has no administrator password, so it cannot be seeded again");
        }
        return Optional.of(new Demo(
                environment.getProperty("lms.demo.tenant-code", "demo"),
                environment.getProperty("lms.demo.admin-email", "admin@demo.local").toLowerCase(Locale.ROOT),
                password));
    }

    private Optional<UUID> demoTenantId(String tenantCode) {
        // The function answers NULL, not no row, for a code nobody has.
        return jdbc.sql("SELECT resolve_tenant_by_code(:code)")
                .param("code", tenantCode)
                .query(UUID.class)
                .list()
                .stream()
                .filter(Objects::nonNull)
                .findFirst();
    }

    private static Optional<UUID> callerUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || authentication.getName() == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(UUID.fromString(authentication.getName()));
        } catch (IllegalArgumentException notAnId) {
            return Optional.empty();
        }
    }

    private boolean userExists(UUID userId) {
        return jdbc.sql("SELECT 1 FROM app_user WHERE id = :id")
                .param("id", userId)
                .query(Integer.class)
                .optional()
                .isPresent();
    }

    private static ResourceNotFoundException unavailable() {
        return new ResourceNotFoundException("Demo reset is not available here");
    }
}
