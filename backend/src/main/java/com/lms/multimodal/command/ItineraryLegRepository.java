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
package com.lms.multimodal.command;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.lms.multimodal.command.domain.ItineraryLeg;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;

public interface ItineraryLegRepository extends CrudRepository<ItineraryLeg, UUID> {

    /** The leg a trip runs, if it runs one. At most one, by a unique index. */
    @Query("SELECT * FROM itinerary_leg WHERE tenant_id = :tenantId AND trip_id = :tripId")
    Optional<ItineraryLeg> findByTrip(@Param("tenantId") UUID tenantId,
                                      @Param("tripId") UUID tripId);

    @Query("""
            SELECT * FROM itinerary_leg
             WHERE tenant_id = :tenantId AND itinerary_id = :itineraryId
             ORDER BY seq
            """)
    List<ItineraryLeg> findByItinerary(@Param("tenantId") UUID tenantId,
                                       @Param("itineraryId") UUID itineraryId);

    /**
     * Legs still waiting on a predecessor that has not arrived, whose
     * predecessor's planned arrival plus the transfer dwell has passed, and
     * for which there is no longer time to transfer before the planned
     * departure -- the missed-connection sweep's candidates. The same test as
     * {@link ItineraryLeg#isOverdueAfter}, which has the final say.
     *
     * <p>Served by {@code itinerary_leg_awaiting_start_idx}, a partial index
     * whose predicate is repeated literally here so the planner can use it;
     * each predecessor is found through the (itinerary, seq) index. Bounded,
     * because a sweep that falls behind should catch up over several runs
     * rather than in one long transaction.
     */
    @Query("""
            SELECT n.* FROM itinerary_leg n
              JOIN itinerary_leg p
                ON p.tenant_id = n.tenant_id
               AND p.itinerary_id = n.itinerary_id
               AND p.seq = n.seq - 1
             WHERE n.tenant_id = :tenantId
               AND n.departed_at IS NULL
               AND n.connection_missed_at IS NULL
               AND n.seq > 1
               AND p.arrived_at IS NULL
               AND p.planned_arrival_at + n.transfer_dwell_minutes * INTERVAL '1 minute' < CAST(:now AS timestamptz)
               AND n.planned_departure_at < CAST(:now AS timestamptz) + n.transfer_dwell_minutes * INTERVAL '1 minute'
             ORDER BY n.planned_departure_at, n.id
             LIMIT 200
            """)
    List<ItineraryLeg> findAwaitingOverduePredecessor(@Param("tenantId") UUID tenantId,
                                                      @Param("now") Instant now);
}
