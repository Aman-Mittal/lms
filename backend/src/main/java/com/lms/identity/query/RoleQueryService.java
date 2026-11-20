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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Read side of the roles screen: the tenant's roles, what they grant, and the vocabulary to pick from. */
@Service
@Transactional(readOnly = true)
public class RoleQueryService {

    /** The roles V66 seeds for every tenant. */
    static final Set<String> STANDARD = Set.of("ADMIN", "PLANNER", "DISPATCHER", "FINANCE", "VIEWER");

    private static final String SELECT = """
            SELECT r.id, r.code, r.name, r.version,
                   (SELECT count(*) FROM user_role ur WHERE ur.role_id = r.id) AS user_count
              FROM role r
            """;

    private final JdbcClient jdbc;

    public RoleQueryService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @PreAuthorize("hasAnyAuthority('ROLE_READ', 'ROLE_MANAGE', 'USER_MANAGE')")
    public List<RoleView> list() {
        List<Row> rows = jdbc.sql(SELECT + " ORDER BY r.name, r.id").query(Row.class).list();
        Map<UUID, List<String>> grants = grantsOf(null);
        return rows.stream().map(r -> r.view(grants.getOrDefault(r.id(), List.of()))).toList();
    }

    @PreAuthorize("hasAnyAuthority('ROLE_READ', 'ROLE_MANAGE', 'USER_MANAGE')")
    public Optional<RoleView> find(UUID id) {
        return jdbc.sql(SELECT + " WHERE r.id = :id").param("id", id).query(Row.class).optional()
                .map(r -> r.view(grantsOf(id).getOrDefault(id, List.of())));
    }

    @PreAuthorize("hasAnyAuthority('ROLE_READ', 'ROLE_MANAGE', 'USER_MANAGE')")
    public List<PermissionView> permissions() {
        return jdbc.sql("SELECT code, resource, action FROM permission ORDER BY resource, code")
                .query(PermissionView.class).list();
    }

    private Map<UUID, List<String>> grantsOf(UUID roleId) {
        Map<UUID, List<String>> out = new HashMap<>();
        jdbc.sql("""
                        SELECT rp.role_id, p.code
                          FROM role_permission rp JOIN permission p ON p.id = rp.permission_id
                         WHERE CAST(:id AS uuid) IS NULL OR rp.role_id = :id
                         ORDER BY p.code
                        """)
                .param("id", roleId)
                .query((rs, n) -> Map.entry(rs.getObject("role_id", UUID.class), rs.getString("code")))
                .list()
                .forEach(e -> out.computeIfAbsent(e.getKey(), k -> new ArrayList<>()).add(e.getValue()));
        return out;
    }

    private record Row(UUID id, String code, String name, Long version, long userCount) {
        RoleView view(List<String> permissions) {
            return new RoleView(id, code, name, STANDARD.contains(code), userCount, version, permissions);
        }
    }
}
