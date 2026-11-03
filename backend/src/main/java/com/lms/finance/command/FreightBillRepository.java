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
 */package com.lms.finance.command;

import java.util.Optional;
import java.util.UUID;

import com.lms.finance.command.domain.FreightBill;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;

public interface FreightBillRepository extends CrudRepository<FreightBill, UUID> {

    /**
     * The bill that pays for a trip: the live one when there is one, otherwise
     * the most recent.
     *
     * <p>A trip has at most one live bill ({@code freight_bill_trip_live_idx}),
     * but may also have a cancelled one -- a trip bill withdrawn because the
     * trip turned out to carry a milk-run circuit, which is paid for by its
     * own bill. Served by {@code freight_bill_trip_idx}.
     */
    @Query("""
            SELECT * FROM freight_bill
             WHERE tenant_id = :tenantId AND trip_id = :tripId
             ORDER BY (status = 'CANCELLED'), created_at DESC
             LIMIT 1
            """)
    Optional<FreightBill> findByTrip(@Param("tenantId") UUID tenantId,
                                     @Param("tripId") UUID tripId);

    /** Served by {@code freight_bill_circuit_run_idx}. */
    @Query("SELECT * FROM freight_bill WHERE tenant_id = :tenantId AND circuit_run_id = :runId")
    Optional<FreightBill> findByCircuitRun(@Param("tenantId") UUID tenantId,
                                           @Param("runId") UUID runId);

    /** Served by {@code freight_bill_itinerary_leg_idx}. */
    @Query("SELECT * FROM freight_bill WHERE tenant_id = :tenantId AND itinerary_leg_id = :legId")
    Optional<FreightBill> findByItineraryLeg(@Param("tenantId") UUID tenantId,
                                             @Param("legId") UUID legId);

    @Query("SELECT * FROM freight_bill WHERE tenant_id = :tenantId AND bill_no = :billNo")
    Optional<FreightBill> findByBillNo(@Param("tenantId") UUID tenantId,
                                       @Param("billNo") String billNo);
}
