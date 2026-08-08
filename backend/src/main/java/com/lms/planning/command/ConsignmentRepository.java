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
package com.lms.planning.command;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.lms.planning.command.domain.Consignment;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;

public interface ConsignmentRepository extends CrudRepository<Consignment, UUID> {

    @Query("SELECT * FROM consignment WHERE tenant_id = :tenantId AND tracking_ref = :trackingRef")
    Optional<Consignment> findByTrackingRef(@Param("tenantId") UUID tenantId,
                                            @Param("trackingRef") String trackingRef);

    /** Served by {@code consignment_order_idx}. */
    @Query("""
            SELECT * FROM consignment
             WHERE tenant_id = :tenantId AND order_id = :orderId
             ORDER BY tracking_ref
            """)
    List<Consignment> findByOrder(@Param("tenantId") UUID tenantId, @Param("orderId") UUID orderId);

    /**
     * The consignments already on a load.
     *
     * <p>Read before an assignment so the compatibility matrix can be applied
     * across everything that would share the vehicle, not just against the
     * consignment being added.
     */
    @Query("""
            SELECT c.* FROM consignment c
              JOIN load_consignment lc ON lc.consignment_id = c.id
             WHERE c.tenant_id = :tenantId AND lc.load_id = :loadId
             ORDER BY lc.drop_sequence
            """)
    List<Consignment> findOnLoad(@Param("tenantId") UUID tenantId, @Param("loadId") UUID loadId);
}
