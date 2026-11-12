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

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.lms.execution.command.domain.GatePass;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;

public interface GatePassRepository extends CrudRepository<GatePass, UUID> {

    @Query("SELECT * FROM gate_pass WHERE tenant_id = :tenantId AND pass_no = :passNo")
    Optional<GatePass> findByNumber(@Param("tenantId") UUID tenantId, @Param("passNo") String passNo);

    /**
     * A vehicle's passes at a unit, the latest window first; served by
     * {@code gate_pass_vehicle_idx}. Bounded: a vehicle does not hold a
     * hundred passes at one unit that still matter.
     */
    @Query("""
            SELECT * FROM gate_pass
             WHERE tenant_id = :tenantId AND registration_no = :registrationNo AND org_unit_id = :orgUnitId
             ORDER BY valid_until DESC
             LIMIT 50
            """)
    List<GatePass> findForVehicle(@Param("tenantId") UUID tenantId,
                                  @Param("registrationNo") String registrationNo,
                                  @Param("orgUnitId") UUID orgUnitId);
}
