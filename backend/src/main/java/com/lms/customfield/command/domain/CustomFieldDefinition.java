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

import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.relational.core.mapping.Table;

/**
 * One field a tenant defined for one kind of record.
 *
 * <p>The key and the type never change after creation: a stored value was
 * validated against them, and changing either would leave values that no
 * longer mean what they were checked to mean.
 */
@Table("custom_field_definition")
public record CustomFieldDefinition(
        @Id UUID id,
        UUID tenantId,
        EntityType entityType,
        String fieldKey,
        String label,
        FieldType fieldType,
        boolean required,
        String[] options,
        int displayOrder,
        boolean active,
        @Version Long version,
        Instant createdAt) {

    public static CustomFieldDefinition of(UUID tenantId, EntityType entityType, String fieldKey,
                                           String label, FieldType fieldType, boolean required,
                                           String[] options, int displayOrder) {
        return new CustomFieldDefinition(UUID.randomUUID(), tenantId, entityType, fieldKey, label,
                fieldType, required, options, displayOrder, true, null, Instant.now());
    }

    public CustomFieldDefinition revised(String label, boolean required, String[] options,
                                         int displayOrder, boolean active) {
        return new CustomFieldDefinition(id, tenantId, entityType, fieldKey, label, fieldType,
                required, options, displayOrder, active, version, createdAt);
    }
}
