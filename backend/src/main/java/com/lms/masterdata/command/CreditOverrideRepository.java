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

import java.util.List;
import java.util.UUID;

import com.lms.masterdata.command.domain.CreditOverride;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;

public interface CreditOverrideRepository extends CrudRepository<CreditOverride, UUID> {

    /** Unused overrides for one subject, oldest first. Served by {@code credit_override_open_idx}. */
    @Query("""
            SELECT * FROM credit_override
             WHERE tenant_id = :tenantId AND partner_id = :partnerId
               AND subject_key = :subjectKey AND used_at IS NULL
             ORDER BY granted_at, id
            """)
    List<CreditOverride> findUnused(@Param("tenantId") UUID tenantId,
                                    @Param("partnerId") UUID partnerId,
                                    @Param("subjectKey") String subjectKey);
}
