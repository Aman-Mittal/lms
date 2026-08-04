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
package com.lms.acceptance;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.lms.shared.tenant.TenantContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Builds test data through the same tenant-scoped path production code uses.
 *
 * <p>Every write runs inside {@link TenantContext#runWith}, because row-level
 * security applies to inserts too: the {@code WITH CHECK} clause rejects a row
 * whose {@code tenant_id} does not match the scope on the connection. Setting
 * the scope <em>before</em> opening the transaction matters -- the transaction
 * manager publishes it during transaction start, so a scope set afterwards
 * arrives too late.
 */
@Component
public class TestFixtures {

    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;
    private final PasswordEncoder passwordEncoder;

    public TestFixtures(JdbcClient jdbc, PlatformTransactionManager transactionManager,
                        PasswordEncoder passwordEncoder) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(transactionManager);
        this.passwordEncoder = passwordEncoder;
    }

    public UUID createTenant(String code, String name) {
        UUID id = UUID.randomUUID();
        inTenant(id, null, () -> jdbc.sql("""
                        INSERT INTO tenant (id, code, name, status)
                        VALUES (:id, :code, :name, 'ACTIVE')
                        """)
                .param("id", id).param("code", code).param("name", name)
                .update());
        return id;
    }

    public UUID createOrgUnit(UUID tenantId, String path, String name, String type, UUID parentId) {
        UUID id = UUID.randomUUID();
        String slug = slugOf(path);
        inTenant(tenantId, path, () -> jdbc.sql("""
                        INSERT INTO org_unit (id, tenant_id, parent_id, slug, path, name, unit_type)
                        VALUES (:id, :tenantId, :parentId, :slug, :path, :name, :type)
                        """)
                .param("id", id).param("tenantId", tenantId).param("parentId", parentId)
                .param("slug", slug).param("path", path).param("name", name).param("type", type)
                .update());
        return id;
    }

    public UUID createUser(UUID tenantId, UUID orgUnitId, String orgPath, String email, String rawPassword) {
        UUID id = UUID.randomUUID();
        String hash = passwordEncoder.encode(rawPassword);
        inTenant(tenantId, orgPath, () -> jdbc.sql("""
                        INSERT INTO app_user (id, tenant_id, org_unit_id, email, password_hash, full_name, status)
                        VALUES (:id, :tenantId, :orgUnitId, :email, :hash, :name, 'ACTIVE')
                        """)
                .param("id", id).param("tenantId", tenantId).param("orgUnitId", orgUnitId)
                .param("email", email).param("hash", hash).param("name", email)
                .update());
        return id;
    }

    public void suspendUser(UUID tenantId, String email) {
        inTenant(tenantId, null, () -> jdbc.sql(
                        "UPDATE app_user SET status = 'SUSPENDED' WHERE tenant_id = :tenantId AND email = :email")
                .param("tenantId", tenantId).param("email", email)
                .update());
    }

    /**
     * Reads every row of {@code app_user} with <em>no</em> tenant predicate.
     *
     * <p>The point of the test suite: this query is wrong by construction, and
     * row-level security must make it safe anyway.
     */
    public List<String> listAllUserEmailsWithoutTenantPredicate(UUID scopedTenant) {
        // Scope first, transaction second -- the transaction manager reads the
        // scope while starting the transaction, so setting it inside would be
        // too late and the query would run unscoped.
        if (scopedTenant == null) {
            return transactions.execute(status -> queryEmails());
        }
        return TenantContext.callWith(scopedTenant, null,
                () -> transactions.execute(status -> queryEmails()));
    }

    private List<String> queryEmails() {
        return jdbc.sql("SELECT email FROM app_user").query(String.class).list();
    }

    public List<Map<String, Object>> listSubtree(UUID tenantId, String orgPath) {
        return inTenant(tenantId, orgPath, () -> jdbc
                .sql("SELECT path, name FROM org_unit WHERE path LIKE :prefix || '%' ORDER BY path")
                .param("prefix", orgPath)
                .query()
                .listOfRows());
    }

    private <T> T inTenant(UUID tenantId, String orgPath, java.util.function.Supplier<T> action) {
        // Scope first, transaction second. The transaction manager reads the
        // scope while starting the transaction, so the ordering is load-bearing.
        return TenantContext.callWith(tenantId, orgPath, () -> transactions.execute(status -> action.get()));
    }

    private static String slugOf(String path) {
        String trimmed = path.replaceAll("^/|/$", "");
        int lastSlash = trimmed.lastIndexOf('/');
        return lastSlash < 0 ? trimmed : trimmed.substring(lastSlash + 1);
    }
}
