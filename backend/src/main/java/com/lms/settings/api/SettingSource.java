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
package com.lms.settings.api;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Where an effective value came from.
 *
 * @param scopeType     null when no profile set the key and the system default answered
 * @param scopeId       the scope's record; null for the organisation default and the system default
 * @param profileId     the profile that supplied it; null for the system default
 * @param effectiveFrom the version's first day; null for an undated value
 * @param label         the scope in words, with its level: "Country: India", "Branch: Gurgaon",
 *                      "Customer: Terms", or for a combination its conditions, "Branch Gurgaon +
 *                      Customer Acme"; "System default" for the system default
 */
public record SettingSource(ScopeType scopeType, UUID scopeId, UUID profileId, String profileName,
                            LocalDate effectiveFrom, String label) {

    public static final SettingSource SYSTEM_DEFAULT = new SettingSource(null, null, null, null, null,
            "System default");

    /** With the label of a profile kept per scope record: its scope and its name. */
    public SettingSource(ScopeType scopeType, UUID scopeId, UUID profileId, String profileName,
                         LocalDate effectiveFrom) {
        this(scopeType, scopeId, profileId, profileName, effectiveFrom, null);
    }

    public boolean isSystemDefault() {
        return profileId == null;
    }
}
