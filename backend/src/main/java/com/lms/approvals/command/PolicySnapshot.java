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

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.lms.approvals.api.ApprovalPolicy;
import com.lms.shared.config.Json;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * A policy as it is copied onto a request, and read back from one.
 *
 * <p>Written from an explicit list of fields, as audit snapshots are, so a
 * request raised today can still be decided by a later release that has
 * renamed something in the record.
 */
@Component
public class PolicySnapshot {

    private final JsonMapper json;

    public PolicySnapshot(JsonMapper json) {
        this.json = json;
    }

    public Json write(ApprovalPolicy policy) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("subjectType", policy.subjectType());
        map.put("required", policy.required());
        map.put("mandatory", policy.mandatory());
        map.put("decidePermissions", policy.decidePermissions());
        map.put("ordered", policy.ordered());
        map.put("allowSelfApproval", policy.allowSelfApproval());
        map.put("dueMinutes", policy.due() == null ? null : policy.due().toMinutes());
        map.put("onExpiry", policy.onExpiry().name());
        return new Json(json.writeValueAsString(map));
    }

    public ApprovalPolicy read(Json snapshot) {
        Map<String, Object> map = json.readValue(snapshot.value(), new TypeReference<Map<String, Object>>() { });
        Object due = map.get("dueMinutes");
        @SuppressWarnings("unchecked")
        List<String> steps = (List<String>) map.get("decidePermissions");
        return new ApprovalPolicy(String.valueOf(map.get("subjectType")),
                Boolean.TRUE.equals(map.get("required")), Boolean.TRUE.equals(map.get("mandatory")),
                steps, Boolean.TRUE.equals(map.get("ordered")), Boolean.TRUE.equals(map.get("allowSelfApproval")),
                due == null ? null : Duration.ofMinutes(((Number) due).longValue()),
                ApprovalPolicy.Expiry.valueOf(String.valueOf(map.get("onExpiry"))));
    }

    /** A payload as JSON; values are strings, numbers, booleans or null. */
    public Json payload(Map<String, ?> payload) {
        return new Json(json.writeValueAsString(payload == null ? Map.of() : payload));
    }

    public Map<String, Object> payload(Json payload) {
        if (payload == null) {
            return Map.of();
        }
        return json.readValue(payload.value(), new TypeReference<LinkedHashMap<String, Object>>() { });
    }
}
