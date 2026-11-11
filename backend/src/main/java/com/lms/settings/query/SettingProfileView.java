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
package com.lms.settings.query;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.lms.settings.api.ScopeType;

/**
 * A profile as a screen shows it.
 *
 * @param scopeLabel what the scope is called: the unit's name and path, the
 *                   customer's code and name, the vehicle's registration;
 *                   null for the organisation default and a user
 * @param country    a platform profile's jurisdiction; null for every country
 * @param state      a platform profile's state within {@code country}
 * @param values     key to the value in effect today, from this profile alone
 * @param versions   every stored version, by key then day
 * @param version    the optimistic-lock version, also the {@code ETag}
 */
public record SettingProfileView(
        UUID id,
        ScopeType scopeType,
        UUID scopeId,
        String scopeLabel,
        String country,
        String state,
        String name,
        Map<String, Object> values,
        List<VersionView> versions,
        long version,
        Instant createdAt,
        Instant updatedAt) {

    /**
     * One version of one key.
     *
     * @param effectiveFrom null for an undated value
     * @param status        {@code IN_FORCE} (the one applying today),
     *                      {@code SUPERSEDED} (in force once, replaced by a
     *                      later one) or {@code SCHEDULED} (from a day still
     *                      to come, and the only kind that may still change)
     */
    public record VersionView(String key, LocalDate effectiveFrom, Object value, String status) {
    }
}
