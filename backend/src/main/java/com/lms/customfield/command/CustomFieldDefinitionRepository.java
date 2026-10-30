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

package com.lms.customfield.command;

import java.util.List;
import java.util.UUID;

import com.lms.customfield.command.domain.CustomFieldDefinition;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;

public interface CustomFieldDefinitionRepository
        extends CrudRepository<CustomFieldDefinition, UUID> {

    @Query("SELECT * FROM custom_field_definition WHERE tenant_id = :tenantId"
            + " ORDER BY entity_type, display_order, field_key")
    List<CustomFieldDefinition> forTenant(@Param("tenantId") UUID tenantId);

    /** Served by the unique index {@code custom_field_definition_key_idx}. */
    @Query("SELECT * FROM custom_field_definition WHERE tenant_id = :tenantId"
            + " AND entity_type = :entityType ORDER BY display_order, field_key")
    List<CustomFieldDefinition> ofType(@Param("tenantId") UUID tenantId,
                                       @Param("entityType") String entityType);
}
