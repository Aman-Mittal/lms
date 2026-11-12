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

import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import com.lms.settings.api.Resolved;
import com.lms.settings.api.ScopeType;
import com.lms.settings.api.SettingContext;
import com.lms.settings.api.SettingDefinition;
import com.lms.settings.command.CalendarSettings;
import com.lms.settings.command.ScopeLabels;
import com.lms.settings.command.SettingFold;
import com.lms.settings.command.SettingRegistry;
import com.lms.settings.command.SettingValueRules;
import com.lms.shared.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Effective values for a context: every applicable profile's versions in one
 * statement, then {@link SettingFold}.
 *
 * <p>Applicable means: the platform profiles of the context's jurisdiction
 * (global, its country, its state); the tenant's organisation default; every
 * organisational unit at or above the context's unit (the unit's path starts
 * with theirs); and the customer, vendor, vehicle and user profiles the
 * context names. The statement costs the same however many profiles apply --
 * one read per resolution, whatever the number of keys. The tenant's
 * {@code settings.timeZone} is read in the same statement, to know which day
 * "today" is when the context names none.
 *
 * <p>Unguarded: the platform applies settings on its own account, and the
 * web endpoints that show them are guarded where they are served.
 */
@Service
public class EffectiveSettingsQuery {

    private static final Logger log = LoggerFactory.getLogger(EffectiveSettingsQuery.class);
    private static final TypeReference<Object> ANY = new TypeReference<>() {
    };

    private final JdbcClient jdbc;
    private final SettingRegistry registry;
    private final JsonMapper json;

    public EffectiveSettingsQuery(JdbcClient jdbc, SettingRegistry registry, JsonMapper json) {
        this.jdbc = jdbc;
        this.registry = registry;
        this.json = json;
    }

    /** {@code wanted} in the order given; read in one statement. */
    @Transactional(readOnly = true)
    public Map<String, Resolved<Object>> resolve(Collection<SettingDefinition> wanted,
                                                 SettingContext context) {
        List<SettingDefinition> keys = List.copyOf(wanted);
        if (keys.isEmpty()) {
            return Map.of();
        }
        SettingDefinition zone = registry.find(CalendarSettings.TIME_ZONE.name()).orElseThrow();
        Set<SettingDefinition> read = new LinkedHashSet<>(keys);
        read.add(zone);
        List<SettingFold.Candidate> candidates = candidates(read, context);
        LocalDate asOf = context.asOf() != null ? context.asOf() : today(candidates, zone, context);
        Map<String, Object> defaults = keys.stream().collect(Collectors.toMap(SettingDefinition::key,
                d -> registry.defaultOf(d.key())));
        return SettingFold.fold(keys, defaults, candidates, asOf);
    }

    /** Today, in the context's {@code settings.timeZone}. */
    @Transactional(readOnly = true)
    public LocalDate today(SettingContext context) {
        SettingDefinition zone = registry.find(CalendarSettings.TIME_ZONE.name()).orElseThrow();
        return today(candidates(List.of(zone), context), zone, context);
    }

    private LocalDate today(List<SettingFold.Candidate> candidates, SettingDefinition zone, SettingContext context) {
        Object id = SettingFold.fold(List.of(zone), Map.of(zone.key(), registry.defaultOf(zone.key())),
                candidates, LocalDate.now(ZoneOffset.UTC)).get(zone.key()).value();
        try {
            return LocalDate.now(ZoneId.of(String.valueOf(id)));
        } catch (DateTimeException e) {
            return LocalDate.now(ZoneOffset.UTC);
        }
    }

