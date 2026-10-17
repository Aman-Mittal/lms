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

import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import com.lms.shared.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Gives a fresh deployment somebody who can sign in.
 *
 * <p>The platform has no self-service tenant registration, so an empty
 * database is a service nobody can use: every endpoint but login needs a
 * token, and login needs a tenant and a user that only a migration or a
 * console session could create. On Render's free tier the database is also
 * recreated every thirty days, so "somebody ran some SQL once" is not an
 * answer either.
 *
 * <p>Off unless {@code LMS_DEMO_SEED=true}. When on, it ensures one tenant, a
 * root organisational unit, an administrator role holding every permission in
 * the vocabulary, and one administrator. Every step is "create if absent", so
 * it runs on every start and does nothing once the rows exist -- and the role
 * picks up permissions added by later migrations, which is what keeps the demo
 * administrator able to reach new modules without anybody remembering to
 * grant them.
 *
 * <p>Three choices are deliberate:
 *
 * <ul>
 *   <li>The switch is read from the {@link Environment} at run time rather than
 *       expressed as {@code @ConditionalOnProperty}. AOT evaluates conditions
 *       when the native image is built and freezes the answer, so a
 *       conditional bean would be either always present or never present in
 *       the shipped binary, whatever the deployment later said.
 *   <li>There is no default password. A seeded account with a password written
 *       in the repository is a public account. Enabled without one, the seeder
 *       logs why it did nothing and the service still starts -- a demo that
 *       will not boot is worse than one with nobody to sign in as.
 *   <li>An existing administrator's password is never reset. Restarting the
 *       service must not undo a password somebody changed on purpose.
 * </ul>
 */
