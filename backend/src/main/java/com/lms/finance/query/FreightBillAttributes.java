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
package com.lms.finance.query;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import com.lms.identity.access.ResourceAttributeProvider;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * What access policies may know about a freight bill.
 *
 * <p>{@code amount} is what acting on the bill would pay: the vendor's claim
 * once there is one, the computed cost before. Approving and settling a
 * dispute both pay the claim, so that is the figure a limit has to be held
 * against -- not the platform's own, often lower, number.
 */
@Component
class FreightBillAttributes implements ResourceAttributeProvider {

    private final JdbcClient jdbc;

    FreightBillAttributes(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public String resourceType() {
        return "FREIGHT_BILL";
    }

    @Override
    public Map<String, Object> attributesOf(UUID id) {
        return jdbc.sql("""
                        SELECT org_unit_id, vendor_partner_id, status, computed_amount,
                               claimed_amount
                          FROM freight_bill
                         WHERE id = :id
                        """)
                .param("id", id)
                .query((rs, row) -> {
                    BigDecimal computed = rs.getBigDecimal("computed_amount");
                    BigDecimal claimed = rs.getBigDecimal("claimed_amount");
                    Map<String, Object> attributes = new LinkedHashMap<>();
                    attributes.put("orgUnitId", rs.getObject("org_unit_id", UUID.class));
                    attributes.put("partnerId", rs.getObject("vendor_partner_id", UUID.class));
                    attributes.put("status", rs.getString("status"));
                    attributes.put("computedAmount", computed);
                    attributes.put("claimedAmount", claimed);
                    attributes.put("amount", claimed != null ? claimed : computed);
                    return attributes;
                })
                .optional()
                .orElseGet(Map::of);
    }
}
