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

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.lms.masterdata.command.domain.ComplianceDocument;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;

public interface ComplianceDocumentRepository extends CrudRepository<ComplianceDocument, UUID> {

    @Query("""
            SELECT * FROM compliance_document
             WHERE tenant_id = :tenantId AND owner_type = :ownerType AND owner_id = :ownerId
             ORDER BY document_type
            """)
    List<ComplianceDocument> findForOwner(@Param("tenantId") UUID tenantId,
                                          @Param("ownerType") String ownerType,
                                          @Param("ownerId") UUID ownerId);

    /**
     * Documents that have lapsed as at the given date.
     *
     * <p>Note {@code expires_on IS NOT NULL}: a document with no expiry never
     * expires, and omitting this would treat every permanent registration as
     * lapsed and block the entire fleet.
     */
    @Query("""
            SELECT * FROM compliance_document
             WHERE tenant_id = :tenantId AND owner_type = :ownerType AND owner_id = :ownerId
               AND expires_on IS NOT NULL AND expires_on < :on
            """)
    List<ComplianceDocument> findExpired(@Param("tenantId") UUID tenantId,
                                         @Param("ownerType") String ownerType,
                                         @Param("ownerId") UUID ownerId,
                                         @Param("on") LocalDate on);
}
