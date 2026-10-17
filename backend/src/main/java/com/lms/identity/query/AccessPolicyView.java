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
package com.lms.identity.query;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * An access policy as an administrator reads it.
 *
 * @param condition the condition as the JSON object it was written as, not a
 *                  string holding JSON -- a client should not have to parse
 *                  twice to read a rule
 */
public record AccessPolicyView(
        UUID id,
        String code,
        String action,
        String effect,
        String description,
        Map<String, Object> condition,
        boolean enabled,
        Instant createdAt,
        Instant updatedAt) {
}