    /**
     * Every version of the wanted keys held by a profile that applies to the
     * context, in one statement. {@code ctx} is the context unit's path;
     * {@code ancestor} its ancestors-or-self, read by (tenant, path) through
     * {@code org_unit_path_unique}; {@code jur} the jurisdiction: the
     * context's own, or when it names none, the unit's (the nearest country at
     * or above it, and the nearest state beneath that country).
     */
    public static final String RESOLVE = """
            WITH ctx AS (
                SELECT coalesce(CAST(:unitPath AS text),
                                (SELECT t.path FROM org_unit t
                                  WHERE t.tenant_id = :tenantId AND t.id = CAST(:unitId AS uuid))) AS path),
            ancestor AS (
                SELECT a.path, a.country_code, a.state_code
                  FROM ctx
                 CROSS JOIN LATERAL generate_series(2, length(ctx.path)) AS i
                  JOIN org_unit a ON a.tenant_id = :tenantId AND a.path = left(ctx.path, i)
                 WHERE substr(ctx.path, i, 1) = '/'),
            jur AS (
                SELECT CASE WHEN CAST(:country AS text) IS NOT NULL OR CAST(:state AS text) IS NOT NULL
                            THEN CAST(:country AS text)
                            ELSE (SELECT c.country_code FROM ancestor c WHERE c.country_code IS NOT NULL
                                   ORDER BY length(c.path) DESC LIMIT 1)
                       END AS country,
                       CASE WHEN CAST(:country AS text) IS NOT NULL OR CAST(:state AS text) IS NOT NULL
                            THEN CAST(:state AS text)
                            ELSE (SELECT s.state_code FROM ancestor s
                                   WHERE s.state_code IS NOT NULL
                                     AND length(s.path) > (SELECT max(length(c.path)) FROM ancestor c
                                                            WHERE c.country_code IS NOT NULL)
                                   ORDER BY length(s.path) DESC LIMIT 1)
                       END AS state)
            SELECT p.id, p.scope_type, p.scope_id, p.name, p.country, p.state, p.priority,
                   v.setting_key, v.effective_from, v.value::text AS value,
                   CASE p.scope_type
                       WHEN 'ORG_UNIT' THEN coalesce(length(u.path), 0)
                       WHEN 'COMBINATION' THEN coalesce(length(cu.path) - length(replace(cu.path, '/', '')) - 1, 0)
                       WHEN 'PLATFORM' THEN (CASE WHEN p.country IS NULL THEN 0 ELSE 1 END)
                                            + (CASE WHEN p.state IS NULL THEN 0 ELSE 1 END)
                       ELSE 0
                   END AS depth,
                   (CASE WHEN p.match_org_unit_id IS NULL THEN 0 ELSE 1 END
                       + CASE WHEN p.match_customer_id IS NULL THEN 0 ELSE 1 END
                       + CASE WHEN p.match_vendor_id IS NULL THEN 0 ELSE 1 END
                       + CASE WHEN p.match_vehicle_id IS NULL THEN 0 ELSE 1 END
                       + CASE WHEN p.match_freight_mode IS NULL THEN 0 ELSE 1 END) AS dimensions,
                   coalesce(u.unit_type, cu.unit_type) AS unit_type, coalesce(u.name, cu.name) AS unit_name,
                   CASE WHEN p.match_customer_id IS NOT NULL THEN
                       (SELECT b.legal_name FROM business_partner b WHERE b.id = p.match_customer_id) END AS customer,
                   CASE WHEN p.match_vendor_id IS NOT NULL THEN
                       (SELECT b.legal_name FROM business_partner b WHERE b.id = p.match_vendor_id) END AS vendor,
                   CASE WHEN p.match_vehicle_id IS NOT NULL THEN
                       (SELECT x.registration_no FROM vehicle x WHERE x.id = p.match_vehicle_id) END AS vehicle,
                   p.match_freight_mode
              FROM setting_profile p
              JOIN setting_value v ON v.profile_id = p.id
             CROSS JOIN ctx
             CROSS JOIN jur
              LEFT JOIN org_unit u ON p.scope_type = 'ORG_UNIT' AND u.id = p.scope_id
              LEFT JOIN org_unit cu ON p.scope_type = 'COMBINATION' AND cu.id = p.match_org_unit_id
             WHERE v.setting_key = ANY (string_to_array(:keys, ','))
               AND ((p.tenant_id = :tenantId
                     AND (p.scope_type = 'ORG_DEFAULT'
                          OR (p.scope_type = 'ORG_UNIT' AND starts_with(ctx.path, u.path))
                          OR (p.scope_type = 'CUSTOMER' AND p.scope_id = CAST(:customerId AS uuid))
                          OR (p.scope_type = 'VEHICLE' AND p.scope_id = CAST(:vehicleId AS uuid))
                          OR (p.scope_type = 'VENDOR' AND p.scope_id = CAST(:vendorId AS uuid))
                          OR (p.scope_type = 'CONTRACT' AND p.scope_id = CAST(:contractId AS uuid))
                          OR (p.scope_type = 'USER' AND p.scope_id = CAST(:userId AS uuid))
                          OR (p.scope_type = 'COMBINATION'
                              AND (p.match_org_unit_id IS NULL OR starts_with(ctx.path, cu.path))
                              AND (p.match_customer_id IS NULL OR p.match_customer_id = CAST(:customerId AS uuid))
                              AND (p.match_vendor_id IS NULL OR p.match_vendor_id = CAST(:vendorId AS uuid))
                              AND (p.match_vehicle_id IS NULL OR p.match_vehicle_id = CAST(:vehicleId AS uuid))
                              AND (p.match_freight_mode IS NULL
                                   OR p.match_freight_mode = CAST(:freightMode AS text)))))
                    OR (p.tenant_id IS NULL AND p.scope_type = 'PLATFORM'
                        AND (p.country IS NULL
                             OR (p.country = jur.country AND (p.state IS NULL OR p.state = jur.state)))))
            """;

