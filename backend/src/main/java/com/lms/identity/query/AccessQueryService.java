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

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.lms.shared.query.Slice;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Read side for access policies and the user attributes they read.
 *
 * <p>Guarded by the same permission as writing them. What a tenant's access
 * rules are is itself sensitive: a list of the limits in force is a map of
 * where they are not.
 */
@Service
@Transactional(readOnly = true)
@PreAuthorize("hasAuthority('ACCESS_POLICY_MANAGE')")
public class AccessQueryService {

    private static final String SELECT = """
            SELECT id, code, action, effect, description, condition::text AS condition,
                   enabled, created_at, updated_at
              FROM access_policy
            """;

    private final JdbcClient jdbc;
    private final JsonMapper json;

    public AccessQueryService(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /**
     * A page of policies in code order, optionally for one action.
     *
     * <p>Keyset on the code, which is unique per tenant. Narrowed to an action
     * it rides {@code access_policy_action_idx}; unnarrowed, the unique
     * constraint's index.
     */
    public Slice<AccessPolicyView> list(String action, String cursor, Integer limit) {
        int pageSize = Slice.clampLimit(limit);
        List<AccessPolicyView> rows = jdbc.sql(SELECT + """
                         WHERE (CAST(:action AS text) IS NULL OR action = CAST(:action AS text))
                           AND (CAST(:cursor AS text) IS NULL OR code > CAST(:cursor AS text))
                         ORDER BY code
                         LIMIT :limit
                        """)
                .param("action", action)
                .param("cursor", cursor)
                .param("limit", pageSize + 1)
                .query(this::toView)
                .list();
        return Slice.of(rows, pageSize, AccessPolicyView::code);
    }

    public Optional<AccessPolicyView> findById(UUID id) {
        return jdbc.sql(SELECT + " WHERE id = :id")
                .param("id", id)
                .query(this::toView)
                .optional();
    }

    public Optional<UserAccessAttributesView> attributesOf(UUID userId) {
        return jdbc.sql("""
                        SELECT id, region, freight_modes, partner_id, approval_limit
                          FROM app_user
                         WHERE id = :id
                        """)
                .param("id", userId)
                .query((rs, row) -> new UserAccessAttributesView(
                        rs.getObject("id", UUID.class),
                        rs.getString("region"),
                        rs.getArray("freight_modes") == null ? null
                                : Arrays.stream((Object[]) rs.getArray("freight_modes").getArray())
                                        .map(String::valueOf).toList(),
                        rs.getObject("partner_id", UUID.class),
                        rs.getBigDecimal("approval_limit")))
                .optional();
    }

    @SuppressWarnings("unchecked")
    private AccessPolicyView toView(ResultSet rs, int row) throws SQLException {
        return new AccessPolicyView(
                rs.getObject("id", UUID.class),
                rs.getString("code"),
                rs.getString("action"),
                rs.getString("effect"),
                rs.getString("description"),
                json.readValue(rs.getString("condition"), Map.class),
                rs.getBoolean("enabled"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }
}
