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

import java.util.Map;
import java.util.UUID;

import com.lms.identity.access.ResourceAttributeProvider;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * What access policies may know about a terminal.
 *
 * <p>Only where the record lives: its organisational unit, which the decision
 * point turns into a path for the subtree rule. A record created beneath it
 * -- a warehouse bin, an ASN, a milk-run route from its plant, an itinerary from its first railhead -- is guarded by naming this one.
 */
@Component
class TerminalAttributes implements ResourceAttributeProvider {

    private final JdbcClient jdbc;

    TerminalAttributes(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public String resourceType() {
        return "TERMINAL";
    }

    @Override
    public Map<String, Object> attributesOf(UUID id) {
        return jdbc.sql("SELECT org_unit_id FROM terminal WHERE id = :id")
                .param("id", id)
                .query(UUID.class)
                .optional()
                .<Map<String, Object>>map(unit -> Map.of("orgUnitId", unit))
                .orElseGet(Map::of);
    }
}
