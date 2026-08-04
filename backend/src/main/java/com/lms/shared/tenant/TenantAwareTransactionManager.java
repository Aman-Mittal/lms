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

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.TransactionDefinition;

/**
 * Publishes the current tenant onto the database connection so that Postgres
 * row-level security policies can enforce isolation.
 *
 * <p>This is layer three of DOCS/adr/0005 -- the fail-safe. Every RLS policy
 * compares {@code tenant_id} against {@code current_setting('app.tenant_id')},
 * so a query that forgets its tenant predicate returns <em>zero rows</em>
 * rather than another tenant's data. A visible bug instead of a silent breach.
 *
 * <p>Two details carry the weight:
 *
 * <ul>
 *   <li>{@code set_config(..., is_local => true)} is the parameterised
 *       equivalent of {@code SET LOCAL}. {@code SET LOCAL} cannot take a bind
 *       parameter, so the alternative would be string-concatenating a value
 *       into DDL-ish SQL. This form is injection-safe by construction.</li>
 *   <li>Being <em>local</em> scopes the setting to the transaction. Connections
 *       are pooled and reused across tenants; a session-level {@code SET} would
 *       leak one tenant's scope into the next request that borrowed the same
 *       connection.</li>
 * </ul>
 *
 * <p>When no tenant is bound -- Flyway migrations, the Modulith event registry,
 * the login endpoint before a tenant is known -- the setting is written as an
 * empty string rather than skipped. Skipping would leave whatever the previous
 * transaction set, and an empty value matches no tenant, which is the safe
 * outcome.
 */
public class TenantAwareTransactionManager extends JdbcTransactionManager {

    private static final Logger log = LoggerFactory.getLogger(TenantAwareTransactionManager.class);

    private static final String SET_TENANT_SQL = "SELECT set_config('app.tenant_id', ?, true)";
    private static final String SET_ROLE_SQL = "SET LOCAL ROLE lms_app";

    /**
     * Whether the restricted role is reachable. Resolved on first use and
     * cached: if the deployment could not create or grant {@code lms_app}, the
     * statement would fail on every single transaction.
     */
    private volatile Boolean appRoleAvailable;

    public TenantAwareTransactionManager(DataSource dataSource) {
        super(dataSource);
    }

    @Override
    protected void prepareTransactionalConnection(Connection con, TransactionDefinition definition)
            throws SQLException {
        super.prepareTransactionalConnection(con, definition);

        // Drop superuser and owner status for the duration of this transaction.
        // Without this, Postgres exempts those roles from row-level security and
        // every policy becomes decorative -- see V3__application_role.sql.
        if (appRoleAvailable == null || appRoleAvailable) {
            try (Statement statement = con.createStatement()) {
                statement.execute(SET_ROLE_SQL);
                appRoleAvailable = Boolean.TRUE;
            } catch (SQLException e) {
                appRoleAvailable = Boolean.FALSE;
                log.error("""
                        Could not SET ROLE lms_app: {}
                        Row-level security may not be enforced for this connection. If the \
                        database user is a superuser, tenant isolation policies are bypassed \
                        entirely. See DOCS/adr/0005.""", e.getMessage());
            }
        }

        String tenantId = TenantContext.current()
                .map(scope -> scope.tenantId().toString())
                .orElse("");

        try (PreparedStatement statement = con.prepareStatement(SET_TENANT_SQL)) {
            statement.setString(1, tenantId);
            statement.execute();
        }
    }
}
