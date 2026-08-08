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

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.lms.sourcing.command.domain.AllocationOffer;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;

public interface AllocationOfferRepository extends CrudRepository<AllocationOffer, UUID> {

    /** Served by {@code allocation_offer_allocation_idx}. */
    @Query("""
            SELECT * FROM allocation_offer
             WHERE tenant_id = :tenantId AND allocation_id = :allocationId
             ORDER BY rank_no
            """)
    List<AllocationOffer> findByAllocation(@Param("tenantId") UUID tenantId,
                                           @Param("allocationId") UUID allocationId);

    /** The offer currently out with a vendor, if any. */
    @Query("""
            SELECT * FROM allocation_offer
             WHERE tenant_id = :tenantId AND allocation_id = :allocationId
               AND outcome = 'PENDING'
             ORDER BY rank_no DESC
             LIMIT 1
            """)
    Optional<AllocationOffer> findPending(@Param("tenantId") UUID tenantId,
                                          @Param("allocationId") UUID allocationId);

    /**
     * Offers whose deadline has passed, for the timeout sweep.
     *
     * <p>Served by the partial index {@code allocation_offer_pending_idx}. The
     * {@code outcome = 'PENDING'} literal has to stay literal: Postgres will
     * only use a partial index for a query it can prove the index predicate
     * covers.
     */
    @Query("""
            SELECT * FROM allocation_offer
             WHERE tenant_id = :tenantId
               AND outcome = 'PENDING'
               AND responds_by <= :now
             ORDER BY responds_by
             LIMIT 200
            """)
    List<AllocationOffer> findLapsed(@Param("tenantId") UUID tenantId,
                                     @Param("now") java.time.Instant now);
}
