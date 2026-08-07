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
package com.lms.telematics.command;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.lms.telematics.command.domain.RouteDeviation;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;

public interface RouteDeviationRepository extends CrudRepository<RouteDeviation, UUID> {

    /**
     * The excursion currently in progress for a trip, if any.
     *
     * <p>Served by the partial index {@code route_deviation_open_idx}. The
     * existence of this row is what turns an alert per ping into an alert per
     * excursion.
     */
    @Query("""
            SELECT * FROM route_deviation
             WHERE tenant_id = :tenantId AND trip_id = :tripId AND resolved_at IS NULL
             ORDER BY detected_at DESC
             LIMIT 1
            """)
    Optional<RouteDeviation> findOpen(@Param("tenantId") UUID tenantId,
                                      @Param("tripId") UUID tripId);

    @Query("""
            SELECT * FROM route_deviation
             WHERE tenant_id = :tenantId AND trip_id = :tripId
             ORDER BY detected_at DESC
            """)
    List<RouteDeviation> findByTrip(@Param("tenantId") UUID tenantId, @Param("tripId") UUID tripId);
}
