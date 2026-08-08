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
package com.lms.execution.command;

import java.util.Optional;
import java.util.UUID;

import com.lms.execution.command.domain.Trip;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;

public interface TripRepository extends CrudRepository<Trip, UUID> {

    @Query("SELECT * FROM trip WHERE tenant_id = :tenantId AND trip_no = :tripNo")
    Optional<Trip> findByTripNo(@Param("tenantId") UUID tenantId, @Param("tripNo") String tripNo);

    @Query("SELECT * FROM trip WHERE tenant_id = :tenantId AND load_id = :loadId")
    Optional<Trip> findByLoad(@Param("tenantId") UUID tenantId, @Param("loadId") UUID loadId);

    /**
     * The trip a vehicle is currently running, if any.
     *
     * <p>Served by {@code trip_vehicle_idx}. Telematics will ask this of every
     * ping received, so it is on the way to being the hottest read in the
     * platform.
     */
    @Query("""
            SELECT * FROM trip
             WHERE tenant_id = :tenantId
               AND vehicle_id = :vehicleId
               AND status IN ('ASSIGNED', 'AT_ORIGIN', 'LOADED', 'DISPATCHED',
                              'IN_TRANSIT', 'AT_DESTINATION')
             LIMIT 1
            """)
    Optional<Trip> findActiveForVehicle(@Param("tenantId") UUID tenantId,
                                        @Param("vehicleId") UUID vehicleId);
}
