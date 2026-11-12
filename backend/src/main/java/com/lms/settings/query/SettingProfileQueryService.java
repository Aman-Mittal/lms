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
package com.lms.settings.query;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;

import com.lms.settings.api.ScopeType;
import com.lms.settings.command.ScopeLabels;
import com.lms.settings.command.ScopeReferences;
import com.lms.settings.command.SettingCalendar;
import com.lms.shared.error.BusinessRuleViolationException;
import com.lms.shared.query.Search;
import com.lms.shared.query.Slice;
import com.lms.shared.tenant.TenantContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads setting profiles. The administrative reads need {@code SETTINGS_READ}
 * and never show a USER profile: a person's preferences are theirs, and are
 * read through {@link #mine}.
 *
 * <p>The listing is keyset on {@code (scope_type, created_at, id)}, served by
 * {@code setting_profile_list_idx}.
 */
@Service
@Transactional(readOnly = true)
public class SettingProfileQueryService {

    private static final TypeReference<List<Map<String, Object>>> VERSIONS = new TypeReference<>() {
    };

    private static final String SELECT = """
            SELECT p.id, p.scope_type, p.scope_id, p.country, p.state, p.name, p.version,
                   p.created_at, p.updated_at, p.match_org_unit_id, p.match_customer_id, p.match_vendor_id,
                   p.match_vehicle_id, p.match_freight_mode, p.priority,
                   (SELECT u.unit_type || '|' || u.name
                      FROM org_unit u
                     WHERE u.id = CASE p.scope_type WHEN 'ORG_UNIT' THEN p.scope_id
                                                    ELSE p.match_org_unit_id END) AS unit,
                   CASE WHEN p.match_customer_id IS NOT NULL THEN
                       (SELECT b.legal_name FROM business_partner b WHERE b.id = p.match_customer_id) END AS customer,
                   CASE WHEN p.match_vendor_id IS NOT NULL THEN
                       (SELECT b.legal_name FROM business_partner b WHERE b.id = p.match_vendor_id) END AS vendor,
                   CASE WHEN p.match_vehicle_id IS NOT NULL THEN
                       (SELECT x.registration_no FROM vehicle x WHERE x.id = p.match_vehicle_id) END AS vehicle,
                   CASE p.scope_type
                       WHEN 'ORG_UNIT' THEN (SELECT u.name || ' (' || u.path || ')'
                                               FROM org_unit u WHERE u.id = p.scope_id)
                       WHEN 'CUSTOMER' THEN (SELECT b.code || ' ' || b.legal_name
                                               FROM business_partner b WHERE b.id = p.scope_id)
                       WHEN 'VENDOR' THEN (SELECT b.code || ' ' || b.legal_name
                                             FROM business_partner b WHERE b.id = p.scope_id)
                       WHEN 'VEHICLE' THEN (SELECT x.registration_no FROM vehicle x WHERE x.id = p.scope_id)
                       WHEN 'CONTRACT' THEN (SELECT k.code FROM client_contract k
                                              WHERE coalesce(k.family_id, k.id) = p.scope_id
                                              ORDER BY k.effective_from DESC LIMIT 1)
                   END AS scope_label,
                   (SELECT jsonb_agg(jsonb_build_object('key', v.setting_key, 'effectiveFrom', v.effective_from,
                                                        'value', v.value)
                                     ORDER BY v.setting_key, v.effective_from NULLS FIRST)
                      FROM setting_value v
                     WHERE v.profile_id = p.id)::text AS profile_values
              FROM setting_profile p
            """;

    private final JdbcClient jdbc;
    private final JsonMapper json;
    private final SettingCalendar calendar;

    public SettingProfileQueryService(JdbcClient jdbc, JsonMapper json, SettingCalendar calendar) {
        this.jdbc = jdbc;
        this.json = json;
        this.calendar = calendar;
    }

    /**
     * @param scopeType only profiles of this type; null for every type but USER
     * @param q         part of the profile's name, any case
     */
    @PreAuthorize("hasAnyAuthority('SETTINGS_READ', 'SETTINGS_MANAGE', 'PLATFORM_SETTINGS_MANAGE')")
    public Slice<SettingProfileView> list(ScopeType scopeType, String q, String cursor, Integer limit) {
        if (scopeType == ScopeType.USER
                || (scopeType == ScopeType.PLATFORM && !ScopeReferences.isPlatformOperator())) {
            return Slice.empty();
        }
        int pageSize = Slice.clampLimit(limit);
        Cursor after = Cursor.parse(cursor);
        LocalDate today = calendar.today();
        LocalDate platformToday = calendar.platformToday();
        List<SettingProfileView> rows = jdbc.sql(SELECT + """
                         WHERE (p.tenant_id = :tenantId
                                OR (p.tenant_id IS NULL AND CAST(:operator AS boolean)))
                           AND p.scope_type <> 'USER'
                           AND (CAST(:scopeType AS text) IS NULL OR p.scope_type = CAST(:scopeType AS text))
                           AND (CAST(:q AS text) IS NULL OR lower(p.name) LIKE CAST(:q AS text))
                           AND (CAST(:afterType AS text) IS NULL
                                OR (p.scope_type, p.created_at, p.id) > (CAST(:afterType AS text),
                                    CAST(:afterAt AS timestamptz), CAST(:afterId AS uuid)))
                         ORDER BY p.scope_type, p.created_at, p.id
                         LIMIT :limit
                        """)
                .param("tenantId", TenantContext.requireTenantId())
                .param("operator", scopeType == ScopeType.PLATFORM)
                .param("scopeType", scopeType == null ? null : scopeType.name())
                .param("q", Search.containsPattern(q))
                .param("afterType", after == null ? null : after.scopeType())
                .param("afterAt", after == null ? null : after.createdAt())
                .param("afterId", after == null ? null : after.id())
                .param("limit", pageSize + 1)
                .query((rs, row) -> view(rs, today, platformToday))
                .list();
        return Slice.of(rows, pageSize, v -> new Cursor(v.scopeType().name(),
                v.createdAt().atOffset(ZoneOffset.UTC), v.id()).encode());
    }

    @PreAuthorize("hasAnyAuthority('SETTINGS_READ', 'SETTINGS_MANAGE', 'PLATFORM_SETTINGS_MANAGE')")
    public Optional<SettingProfileView> find(UUID id) {
        LocalDate today = calendar.today();
        LocalDate platformToday = calendar.platformToday();
        return jdbc.sql(SELECT + """
                         WHERE (p.tenant_id = :tenantId OR (p.tenant_id IS NULL AND CAST(:operator AS boolean)))
                           AND p.id = :id AND p.scope_type <> 'USER'
                        """)
                .param("tenantId", TenantContext.requireTenantId())
                .param("operator", ScopeReferences.isPlatformOperator())
                .param("id", id)
                .query((rs, row) -> view(rs, today, platformToday))
                .optional();
    }

    /** The caller's own preferences profile, if they have saved any. Unguarded: it is theirs. */
    public Optional<SettingProfileView> mine(UUID userId) {
        LocalDate today = calendar.today();
        return jdbc.sql(SELECT + """
                         WHERE p.tenant_id = :tenantId AND p.scope_type = 'USER'
                           AND coalesce(p.scope_id, '00000000-0000-0000-0000-000000000000'::uuid) = :userId
                        """)
                .param("tenantId", TenantContext.requireTenantId())
                .param("userId", userId)
                .query((rs, row) -> view(rs, today, today))
                .optional();
    }

    private SettingProfileView view(ResultSet rs, LocalDate tenantToday, LocalDate platformToday)
            throws SQLException {
        ScopeType scopeType = ScopeType.valueOf(rs.getString("scope_type"));
        LocalDate today = scopeType == ScopeType.PLATFORM ? platformToday : tenantToday;
        String stored = rs.getString("profile_values");
        List<Map<String, Object>> raw = stored == null ? List.of() : json.readValue(stored, VERSIONS);
        // Per key, the version in effect today: the latest dated on or before it.
        Map<String, LocalDate> inForce = new TreeMap<>();
        Map<String, Object> values = new TreeMap<>();
        for (Map<String, Object> v : raw) {
            String key = String.valueOf(v.get("key"));
            LocalDate from = v.get("effectiveFrom") == null ? null : LocalDate.parse(String.valueOf(v.get("effectiveFrom")));
            if (from != null && from.isAfter(today)) {
                continue;
            }
            if (!values.containsKey(key) || (from != null && (inForce.get(key) == null
                    || from.isAfter(inForce.get(key))))) {
                values.put(key, v.get("value"));
                inForce.put(key, from);
            }
        }
        List<SettingProfileView.VersionView> versions = new ArrayList<>();
        for (Map<String, Object> v : raw) {
            String key = String.valueOf(v.get("key"));
            LocalDate from = v.get("effectiveFrom") == null ? null : LocalDate.parse(String.valueOf(v.get("effectiveFrom")));
            String status = from != null && from.isAfter(today) ? "SCHEDULED"
                    : Objects.equals(inForce.get(key), from) ? "IN_FORCE" : "SUPERSEDED";
            versions.add(new SettingProfileView.VersionView(key, from, v.get("value"), status));
        }
        String unit = rs.getString("unit");
        String unitType = unit == null ? null : unit.substring(0, unit.indexOf('|'));
        String unitName = unit == null ? null : unit.substring(unit.indexOf('|') + 1);
        String scopeLabel = rs.getString("scope_label");
        SettingProfileView.ConditionsView conditions = null;
        if (scopeType == ScopeType.ORG_UNIT && scopeLabel != null) {
            scopeLabel = ScopeLabels.level(unitType) + ": " + scopeLabel;
        } else if (scopeType == ScopeType.COMBINATION) {
            scopeLabel = ScopeLabels.combination(new ScopeLabels.Parts(unitType, unitName, rs.getString("customer"),
                    rs.getString("vendor"), rs.getString("vehicle"), rs.getString("match_freight_mode")));
            conditions = new SettingProfileView.ConditionsView(rs.getObject("match_org_unit_id", UUID.class),
                    rs.getObject("match_customer_id", UUID.class), rs.getObject("match_vendor_id", UUID.class),
                    rs.getObject("match_vehicle_id", UUID.class), rs.getString("match_freight_mode"),
                    rs.getInt("priority"));
        }
        return new SettingProfileView(rs.getObject("id", UUID.class), scopeType,
                rs.getObject("scope_id", UUID.class), scopeLabel, rs.getString("country"),
                rs.getString("state"), rs.getString("name"), values, versions, conditions, rs.getLong("version"),
                rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                rs.getObject("updated_at", OffsetDateTime.class).toInstant());
    }

    /**
     * An organisational unit a profile can be scoped to.
     *
     * @param level the unit type's rank, 0 for HQ to 5 for a hub; -1 for a type this module does not know
     */
    public record OrgUnitChoice(UUID id, String name, String path, String unitType, int level) {
    }

    /**
     * The units the caller may scope a profile to: their own and every one
     * beneath it, in path order, keyset on the path. Served by
     * {@code org_unit_path_prefix_idx}.
     */
    @PreAuthorize("hasAnyAuthority('SETTINGS_READ', 'SETTINGS_MANAGE')")
    public Slice<OrgUnitChoice> orgUnits(String q, String cursor, Integer limit) {
        int pageSize = Slice.clampLimit(limit);
        List<OrgUnitChoice> rows = jdbc.sql("""
                        SELECT id, name, path, unit_type FROM org_unit
                         WHERE tenant_id = :tenantId
                           AND path LIKE :orgPath || '%'
                           AND (CAST(:after AS text) IS NULL OR path > CAST(:after AS text))
                           AND (CAST(:q AS text) IS NULL OR lower(name) LIKE CAST(:q AS text)
                                OR path LIKE CAST(:q AS text))
                         ORDER BY path
                         LIMIT :limit
                        """)
                .param("tenantId", TenantContext.requireTenantId())
                .param("orgPath", TenantContext.requireOrgPath())
                .param("after", cursor == null || cursor.isBlank() ? null : cursor)
                .param("q", Search.containsPattern(q))
                .param("limit", pageSize + 1)
                .query((rs, row) -> new OrgUnitChoice(rs.getObject("id", UUID.class), rs.getString("name"),
                        rs.getString("path"), rs.getString("unit_type"), ScopeLabels.rank(rs.getString("unit_type"))))
                .list();
        return Slice.of(rows, pageSize, OrgUnitChoice::path);
    }

    /** Where the previous page stopped; {@code ~} because scope types hold an underscore. */
    record Cursor(String scopeType, OffsetDateTime createdAt, UUID id) {

        String encode() {
            return scopeType + "~" + createdAt.toInstant() + "~" + id;
        }

        static Cursor parse(String cursor) {
            if (cursor == null || cursor.isBlank()) {
                return null;
            }
            String[] parts = cursor.split("~");
            try {
                if (parts.length != 3) {
                    throw new IllegalArgumentException("not a cursor");
                }
                return new Cursor(ScopeType.valueOf(parts[0]).name(),
                        Instant.parse(parts[1]).atOffset(ZoneOffset.UTC), UUID.fromString(parts[2]));
            } catch (DateTimeParseException | IllegalArgumentException e) {
                throw new BusinessRuleViolationException("invalid-cursor",
                        "The cursor was not issued by this listing");
            }
        }
    }
}
