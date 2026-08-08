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

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import com.lms.finance.command.domain.Tariff;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;

public interface TariffRepository extends CrudRepository<Tariff, UUID> {

    /**
     * The rate card in force for this vendor, lane and vehicle type on a date.
     *
     * <p>Served by {@code tariff_lookup_idx}, whose descending
     * {@code effective_from} lets the plan stop at the first matching row
     * rather than reading a lane's whole rate history to sort it.
     */
    @Query("""
            SELECT * FROM tariff
             WHERE tenant_id = :tenantId
               AND vendor_partner_id = :vendorPartnerId
               AND origin_terminal_id = :originTerminalId
               AND destination_terminal_id = :destinationTerminalId
               AND vehicle_type = :vehicleType
               AND effective_from <= :onDate
               AND (effective_to IS NULL OR effective_to >= :onDate)
             ORDER BY effective_from DESC
             LIMIT 1
            """)
    Optional<Tariff> findInForceOn(@Param("tenantId") UUID tenantId,
                                   @Param("vendorPartnerId") UUID vendorPartnerId,
                                   @Param("originTerminalId") UUID originTerminalId,
                                   @Param("destinationTerminalId") UUID destinationTerminalId,
                                   @Param("vehicleType") String vehicleType,
                                   @Param("onDate") LocalDate onDate);

    /** The open-ended version, of which the partial unique index permits exactly one. */
    @Query("""
            SELECT * FROM tariff
             WHERE tenant_id = :tenantId
               AND vendor_partner_id = :vendorPartnerId
               AND origin_terminal_id = :originTerminalId
               AND destination_terminal_id = :destinationTerminalId
               AND vehicle_type = :vehicleType
               AND effective_to IS NULL
            """)
    Optional<Tariff> findOpenVersion(@Param("tenantId") UUID tenantId,
                                     @Param("vendorPartnerId") UUID vendorPartnerId,
                                     @Param("originTerminalId") UUID originTerminalId,
                                     @Param("destinationTerminalId") UUID destinationTerminalId,
                                     @Param("vehicleType") String vehicleType);

    @Query("SELECT * FROM tariff WHERE tenant_id = :tenantId AND code = :code "
            + "ORDER BY effective_from DESC LIMIT 1")
    Optional<Tariff> findLatestByCode(@Param("tenantId") UUID tenantId,
                                      @Param("code") String code);
}
