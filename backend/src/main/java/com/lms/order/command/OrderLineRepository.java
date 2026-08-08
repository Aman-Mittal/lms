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
package com.lms.order.command;

import java.util.List;
import java.util.UUID;

import com.lms.order.command.domain.OrderLine;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;

public interface OrderLineRepository extends CrudRepository<OrderLine, UUID> {

    /** Served by {@code order_line_order_idx}. */
    @Query("""
            SELECT * FROM order_line
             WHERE tenant_id = :tenantId AND order_id = :orderId
             ORDER BY line_no
            """)
    List<OrderLine> findByOrder(@Param("tenantId") UUID tenantId, @Param("orderId") UUID orderId);

    /**
     * Lines still waiting for a consignment.
     *
     * <p>Served by the partial index {@code order_line_unplanned_idx}, whose
     * {@code WHERE planned = false} clause matches this predicate exactly --
     * Postgres will only use a partial index for a query it can prove is
     * covered by the index predicate, so the literal must stay literal here.
     */
    @Query("""
            SELECT * FROM order_line
             WHERE tenant_id = :tenantId AND order_id = :orderId AND planned = false
             ORDER BY line_no
            """)
    List<OrderLine> findUnplannedByOrder(@Param("tenantId") UUID tenantId,
                                         @Param("orderId") UUID orderId);

    @Query("SELECT count(*) FROM order_line WHERE tenant_id = :tenantId AND order_id = :orderId")
    long countByOrder(@Param("tenantId") UUID tenantId, @Param("orderId") UUID orderId);

    @Query("""
            SELECT count(*) FROM order_line
             WHERE tenant_id = :tenantId AND order_id = :orderId AND planned = true
            """)
    long countPlannedByOrder(@Param("tenantId") UUID tenantId, @Param("orderId") UUID orderId);
}
