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
package com.lms.identity.abac;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import tools.jackson.databind.json.JsonMapper;

/**
 * Loads the enabled policies that refine one action in one tenant.
 *
 * <p>Index-served on {@code (tenant_id, action)} -- {@code QueryPlanTest} holds
 * that -- and cached for the life of the HTTP request, so a request that
 * checks the same action twice pays for one query. Not cached for longer: a
 * policy an administrator just disabled must stop applying on the next
 * request, and on one small instance there is nothing to invalidate a
 * longer-lived cache across.
 *
 * <p>Must be called inside a transaction, which the decision point opens:
 * row-level security reads the tenant from the transaction.
 */
@Component
class PolicyStore {

    private static final Logger log = LoggerFactory.getLogger(PolicyStore.class);
    private static final String CACHE_PREFIX = PolicyStore.class.getName() + ".";

    private final JdbcClient jdbc;
    private final JsonMapper json;

    PolicyStore(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /** A loaded policy, its condition already parsed. */
    record Rule(String code, String effect, Condition condition) {

        boolean deny() {
            return "DENY".equals(effect);
        }
    }

    @SuppressWarnings("unchecked")
    List<Rule> forAction(UUID tenantId, String action) {
        RequestAttributes request = RequestContextHolder.getRequestAttributes();
        String key = CACHE_PREFIX + tenantId + "." + action;
        if (request != null) {
            Object cached = request.getAttribute(key, RequestAttributes.SCOPE_REQUEST);
            if (cached != null) {
                return (List<Rule>) cached;
            }
        }

        List<Rule> rules = jdbc.sql("""
                        SELECT code, effect, condition::text AS condition
                          FROM access_policy
                         WHERE tenant_id = :tenantId
                           AND action = :action
                           AND enabled
                         ORDER BY code
                        """)
                .param("tenantId", tenantId)
                .param("action", action)
                .query((rs, row) -> toRule(rs.getString("code"), rs.getString("effect"),
                        rs.getString("condition")))
                .list();

        if (request != null) {
            request.setAttribute(key, rules, RequestAttributes.SCOPE_REQUEST);
        }
        return rules;
    }

    private Rule toRule(String code, String effect, String conditionJson) {
        Condition condition;
        try {
            condition = Condition.parse(json.readValue(conditionJson, Map.class));
        } catch (RuntimeException e) {
            // Every write is validated, so this means somebody edited the row
            // by hand. Fail closed: a DENY that cannot be read still denies,
            // and a PERMIT that cannot be read permits nothing.
            log.error("Access policy '{}' has an unreadable condition and is treated as "
                    + "{}: {}", code, "DENY".equals(effect) ? "always matching" : "never matching",
                    e.getMessage());
            condition = new Condition.Fixed("DENY".equals(effect));
        }
        return new Rule(code, effect, condition);
    }
}