@Component
public class DemoTenantSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoTenantSeeder.class);

    static final String ADMIN_ROLE_CODE = "ADMIN";

    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;
    private final PasswordEncoder passwordEncoder;
    private final Environment environment;

    public DemoTenantSeeder(JdbcClient jdbc, PlatformTransactionManager transactionManager,
                            PasswordEncoder passwordEncoder, Environment environment) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(transactionManager);
        this.passwordEncoder = passwordEncoder;
        this.environment = environment;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!environment.getProperty("lms.demo.enabled", Boolean.class, false)) {
            return;
        }

        String tenantCode = environment.getProperty("lms.demo.tenant-code", "demo");
        String email = environment.getProperty("lms.demo.admin-email", "admin@demo.local")
                .toLowerCase(Locale.ROOT);
        String password = environment.getProperty("lms.demo.admin-password", "");

        if (password.isBlank()) {
            log.error("""
                    LMS_DEMO_SEED is on but DEMO_ADMIN_PASSWORD is empty -- no demo \
                    administrator was created. Set a password and restart.""");
            return;
        }

        seed(tenantCode, email, password);
        log.info("Demo tenant '{}' is ready; sign in as {}", tenantCode, email);
    }

    /** Ensures the demo tenant exists and returns its id. Safe to call repeatedly. */
    UUID seed(String tenantCode, String email, String rawPassword) {
        UUID tenantId = existingTenant(tenantCode).orElseGet(UUID::randomUUID);
        String rootPath = "/" + tenantCode + "/";

        // Scope first, transaction second: the transaction manager publishes
        // the tenant onto the connection as the transaction starts.
        TenantContext.runWith(tenantId, rootPath, () -> transactions.executeWithoutResult(status -> {
            jdbc.sql("""
                            INSERT INTO tenant (id, code, name, status)
                            VALUES (:id, :code, :name, 'ACTIVE')
                            ON CONFLICT (code) DO NOTHING
                            """)
                    .param("id", tenantId)
                    .param("code", tenantCode)
                    .param("name", "Demo Logistics")
                    .update();

            UUID orgUnitId = ensureRootOrgUnit(tenantId, tenantCode, rootPath);
            UUID roleId = ensureAdminRole(tenantId);
            grantEveryPermission(tenantId, roleId);
            UUID userId = ensureAdministrator(tenantId, orgUnitId, email, rawPassword);

            jdbc.sql("""
                            INSERT INTO user_role (tenant_id, user_id, role_id)
                            VALUES (:tenantId, :userId, :roleId)
                            ON CONFLICT DO NOTHING
                            """)
                    .param("tenantId", tenantId)
                    .param("userId", userId)
                    .param("roleId", roleId)
                    .update();
        }));
        return tenantId;
    }

    /**
     * The tenant's id, if it was created on an earlier start.
     *
     * <p>Through the login lookup rather than a SELECT: with no scope bound,
     * row-level security hides the tenant table, and this function is the one
     * sanctioned way past it.
     */
    private Optional<UUID> existingTenant(String tenantCode) {
        return Optional.ofNullable(transactions.execute(status -> jdbc
                .sql("SELECT resolve_tenant_by_code(:code)")
                .param("code", tenantCode)
                .query(UUID.class)
                .optional()
                .orElse(null)));
    }

    private UUID ensureRootOrgUnit(UUID tenantId, String slug, String path) {
        Optional<UUID> existing = jdbc.sql("""
                        SELECT id FROM org_unit
                         WHERE tenant_id = :tenantId AND parent_id IS NULL
                        """)
                .param("tenantId", tenantId)
                .query(UUID.class)
                .optional();
        if (existing.isPresent()) {
            return existing.get();
        }

        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO org_unit (id, tenant_id, parent_id, slug, path, name, unit_type)
                        VALUES (:id, :tenantId, NULL, :slug, :path, 'Head Office', 'HQ')
                        """)
                .param("id", id)
                .param("tenantId", tenantId)
                .param("slug", slug)
                .param("path", path)
                .update();
        return id;
    }

    private UUID ensureAdminRole(UUID tenantId) {
        jdbc.sql("""
                        INSERT INTO role (id, tenant_id, code, name)
                        VALUES (:id, :tenantId, :code, 'Administrator')
                        ON CONFLICT (tenant_id, code) DO NOTHING
                        """)
                .param("id", UUID.randomUUID())
                .param("tenantId", tenantId)
                .param("code", ADMIN_ROLE_CODE)
                .update();

        return jdbc.sql("SELECT id FROM role WHERE tenant_id = :tenantId AND code = :code")
                .param("tenantId", tenantId)
                .param("code", ADMIN_ROLE_CODE)
                .query(UUID.class)
                .single();
    }

    /**
     * Grants whatever the vocabulary holds today.
     *
     * <p>Re-run on every start, so a permission introduced by a later
     * migration reaches the demo administrator on the next deploy.
     */
    private void grantEveryPermission(UUID tenantId, UUID roleId) {
        jdbc.sql("""
                        INSERT INTO role_permission (tenant_id, role_id, permission_id)
                        SELECT :tenantId, :roleId, p.id FROM permission p
                        ON CONFLICT DO NOTHING
                        """)
                .param("tenantId", tenantId)
                .param("roleId", roleId)
                .update();
    }

    private UUID ensureAdministrator(UUID tenantId, UUID orgUnitId, String email,
                                     String rawPassword) {
        Optional<UUID> existing = jdbc.sql("""
                        SELECT id FROM app_user
                         WHERE tenant_id = :tenantId AND lower(email) = :email
                        """)
                .param("tenantId", tenantId)
                .param("email", email)
                .query(UUID.class)
                .optional();
        if (existing.isPresent()) {
            return existing.get();
        }

        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO app_user (id, tenant_id, org_unit_id, email, password_hash,
                                              full_name, status)
                        VALUES (:id, :tenantId, :orgUnitId, :email, :hash,
                                'Demo Administrator', 'ACTIVE')
                        """)
                .param("id", id)
                .param("tenantId", tenantId)
                .param("orgUnitId", orgUnitId)
                .param("email", email)
                .param("hash", passwordEncoder.encode(rawPassword))
                .update();
        return id;
    }
}
