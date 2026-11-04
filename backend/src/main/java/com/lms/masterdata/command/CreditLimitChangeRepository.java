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
package com.lms.masterdata.command;

import java.util.Optional;
import java.util.UUID;

import com.lms.masterdata.command.domain.CreditLimitChange;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;

public interface CreditLimitChangeRepository extends CrudRepository<CreditLimitChange, UUID> {

    /** Served by the partial unique index {@code credit_limit_change_one_pending}. */
    @Query("""
            SELECT * FROM credit_limit_change
             WHERE tenant_id = :tenantId AND partner_id = :partnerId AND status = 'PENDING'
            """)
    Optional<CreditLimitChange> findPending(@Param("tenantId") UUID tenantId,
                                            @Param("partnerId") UUID partnerId);
}
