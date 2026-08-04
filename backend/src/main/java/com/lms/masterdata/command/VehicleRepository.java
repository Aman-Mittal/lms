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

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.lms.masterdata.command.domain.Vehicle;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;

public interface VehicleRepository extends CrudRepository<Vehicle, UUID> {

    @Query("SELECT * FROM vehicle WHERE tenant_id = :tenantId AND registration_no = :registrationNo")
    Optional<Vehicle> findByRegistration(@Param("tenantId") UUID tenantId,
                                         @Param("registrationNo") String registrationNo);

    /**
     * Available vehicles with at least the given capacity, ordered so the
     * smallest adequate vehicle comes first.
     *
     * <p>Smallest-first is deliberate: allocating a 40-tonne trailer to a
     * two-tonne load wastes capacity that another load needs, and load building
     * has no way to reclaim it afterwards.
     */
    @Query("""
            SELECT * FROM vehicle
             WHERE tenant_id = :tenantId
               AND status = 'AVAILABLE'
               AND (gross_weight_kg - tare_weight_kg) >= :requiredKg
               AND max_volume_m3 >= :requiredM3
               AND (NOT :hazmatRequired OR hazmat_certified)
             ORDER BY (gross_weight_kg - tare_weight_kg), id
             LIMIT :limit
            """)
    List<Vehicle> findAvailableWithCapacity(@Param("tenantId") UUID tenantId,
                                            @Param("requiredKg") java.math.BigDecimal requiredKg,
                                            @Param("requiredM3") java.math.BigDecimal requiredM3,
                                            @Param("hazmatRequired") boolean hazmatRequired,
                                            @Param("limit") int limit);
}
