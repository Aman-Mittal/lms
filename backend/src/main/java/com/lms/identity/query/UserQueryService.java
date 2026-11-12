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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
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

    /**
     * The caller as their profile page shows them. Read under the caller's
     * scope: their tenant, and their unit by the path the token carries.
     *
     * @param email       the token's, used when the user has no row (a service token)
     * @param fullName    the token's, likewise
     * @param permissions the token's, always
     */
    public MeView me(UUID userId, String email, String fullName, List<String> permissions) {
        UUID tenantId = TenantContext.requireTenantId();
        String orgPath = TenantContext.requireOrgPath();
        record Head(String tenantCode, String tenantName, UUID orgUnitId, String orgUnitName) {
        }
        Head head = jdbc.sql("""
                        SELECT t.code AS tenant_code, t.name AS tenant_name,
                               o.id AS org_unit_id, o.name AS org_unit_name
                          FROM tenant t
                          LEFT JOIN org_unit o ON o.tenant_id = t.id AND o.path = :orgPath
                         WHERE t.id = :tenantId
                        """)
                .param("tenantId", tenantId)
                .param("orgPath", orgPath)
                .query((rs, row) -> new Head(rs.getString("tenant_code"), rs.getString("tenant_name"),
                        rs.getObject("org_unit_id", UUID.class), rs.getString("org_unit_name")))
                .optional()
                .orElse(new Head(null, null, null, null));
        Optional<UserView> user = userId == null ? Optional.empty() : findById(userId);
        List<String> roles = userId == null ? List.of() : jdbc.sql("""
                        SELECT r.code
                          FROM user_role ur
                          JOIN role r ON r.id = ur.role_id
                         WHERE ur.tenant_id = :tenantId AND ur.user_id = :userId
                         ORDER BY r.code
                        """)
                .param("tenantId", tenantId)
                .param("userId", userId)
                .query(String.class)
                .list();
        UUID orgUnitId = user.map(UserView::orgUnitId).orElse(head.orgUnitId());
        String path = user.map(UserView::orgPath).orElse(orgPath);
        List<String> sortedPermissions = permissions.stream().sorted().toList();
        return new MeView(userId, user.map(UserView::email).orElse(email),
                user.map(UserView::fullName).orElse(fullName), tenantId, head.tenantCode(),
                head.tenantName(), orgUnitId, path,
                user.map(UserView::orgName).orElse(head.orgUnitName()), roles, sortedPermissions,
                cacheEpoch(tenantId, userId, orgUnitId, path, roles, sortedPermissions));
    }

    /**
     * A digest of everything that decides what this caller may see: who and
     * where they are, the roles they hold and the permissions in force.
     *
     * <p>Computed from values this read has already fetched, so it costs no
     * query and needs no counter or table to be bumped by every role, grant
     * and unit write (and so none can be forgotten). Anything that changes the
     * caller's rights changes one of the inputs; nothing else does. A console
     * discards its cache when the value differs from the one it stored.
     */
    static String cacheEpoch(UUID tenantId, UUID userId, UUID orgUnitId, String orgPath,
                             List<String> roles, List<String> permissions) {
        StringBuilder in = new StringBuilder();
        in.append(tenantId).append('|').append(userId).append('|').append(orgUnitId).append('|')
                .append(orgPath).append('|').append(String.join(",", roles)).append('|')
                .append(String.join(",", permissions));
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(in.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 12);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is a required JDK algorithm", e);
        }
    }
}
