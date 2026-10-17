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
package com.lms.identity.command.domain;

import java.time.Instant;
import java.util.UUID;
import java.util.regex.Pattern;

import com.lms.shared.config.Json;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.relational.core.mapping.Table;

/**
 * One attribute-based rule refining one permission, in one tenant.
 *
 * <p>The code and the action are fixed once created. A policy that changed
 * which action it governed would be a different policy wearing the old one's
 * audit history; delete it and create the new one instead.
 *
 * <p>The condition is stored as JSON and validated by the caller before it
 * gets here -- the aggregate holds it, the decision point interprets it.
 */
@Table("access_policy")
public record AccessPolicy(
        @Id UUID id,
        UUID tenantId,
        String code,
        String action,
        Effect effect,
        String description,
        Json condition,
        boolean enabled,
        @Version Long version,
        Instant createdAt,
        Instant updatedAt) {

    public enum Effect {
        PERMIT, DENY
    }

    /** Mirrors {@code access_policy_code_safe}. */
    private static final Pattern SAFE_CODE = Pattern.compile("^[a-z0-9][a-z0-9-]{0,62}$");
    /** Mirrors {@code access_policy_action_safe}: the shape of a permission code. */
    private static final Pattern SAFE_ACTION = Pattern.compile("^[A-Z][A-Z0-9_]{0,62}$");

    public AccessPolicy {
        if (code == null || !SAFE_CODE.matcher(code).matches()) {
            throw new IllegalArgumentException(
                    "Policy code must be lowercase alphanumeric with hyphens: " + code);
        }
        if (action == null || !SAFE_ACTION.matcher(action).matches()) {
            throw new IllegalArgumentException("Policy action must be a permission code: " + action);
        }
        if (effect == null) {
            throw new IllegalArgumentException("Policy effect is required");
        }
        if (description == null || description.isBlank()) {
            throw new IllegalArgumentException("A policy has to say what it is for");
        }
        if (condition == null) {
            throw new IllegalArgumentException("Policy condition is required");
        }
    }

    public static AccessPolicy create(UUID id, UUID tenantId, String code, String action,
                                      Effect effect, String description, Json condition,
                                      boolean enabled) {
        Instant now = Instant.now();
        return new AccessPolicy(id, tenantId, code, action, effect, description, condition,
                enabled, null, now, now);
    }

    public AccessPolicy revise(Effect newEffect, String newDescription, Json newCondition,
                               boolean nowEnabled) {
        return new AccessPolicy(id, tenantId, code, action, newEffect, newDescription,
                newCondition, nowEnabled, version, createdAt, Instant.now());
    }
}
