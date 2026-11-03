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
package com.lms.sourcing.command;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.lms.sourcing.command.domain.Allocation;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;

public interface AllocationRepository extends CrudRepository<Allocation, UUID> {

    /**
     * The load's live allocation, if it has one. A CANCELLED allocation is
     * history and does not count: it is what lets a load whose award was
     * cancelled be offered again. Served by {@code allocation_live_load_unique},
     * which the literal predicate has to match.
     */
    @Query("""
            SELECT * FROM allocation
             WHERE tenant_id = :tenantId AND load_id = :loadId AND status <> 'CANCELLED'
            """)
    Optional<Allocation> findByLoad(@Param("tenantId") UUID tenantId, @Param("loadId") UUID loadId);

    /**
     * The allocations of these loads that are still out with a vendor or
     * awarded, for a cancelled load to take down. One read for all the loads.
     * EXHAUSTED is finished and CANCELLED is history, so neither is returned.
     */
    @Query("""
            SELECT * FROM allocation
             WHERE tenant_id = :tenantId AND load_id IN (:loadIds)
               AND status IN ('OFFERED', 'AWARDED')
            """)
    List<Allocation> findLiveForLoads(@Param("tenantId") UUID tenantId,
                                      @Param("loadIds") Collection<UUID> loadIds);
}
