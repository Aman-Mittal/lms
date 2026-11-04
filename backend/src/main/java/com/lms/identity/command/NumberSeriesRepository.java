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

import com.lms.identity.command.domain.NumberSeries;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;

public interface NumberSeriesRepository extends CrudRepository<NumberSeries, UUID> {

    @Query("SELECT * FROM number_series WHERE tenant_id = :tenantId AND code = :code")
    Optional<NumberSeries> findByCode(@Param("tenantId") UUID tenantId, @Param("code") String code);

    /**
     * Every series of a type, active or not, longest prefix first -- the
     * order a number is tried against them, so that {@code LR2} is matched
     * before {@code LR}. Served by {@code number_series_prefix_unique}.
     */
    @Query("""
            SELECT * FROM number_series
             WHERE tenant_id = :tenantId AND document_type = :type
             ORDER BY length(prefix) DESC, prefix
            """)
    List<NumberSeries> findByType(@Param("tenantId") UUID tenantId, @Param("type") String type);

    /**
     * The active series of a type that numbers documents at {@code orgUnitId}:
     * the one on the deepest unit at or above it, else the tenant-wide one.
     *
     * <p>{@code starts_with} rather than {@code LIKE}, so that nothing in a
     * path can act as a wildcard. With a null unit only the tenant-wide series
     * can match.
     */
    @Query("""
            SELECT s.* FROM number_series s
              LEFT JOIN org_unit u ON u.id = s.org_unit_id
             WHERE s.tenant_id = :tenantId
               AND s.document_type = :type
               AND s.active
               AND (s.org_unit_id IS NULL
                    OR starts_with((SELECT t.path FROM org_unit t WHERE t.id = CAST(:unitId AS uuid)),
                                   u.path))
             ORDER BY length(u.path) DESC NULLS LAST
             LIMIT 1
            """)
    Optional<NumberSeries> findEffective(@Param("tenantId") UUID tenantId, @Param("type") String type,
                                         @Param("unitId") UUID unitId);

    /** Whether another active series already holds the type and scope. */
    @Query("""
            SELECT EXISTS (SELECT 1 FROM number_series
                            WHERE tenant_id = :tenantId AND document_type = :type AND active
                              AND id <> :id
                              AND org_unit_id IS NOT DISTINCT FROM CAST(:unitId AS uuid))
            """)
    boolean scopeTaken(@Param("tenantId") UUID tenantId, @Param("type") String type,
                       @Param("unitId") UUID unitId, @Param("id") UUID id);

    /** Whether any number has ever been issued from the series. */
    @Query("""
            SELECT EXISTS (SELECT 1 FROM number_series_counter
                            WHERE tenant_id = :tenantId AND series_id = :id)
            """)
    boolean hasIssued(@Param("tenantId") UUID tenantId, @Param("id") UUID id);

    /** The unit's path when it exists in the tenant, read under row-level security. */
    @Query("SELECT path FROM org_unit WHERE id = :id")
    Optional<String> orgUnitPath(@Param("id") UUID id);
}
