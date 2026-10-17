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

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import com.lms.finance.command.domain.LtlRateCard;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;

public interface LtlRateCardRepository extends CrudRepository<LtlRateCard, UUID> {

    /**
     * The version in force between two zones on a day: the latest to have
     * started by then. Served by {@code ltl_rate_card_lookup_idx}, whose
     * descending date lets the plan stop at the first row.
     */
    @Query("""
            SELECT * FROM ltl_rate_card
             WHERE tenant_id = :tenantId
               AND origin_zone = :originZone
               AND destination_zone = :destinationZone
               AND effective_from <= :onDate
             ORDER BY effective_from DESC
             LIMIT 1
            """)
    Optional<LtlRateCard> findInForceOn(@Param("tenantId") UUID tenantId,
                                        @Param("originZone") String originZone,
                                        @Param("destinationZone") String destinationZone,
                                        @Param("onDate") LocalDate onDate);

    /** The most recent version for a lane, whether or not it has started yet. */
    @Query("""
            SELECT * FROM ltl_rate_card
             WHERE tenant_id = :tenantId
               AND origin_zone = :originZone
               AND destination_zone = :destinationZone
             ORDER BY effective_from DESC
             LIMIT 1
            """)
    Optional<LtlRateCard> findLatest(@Param("tenantId") UUID tenantId,
                                     @Param("originZone") String originZone,
                                     @Param("destinationZone") String destinationZone);
}
