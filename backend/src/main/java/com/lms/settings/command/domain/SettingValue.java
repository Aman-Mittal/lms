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
package com.lms.settings.command.domain;

import java.time.LocalDate;
import java.util.UUID;

import com.lms.shared.config.Json;
import org.springframework.data.relational.core.mapping.Table;

/**
 * One version of a key's value in a profile, as JSON.
 *
 * @param tenantId      null on a platform profile
 * @param effectiveFrom the first day it applies; null for an undated value,
 *                      which applies since always
 */
@Table("setting_value")
public record SettingValue(UUID tenantId, String settingKey, LocalDate effectiveFrom, Json value) {
}
