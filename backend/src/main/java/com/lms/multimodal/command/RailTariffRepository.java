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

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import com.lms.multimodal.command.domain.RailTariff;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;

public interface RailTariffRepository extends CrudRepository<RailTariff, UUID> {

    /**
     * The card in force for this carrier and railhead pair on a day. Served by
     * {@code rail_tariff_lookup_idx}; the newest applicable version comes first.
     */
    @Query("""
            SELECT * FROM rail_tariff
             WHERE tenant_id = :tenantId
               AND carrier_partner_id = :carrierPartnerId
               AND origin_terminal_id = :originTerminalId
               AND destination_terminal_id = :destinationTerminalId
               AND effective_from <= :onDate
               AND (effective_to IS NULL OR effective_to >= :onDate)
             ORDER BY effective_from DESC
             LIMIT 1
            """)
    Optional<RailTariff> findInForceOn(@Param("tenantId") UUID tenantId,
                                       @Param("carrierPartnerId") UUID carrierPartnerId,
                                       @Param("originTerminalId") UUID originTerminalId,
                                       @Param("destinationTerminalId") UUID destinationTerminalId,
                                       @Param("onDate") LocalDate onDate);

    /** The open-ended version, of which the partial unique index permits one. */
    @Query("""
            SELECT * FROM rail_tariff
             WHERE tenant_id = :tenantId
               AND carrier_partner_id = :carrierPartnerId
               AND origin_terminal_id = :originTerminalId
               AND destination_terminal_id = :destinationTerminalId
               AND effective_to IS NULL
            """)
    Optional<RailTariff> findOpenVersion(@Param("tenantId") UUID tenantId,
                                         @Param("carrierPartnerId") UUID carrierPartnerId,
                                         @Param("originTerminalId") UUID originTerminalId,
                                         @Param("destinationTerminalId") UUID destinationTerminalId);
}
