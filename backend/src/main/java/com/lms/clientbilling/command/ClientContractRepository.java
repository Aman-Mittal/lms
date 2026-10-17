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
package com.lms.clientbilling.command;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import com.lms.clientbilling.command.domain.ClientContract;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;

public interface ClientContractRepository extends CrudRepository<ClientContract, UUID> {

    /** The contract version in force for a client on a date. */
    @Query("""
            SELECT * FROM client_contract
             WHERE tenant_id = :tenantId
               AND client_partner_id = :clientPartnerId
               AND effective_from <= :onDate
               AND (effective_to IS NULL OR effective_to >= :onDate)
             ORDER BY effective_from DESC
             LIMIT 1
            """)
    Optional<ClientContract> findInForceOn(@Param("tenantId") UUID tenantId,
                                           @Param("clientPartnerId") UUID clientPartnerId,
                                           @Param("onDate") LocalDate onDate);

    /** The open-ended version, of which the partial unique index permits one. */
    @Query("""
            SELECT * FROM client_contract
             WHERE tenant_id = :tenantId
               AND client_partner_id = :clientPartnerId
               AND effective_to IS NULL
            """)
    Optional<ClientContract> findOpenVersion(@Param("tenantId") UUID tenantId,
                                             @Param("clientPartnerId") UUID clientPartnerId);

    /** Whether the partner holds any contract at all -- which is what makes it a client. */
    @Query("""
            SELECT count(*) FROM client_contract
             WHERE tenant_id = :tenantId AND client_partner_id = :clientPartnerId
            """)
    long countForClient(@Param("tenantId") UUID tenantId,
                        @Param("clientPartnerId") UUID clientPartnerId);
}
