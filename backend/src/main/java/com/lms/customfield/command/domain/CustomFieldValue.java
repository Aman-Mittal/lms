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

package com.lms.customfield.command.domain;

import java.time.Instant;
import java.util.UUID;

import com.lms.shared.config.Json;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.relational.core.mapping.Table;

/** The custom values of one record, as one JSON object. */
@Table("custom_field_value")
public record CustomFieldValue(
        @Id UUID id,
        UUID tenantId,
        EntityType entityType,
        UUID entityId,
        Json fieldValues,
        @Version Long version,
        Instant createdAt,
        Instant updatedAt) {

    public static CustomFieldValue of(UUID tenantId, EntityType entityType, UUID entityId,
                                      Json fieldValues) {
        Instant now = Instant.now();
        return new CustomFieldValue(UUID.randomUUID(), tenantId, entityType, entityId, fieldValues,
                null, now, now);
    }

    public CustomFieldValue withValues(Json fieldValues) {
        return new CustomFieldValue(id, tenantId, entityType, entityId, fieldValues, version,
                createdAt, Instant.now());
    }
}
