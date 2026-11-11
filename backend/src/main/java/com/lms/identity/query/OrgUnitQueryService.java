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
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.lms.identity.command.domain.OrgUnit;
import com.lms.shared.query.Search;
import com.lms.shared.query.Slice;
import com.lms.shared.tenant.TenantContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The organisational tree as the caller may see it: their own unit and every
 * unit beneath it, in path order (a parent before its children). Any
 * signed-in caller may read it; the token already carries their place in it.
 */
@Service
@Transactional(readOnly = true)
public class OrgUnitQueryService {

    /**
     * Each unit with its effective jurisdiction: of its ancestors-or-self (the
     * prefixes of its path, read through {@code org_unit_path_unique}), the
     * nearest with a country, and the nearest with a state beneath that one.
     */
    private static final String SELECT = """
            SELECT u.id, u.parent_id, u.slug, u.path, u.name, u.unit_type, u.country_code, u.state_code,
                   u.version, c.country_code AS effective_country, s.state_code AS effective_state
              FROM org_unit u
              LEFT JOIN LATERAL (
                   SELECT a.country_code, a.path FROM org_unit a
                    WHERE a.tenant_id = u.tenant_id AND a.country_code IS NOT NULL
                      AND a.path = ANY (ARRAY(SELECT left(u.path, i) FROM generate_series(2, length(u.path)) i
                                               WHERE substr(u.path, i, 1) = '/'))
                    ORDER BY length(a.path) DESC LIMIT 1) c ON true
              LEFT JOIN LATERAL (
                   SELECT a.state_code FROM org_unit a
                    WHERE a.tenant_id = u.tenant_id AND a.state_code IS NOT NULL
                      AND length(a.path) > length(c.path)
                      AND a.path = ANY (ARRAY(SELECT left(u.path, i) FROM generate_series(2, length(u.path)) i
                                               WHERE substr(u.path, i, 1) = '/'))
                    ORDER BY length(a.path) DESC LIMIT 1) s ON true
            """;

    private final JdbcClient jdbc;

    public OrgUnitQueryService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * A page of the caller's subtree, keyset on the path. Served by
     * {@code org_unit_path_prefix_idx}.
     *
     * @param q part of the unit's name or path, any case
     */
    public Slice<OrgUnitView> list(String q, String cursor, Integer limit) {
        int pageSize = Slice.clampLimit(limit);
        List<OrgUnitView> rows = jdbc.sql(SELECT + """
                         WHERE u.tenant_id = :tenantId
                           AND u.path LIKE :orgPath || '%'
                           AND (CAST(:after AS text) IS NULL OR u.path > CAST(:after AS text))
                           AND (CAST(:q AS text) IS NULL OR lower(u.name) LIKE CAST(:q AS text)
                                OR u.path LIKE CAST(:q AS text))
                         ORDER BY u.path
                         LIMIT :limit
                        """)
                .param("tenantId", TenantContext.requireTenantId())
                .param("orgPath", TenantContext.requireOrgPath())
                .param("after", cursor == null || cursor.isBlank() ? null : cursor)
                .param("q", Search.containsPattern(q))
                .param("limit", pageSize + 1)
                .query((rs, row) -> view(rs))
                .list();
        return Slice.of(rows, pageSize, OrgUnitView::path);
    }

    /** One unit at or beneath the caller's; empty for any other, of this tenant or not. */
    public Optional<OrgUnitView> find(UUID id) {
        return jdbc.sql(SELECT + """
                         WHERE u.tenant_id = :tenantId AND u.id = :id
                           AND u.path LIKE :orgPath || '%'
                        """)
                .param("tenantId", TenantContext.requireTenantId())
                .param("orgPath", TenantContext.requireOrgPath())
                .param("id", id)
                .query((rs, row) -> view(rs))
                .optional();
    }

    private static OrgUnitView view(ResultSet rs) throws SQLException {
        String type = rs.getString("unit_type");
        return new OrgUnitView(rs.getObject("id", UUID.class), rs.getObject("parent_id", UUID.class),
                rs.getString("slug"), rs.getString("path"), rs.getString("name"), type,
                OrgUnit.UnitType.valueOf(type).rank(), rs.getString("country_code"), rs.getString("state_code"),
                rs.getString("effective_country"), rs.getString("effective_state"), rs.getLong("version"));
    }
}
