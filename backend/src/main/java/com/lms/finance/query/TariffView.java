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
package com.lms.finance.query;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * One version of a road rate card, with the codes and names it refers to.
 *
 * @param effectiveTo inclusive last day; null while no successor has been published
 * @param superseded  whether a successor has closed this version
 * @param slabCount   how many weight bands the version carries
 */
public record TariffView(
        UUID id,
        UUID orgUnitId,
        String orgUnitName,
        String code,
        UUID vendorPartnerId,
        String vendorCode,
        String vendorName,
        UUID originTerminalId,
        String originTerminalCode,
        UUID destinationTerminalId,
        String destinationTerminalCode,
        String vehicleType,
        String currency,
        LocalDate effectiveFrom,
        LocalDate effectiveTo,
        boolean superseded,
        BigDecimal detentionFreeHours,
        BigDecimal detentionHourlyRate,
        BigDecimal additionalDropFee,
        BigDecimal minimumCharge,
        int slabCount,
        Instant createdAt) {
}
