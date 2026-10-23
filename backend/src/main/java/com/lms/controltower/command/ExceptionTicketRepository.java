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
package com.lms.controltower.command;

import java.util.Optional;
import java.util.UUID;

import com.lms.controltower.command.domain.ExceptionTicket;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;

public interface ExceptionTicketRepository extends CrudRepository<ExceptionTicket, UUID> {

    /** The ticket a detector already raised for this occurrence, if any. */
    @Query("SELECT * FROM exception_ticket WHERE tenant_id = :tenantId AND source_key = :sourceKey")
    Optional<ExceptionTicket> findBySourceKey(@Param("tenantId") UUID tenantId,
                                              @Param("sourceKey") String sourceKey);

    /**
     * The stoppage ticket still being worked on for a trip, if any. Served by
     * {@code exception_ticket_trip_idx}; a trip has a handful of tickets.
     */
    @Query("""
            SELECT * FROM exception_ticket
             WHERE tenant_id = :tenantId AND trip_id = :tripId
               AND source = 'STOPPAGE_DETECTED' AND status NOT IN ('RESOLVED', 'CLOSED')
             LIMIT 1
            """)
    Optional<ExceptionTicket> findOpenStoppage(@Param("tenantId") UUID tenantId,
                                               @Param("tripId") UUID tripId);
}
