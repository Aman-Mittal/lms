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
package com.lms.identity.command;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.lms.identity.command.domain.OrgUnit;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;

public interface OrgUnitRepository extends CrudRepository<OrgUnit, UUID> {

    /**
     * Every unit at or below {@code orgPath}: the top-down visibility rule of
     * vision document 2.1, as an index-assisted prefix match rather than a
     * recursive CTE.
     */
    @Query("""
            SELECT * FROM org_unit
             WHERE tenant_id = :tenantId
               AND path LIKE :orgPath || '%'
             ORDER BY path
            """)
    List<OrgUnit> findSubtree(@Param("tenantId") UUID tenantId, @Param("orgPath") String orgPath);

    @Query("SELECT * FROM org_unit WHERE tenant_id = :tenantId AND path = :path")
    Optional<OrgUnit> findByPath(@Param("tenantId") UUID tenantId, @Param("path") String path);

    /** The tenant's root unit, used when seeding and when a caller has no narrower scope. */
    @Query("SELECT * FROM org_unit WHERE tenant_id = :tenantId AND parent_id IS NULL")
    Optional<OrgUnit> findRoot(@Param("tenantId") UUID tenantId);
}
