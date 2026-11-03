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
package com.lms.warehouse.query;

import java.util.Map;
import java.util.UUID;

import com.lms.identity.access.ResourceAttributeProvider;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * What access policies may know about a warehouse bin.
 *
 * <p>Only where it lives: the organisational unit of the warehouse it stands
 * in, so a stock adjustment named by its bin -- a correction, or a release
 * from quarantine into it -- is held to the same subtree rule as anything
 * else filed at that warehouse.
 */
@Component
class WarehouseBinAttributes implements ResourceAttributeProvider {

    private final JdbcClient jdbc;

    WarehouseBinAttributes(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public String resourceType() {
        return "WAREHOUSE_BIN";
    }

    @Override
    public Map<String, Object> attributesOf(UUID id) {
        return jdbc.sql("""
                        SELECT t.org_unit_id
                          FROM warehouse_bin w
                          JOIN terminal t ON t.id = w.warehouse_id
                         WHERE w.id = :id
                        """)
                .param("id", id)
                .query(UUID.class)
                .optional()
                .<Map<String, Object>>map(unit -> Map.of("orgUnitId", unit))
                .orElseGet(Map::of);
    }
}
