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
package com.lms.masterdata.command;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.lms.masterdata.command.domain.Terminal;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;

public interface TerminalRepository extends CrudRepository<Terminal, UUID> {

    @Query("SELECT * FROM terminal WHERE tenant_id = :tenantId AND code = :code")
    Optional<Terminal> findByCode(@Param("tenantId") UUID tenantId, @Param("code") String code);

    /**
     * Terminals of the same functional category whose bounding box intersects
     * the given one.
     *
     * <p>The first half of the two-phase spatial strategy from DOCS/adr/0004:
     * an indexed range query narrows the field, then exact geometry runs in
     * Java over the survivors. It is served by {@code terminal_bbox_idx}, and
     * {@code QueryPlanTest} fails the build if that ever stops being true.
     *
     * <p>Restricted to one category on purpose. A warehouse inside a port is a
     * legitimate arrangement; two overlapping warehouses are not.
     */
    @Query("""
            SELECT * FROM terminal
             WHERE tenant_id = :tenantId
               AND functional_category = :category
               AND min_lat <= :maxLat AND max_lat >= :minLat
               AND min_lon <= :maxLon AND max_lon >= :minLon
               -- The cast is required, not cosmetic. A bind parameter used only
               -- in `IS NULL` gives Postgres nothing to infer a type from, and
               -- it fails with "could not determine data type of parameter".
               AND (CAST(:excludeId AS uuid) IS NULL OR id <> CAST(:excludeId AS uuid))
            """)
    List<Terminal> findOverlapCandidates(@Param("tenantId") UUID tenantId,
                                         @Param("category") String category,
                                         @Param("minLat") BigDecimal minLat,
                                         @Param("maxLat") BigDecimal maxLat,
                                         @Param("minLon") BigDecimal minLon,
                                         @Param("maxLon") BigDecimal maxLon,
                                         @Param("excludeId") UUID excludeId);

    /**
     * Terminals whose bounding box could contain a coordinate, across all
     * categories.
     *
     * <p>Runs on every GPS ping, so it is the hottest query in the platform.
     */
    @Query("""
            SELECT * FROM terminal
             WHERE tenant_id = :tenantId
               AND min_lat <= :lat AND max_lat >= :lat
               AND min_lon <= :lon AND max_lon >= :lon
            """)
    List<Terminal> findCandidatesContaining(@Param("tenantId") UUID tenantId,
                                            @Param("lat") BigDecimal lat,
                                            @Param("lon") BigDecimal lon);
}
