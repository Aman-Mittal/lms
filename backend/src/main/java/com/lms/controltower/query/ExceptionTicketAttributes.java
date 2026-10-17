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
package com.lms.controltower.query;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import com.lms.identity.access.ResourceAttributeProvider;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * What access policies may know about an exception ticket.
 *
 * <p>{@code suspendsSettlement} is the one that matters today: closing a
 * ticket that holds a trip's settlement is what releases the vendor's money,
 * so it is where a monetary authority limit applies.
 */
@Component
class ExceptionTicketAttributes implements ResourceAttributeProvider {

    private final JdbcClient jdbc;

    ExceptionTicketAttributes(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public String resourceType() {
        return "EXCEPTION_TICKET";
    }

    @Override
    public Map<String, Object> attributesOf(UUID id) {
        return jdbc.sql("""
                        SELECT category, severity, status, suspends_settlement, trip_id
                          FROM exception_ticket
                         WHERE id = :id
                        """)
                .param("id", id)
                .query((rs, row) -> {
                    Map<String, Object> attributes = new LinkedHashMap<>();
                    attributes.put("category", rs.getString("category"));
                    attributes.put("severity", rs.getString("severity"));
                    attributes.put("status", rs.getString("status"));
                    attributes.put("suspendsSettlement", rs.getBoolean("suspends_settlement"));
                    attributes.put("tripId", rs.getObject("trip_id", UUID.class));
                    return attributes;
                })
                .optional()
                .orElseGet(Map::of);
    }
}
