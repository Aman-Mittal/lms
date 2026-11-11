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
package com.lms.settings.command;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

import com.lms.shared.error.BusinessRuleViolationException;
import com.lms.shared.tenant.TenantContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The write-side rule that keeps the fold decidable: two combination profiles
 * that could both match one context, with as many conditions, an equally deep
 * unit and the same priority, must not set the same key. The fold would
 * otherwise have nothing but an arbitrary tie-breaker to choose between them.
 */
@Component
public class CombinationRules {

    /** Another combination of the tenant's, as the rule needs it. */
    record Other(UUID id, String name, SettingCombination combination, Set<String> keys) {
    }

    private final JdbcClient jdbc;

    public CombinationRules(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Refuses {@code mine}, about to hold {@code keys}, when it ties with
     * another combination of the tenant's on one of them. Takes a per-tenant
     * transaction-scoped advisory lock first, so two writes cannot each pass
     * the check against the other's state before it committed.
     *
     * @throws BusinessRuleViolationException {@code setting-combination-ambiguous},
     *         naming each shared key and the profile it ties with
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void refuseAmbiguous(UUID self, SettingCombination mine, Set<String> keys) {
        if (keys.isEmpty()) {
            return;
        }
        UUID tenantId = TenantContext.requireTenantId();
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended('setting-combination:' || CAST(:t AS text), 0))")
                .param("t", tenantId).query().singleRow();
        Map<String, String> clashes = clashes(mine, keys, others(tenantId, self));
        if (!clashes.isEmpty()) {
            throw new BusinessRuleViolationException("setting-combination-ambiguous",
                    "Another combination could match the same context with as many conditions, an equally deep"
                            + " unit and the same priority, and sets the same keys: "
                            + String.join(", ", clashes.keySet())
                            + ". Give one a different priority, or narrow its conditions",
                    clashes);
        }
    }

    /** Key to the profile it ties with, for each key {@code mine} shares with a combination it ties with. */
    static Map<String, String> clashes(SettingCombination mine, Set<String> keys, List<Other> others) {
        Map<String, String> out = new TreeMap<>();
        for (Other other : others) {
            if (!mine.tiesWith(other.combination())) {
                continue;
            }
            for (String key : other.keys()) {
                if (keys.contains(key)) {
                    out.putIfAbsent(key, "also set by combination \"" + other.name() + "\" with the same"
                            + " precedence");
                }
            }
        }
        return out;
    }

    /** Served by {@code setting_profile_list_idx} (tenant, scope type). */
    private List<Other> others(UUID tenantId, UUID self) {
        return jdbc.sql("""
                        SELECT p.id, p.name, p.match_org_unit_id, u.path, p.match_customer_id, p.match_vendor_id,
                               p.match_vehicle_id, p.match_freight_mode, p.priority,
                               (SELECT string_agg(DISTINCT v.setting_key, ',') FROM setting_value v
                                 WHERE v.profile_id = p.id) AS keys
                          FROM setting_profile p
                          LEFT JOIN org_unit u ON u.id = p.match_org_unit_id
                         WHERE p.tenant_id = :tenantId AND p.scope_type = 'COMBINATION' AND p.id <> :self
                        """)
                .param("tenantId", tenantId)
                .param("self", self)
                .query((rs, row) -> {
                    String keys = rs.getString("keys");
                    return new Other(rs.getObject("id", UUID.class), rs.getString("name"),
                            new SettingCombination(rs.getObject("match_org_unit_id", UUID.class), rs.getString("path"),
                                    rs.getObject("match_customer_id", UUID.class),
                                    rs.getObject("match_vendor_id", UUID.class),
                                    rs.getObject("match_vehicle_id", UUID.class), rs.getString("match_freight_mode"),
                                    rs.getInt("priority")),
                            keys == null ? Set.of() : Set.copyOf(Arrays.asList(keys.split(","))));
                })
                .list();
    }
}
