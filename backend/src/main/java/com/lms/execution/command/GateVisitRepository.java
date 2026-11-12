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

import com.lms.execution.command.domain.GateVisit;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;

public interface GateVisitRepository extends CrudRepository<GateVisit, UUID> {

    /** The visit a vehicle is inside on, if any; served by {@code gate_visit_one_open_idx}. */
    @Query("""
            SELECT * FROM gate_visit
             WHERE tenant_id = :tenantId AND registration_no = :registrationNo AND status = 'OPEN'
            """)
    Optional<GateVisit> findOpen(@Param("tenantId") UUID tenantId,
                                 @Param("registrationNo") String registrationNo);
}
