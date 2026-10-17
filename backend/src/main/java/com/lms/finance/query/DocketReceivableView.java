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

/** One docket's receivable, as a customer statement shows it. */
public record DocketReceivableView(
        UUID id,
        UUID docketId,
        String docketNo,
        String status,
        UUID customerPartnerId,
        String customerName,
        String currency,
        String rateCardCode,
        LocalDate pricedOn,
        String originZone,
        String destinationZone,
        BigDecimal deadWeightKg,
        BigDecimal volumeCft,
        BigDecimal volumetricWeightKg,
        BigDecimal chargeableWeightKg,
        boolean minimumApplied,
        boolean outOfDeliveryArea,
        String rateBasis,
        BigDecimal rateAmount,
        BigDecimal freightAmount,
        BigDecimal docketCharge,
        BigDecimal odaSurcharge,
        BigDecimal totalAmount,
        String reason,
        Instant createdAt) {
}
