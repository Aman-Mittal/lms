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
package com.lms.milkrun.command;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.lms.milkrun.command.domain.MilkRunRoute;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;

public interface MilkRunRouteRepository extends CrudRepository<MilkRunRoute, UUID> {

    /** The version in force, of which the partial unique index permits one per code. */
    @Query("""
            SELECT * FROM milk_run_route
             WHERE tenant_id = :tenantId AND code = :code AND effective_to IS NULL
            """)
    Optional<MilkRunRoute> findOpenVersion(@Param("tenantId") UUID tenantId,
                                           @Param("code") String code);

    /**
     * Every version in force on any day of a window, newest first per route.
     *
     * <p>The generator's whole input. Bounded by the number of routes a tenant
     * runs -- tens, not thousands -- and served by {@code milk_run_route_lookup_idx}.
     */
    @Query("""
            SELECT * FROM milk_run_route
             WHERE tenant_id = :tenantId
               AND effective_from <= :lastDay
               AND (effective_to IS NULL OR effective_to >= :firstDay)
             ORDER BY code, effective_from DESC
            """)
    List<MilkRunRoute> findCovering(@Param("tenantId") UUID tenantId,
                                    @Param("firstDay") LocalDate firstDay,
                                    @Param("lastDay") LocalDate lastDay);
}
