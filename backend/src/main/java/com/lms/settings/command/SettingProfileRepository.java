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
package com.lms.settings.command;

import java.util.Optional;
import java.util.UUID;

import com.lms.settings.command.domain.SettingProfile;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;

public interface SettingProfileRepository extends CrudRepository<SettingProfile, UUID> {

    /**
     * The profile of one scope, by id only: the aggregate is then loaded with
     * its values through {@code findById}. Served by
     * {@code setting_profile_scope_idx}.
     */
    @Query("""
            SELECT id FROM setting_profile
             WHERE tenant_id = :tenantId AND scope_type = :scopeType
               AND coalesce(scope_id, '00000000-0000-0000-0000-000000000000'::uuid)
                   = coalesce(CAST(:scopeId AS uuid), '00000000-0000-0000-0000-000000000000'::uuid)
            """)
    Optional<UUID> idOfScope(@Param("tenantId") UUID tenantId, @Param("scopeType") String scopeType,
                             @Param("scopeId") UUID scopeId);

    /** The platform profile of one jurisdiction. Served by {@code setting_profile_platform_idx}. */
    @Query("""
            SELECT id FROM setting_profile
             WHERE tenant_id IS NULL AND scope_type = 'PLATFORM'
               AND coalesce(country, '') = coalesce(CAST(:country AS text), '')
               AND coalesce(state, '') = coalesce(CAST(:state AS text), '')
            """)
    Optional<UUID> idOfPlatform(@Param("country") String country, @Param("state") String state);
}