    private List<SettingFold.Candidate> candidates(Collection<SettingDefinition> keys, SettingContext context) {
        String keyList = keys.stream().map(SettingDefinition::key).collect(Collectors.joining(","));
        List<SettingFold.Candidate> out = new ArrayList<>();
        jdbc.sql(RESOLVE)
                .param("tenantId", TenantContext.requireTenantId())
                .param("keys", keyList)
                .param("unitPath", context.orgUnitId() == null ? context.orgPath() : null)
                .param("unitId", context.orgUnitId())
                .param("customerId", context.customerId())
                .param("vehicleId", context.vehicleId())
                .param("vendorId", context.vendorId())
                .param("contractId", context.contractId())
                .param("userId", context.userId())
                .param("country", context.country())
                .param("state", context.state())
                .param("freightMode", context.freightMode())
                .query((rs, row) -> {
                    String key = rs.getString("setting_key");
                    Object value = applicable(key, rs.getString("value"));
                    if (value == null) {
                        return null;
                    }
                    ScopeType type = ScopeType.valueOf(rs.getString("scope_type"));
                    String name = rs.getString("name");
                    String combined = type == ScopeType.COMBINATION
                            ? ScopeLabels.combination(new ScopeLabels.Parts(rs.getString("unit_type"),
                                    rs.getString("unit_name"), rs.getString("customer"), rs.getString("vendor"),
                                    rs.getString("vehicle"), rs.getString("match_freight_mode")))
                            : null;
                    String label = ScopeLabels.source(type, name, rs.getString("unit_type"),
                            rs.getString("unit_name"), rs.getString("country"), rs.getString("state"), combined);
                    return new SettingFold.Candidate(rs.getObject("id", UUID.class), type,
                            rs.getObject("scope_id", UUID.class), name, rs.getInt("depth"), key,
                            rs.getObject("effective_from", LocalDate.class), value, rs.getInt("dimensions"),
                            rs.getInt("priority"), label);
                })
                .list()
                .forEach(c -> {
                    if (c != null) {
                        out.add(c);
                    }
                });
        return out;
    }

    /**
     * The stored value, checked again against today's definition. One that no
     * longer passes (the bounds were tightened since it was saved) is skipped,
     * so the next profile out -- or the system default -- answers instead of a
     * value the platform would now refuse.
     */
    private Object applicable(String key, String stored) {
        SettingDefinition d = registry.find(key).orElse(null);
        if (d == null) {
            return null;
        }
        try {
            return SettingValueRules.coerce(d, json.readValue(stored, ANY));
        } catch (SettingValueRules.InvalidValue e) {
            log.warn("Ignoring a stored value of {} that its definition now refuses: {}", key, e.getMessage());
            return null;
        }
    }
}
