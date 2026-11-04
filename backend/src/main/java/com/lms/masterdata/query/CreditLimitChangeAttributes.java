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
package com.lms.masterdata.query;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import com.lms.identity.access.ResourceAttributeProvider;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * A credit-limit change request as policies see it: the limit being approved
 * as {@code amount}, and the partner's unit for subtree rules.
 */
@Component
class CreditLimitChangeAttributes implements ResourceAttributeProvider {

    private final JdbcClient jdbc;

    CreditLimitChangeAttributes(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    private record Row(BigDecimal requestedLimit, UUID orgUnitId) {
    }

    @Override
    public String resourceType() {
        return "CREDIT_LIMIT_CHANGE";
    }

    @Override
    public Map<String, Object> attributesOf(UUID id) {
        return jdbc.sql("""
                        SELECT c.requested_limit, p.org_unit_id
                          FROM credit_limit_change c
                          JOIN business_partner p ON p.id = c.partner_id
                         WHERE c.id = :id
                        """)
                .param("id", id)
                .query(Row.class)
                .optional()
                .<Map<String, Object>>map(row -> {
                    Map<String, Object> attributes = new LinkedHashMap<>();
                    attributes.put("amount", row.requestedLimit());
                    attributes.put("orgUnitId", row.orgUnitId());
                    return attributes;
                })
                .orElseGet(Map::of);
    }
}
