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
package com.lms.approvals.command;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import com.lms.identity.access.ResourceAttributeProvider;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * An approval request as the access decision point sees it: its unit (so a
 * tenant can confine deciding to an organisational subtree with a policy on
 * {@code APPROVAL_DECIDE}), its subject type and who asked.
 */
@Component
public class ApprovalAttributes implements ResourceAttributeProvider {

    private final JdbcClient jdbc;

    public ApprovalAttributes(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public String resourceType() {
        return "APPROVAL";
    }

    @Override
    public Map<String, Object> attributesOf(UUID id) {
        return jdbc.sql("SELECT org_unit_id, subject_type, requested_by FROM approval_request WHERE id = :id")
                .param("id", id)
                .query((rs, row) -> {
                    Map<String, Object> map = new LinkedHashMap<>();
                    map.put("orgUnitId", rs.getObject("org_unit_id", UUID.class));
                    map.put("subjectType", rs.getString("subject_type"));
                    map.put("requestedBy", rs.getObject("requested_by", UUID.class));
                    return map;
                })
                .optional()
                .orElse(Map.of());
    }
}
