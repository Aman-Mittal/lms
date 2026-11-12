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

package com.lms.finance.command;

import java.util.List;
import java.util.UUID;

import com.lms.finance.command.domain.VendorAdvance;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;

public interface VendorAdvanceRepository extends CrudRepository<VendorAdvance, UUID> {

    /** The advances paid and not yet deducted on a trip, oldest payment first; served by {@code vendor_advance_trip_idx}. */
    @Query("""
            SELECT * FROM vendor_advance
             WHERE tenant_id = :tenantId AND trip_id = :tripId AND status = 'PAID'
             ORDER BY paid_on, created_at, id
            """)
    List<VendorAdvance> findPaidOnTrip(@Param("tenantId") UUID tenantId, @Param("tripId") UUID tripId);
}
