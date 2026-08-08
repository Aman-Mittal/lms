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
package com.lms.identity.query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.lms.shared.tenant.TenantContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read side for identity.
 *
 * <p>Hand-written SQL over {@link JdbcClient}, returning flat
 * {@link UserView} projections. It deliberately does not touch repositories or
 * load aggregates: queries are allowed to span aggregate boundaries, and doing
 * so through the write model would mean N+1 loads for data nobody intends to
 * modify.
 *
 * <p>Every method is {@code @Transactional(readOnly = true)} and that is not
 * decoration. The tenant scope is published onto the connection when a
 * transaction starts, so a query outside one carries no scope and row-level
 * security correctly returns nothing.
 */
@Service
@Transactional(readOnly = true)
public class UserQueryService {

    private static final String SELECT = """
            SELECT u.id, u.email, u.full_name, u.status,
                   u.org_unit_id, o.path AS org_path, o.name AS org_name,
                   u.last_login_at
              FROM app_user u
              JOIN org_unit o ON o.id = u.org_unit_id
            """;

    private final JdbcClient jdbc;

    public UserQueryService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Users the caller may see: their own organisational unit and everything
     * beneath it (vision document 2.1). Sibling subtrees are excluded.
     */
    public List<UserView> findVisible() {
        return jdbc.sql(SELECT + " WHERE o.path LIKE :orgPath || '%' ORDER BY u.full_name")
                .param("orgPath", TenantContext.requireOrgPath())
                .query(UserView.class)
                .list();
    }

    public Optional<UserView> findById(UUID id) {
        return jdbc.sql(SELECT + " WHERE u.id = :id")
                .param("id", id)
                .query(UserView.class)
                .optional();
    }

    /** Permission codes granted to a user through all of their roles. */
    public List<String> findPermissions(UUID userId) {
        return jdbc.sql("""
                        SELECT DISTINCT p.code
                          FROM permission p
                          JOIN role_permission rp ON rp.permission_id = p.id
                          JOIN user_role ur ON ur.role_id = rp.role_id
                         WHERE ur.user_id = :userId
                         ORDER BY p.code
                        """)
                .param("userId", userId)
                .query(String.class)
                .list();
    }
}
