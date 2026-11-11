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
package com.lms.approvals.command;

import java.util.Optional;
import java.util.UUID;

import com.lms.approvals.command.domain.ApprovalRequest;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;

public interface ApprovalRequestRepository extends CrudRepository<ApprovalRequest, UUID> {

    /** Served by the partial unique index {@code approval_request_open_idx}. */
    @Query("""
            SELECT * FROM approval_request
             WHERE tenant_id = :tenantId AND subject_type = :subjectType AND subject_id = :subjectId
               AND action = :action AND status = 'REQUESTED'
            """)
    Optional<ApprovalRequest> findOpen(@Param("tenantId") UUID tenantId, @Param("subjectType") String subjectType,
                                       @Param("subjectId") UUID subjectId, @Param("action") String action);
}
