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

import com.lms.finance.command.domain.ManualCharge;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;

public interface ManualChargeRepository extends CrudRepository<ManualCharge, UUID> {

    /**
     * The approved payables of a vendor not yet on any bill, oldest first;
     * served by {@code manual_charge_waiting_idx}.
     */
    @Query("""
            SELECT * FROM manual_charge
             WHERE tenant_id = :tenantId AND vendor_partner_id = :vendorId AND direction = 'PAYABLE'
               AND status = 'APPROVED' AND freight_bill_id IS NULL
             ORDER BY created_at, id
            """)
    List<ManualCharge> findWaiting(@Param("tenantId") UUID tenantId, @Param("vendorId") UUID vendorId);
}
