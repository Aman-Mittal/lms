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
import java.util.List;
import java.util.UUID;

/**
 * A rate card version as it is read back whole: the card and its weight
 * bands, lightest first.
 *
 * <p>The card's fields are repeated rather than nested so that a read of one
 * card and a row of the listing have the same shape at the top level.
 */
public record TariffCardView(
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
        Instant createdAt,
        List<TariffSlabView> slabs) {

    public TariffCardView {
        slabs = slabs == null ? List.of() : List.copyOf(slabs);
    }

    static TariffCardView of(TariffView card, List<TariffSlabView> slabs) {
        return new TariffCardView(card.id(), card.orgUnitId(), card.orgUnitName(), card.code(),
                card.vendorPartnerId(), card.vendorCode(), card.vendorName(),
                card.originTerminalId(), card.originTerminalCode(),
                card.destinationTerminalId(), card.destinationTerminalCode(),
                card.vehicleType(), card.currency(), card.effectiveFrom(), card.effectiveTo(),
                card.superseded(), card.detentionFreeHours(), card.detentionHourlyRate(),
                card.additionalDropFee(), card.minimumCharge(), card.slabCount(),
                card.createdAt(), slabs);
    }
}
