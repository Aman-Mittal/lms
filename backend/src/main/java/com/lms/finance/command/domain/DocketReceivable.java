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
package com.lms.finance.command.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.relational.core.mapping.Table;

/**
 * What one part-load docket is billed to its customer.
 *
 * <p>The receivable side of LTL, kept apart from {@link FreightBill}, which is
 * the payable side: one vehicle carries many customers' dockets, earns one of
 * these per docket, and costs one freight bill from its carrier. Folding the
 * two together would make it impossible to say whether a consolidated lorry
 * made money.
 *
 * <p>Everything the price came from is stored on the row -- zones, weights,
 * the rate, whether the minimum or the ODA surcharge applied -- so a dispute is
 * argued from what was charged rather than by re-running today's zones.
 */
@Table("docket_receivable")
public record DocketReceivable(
        @Id UUID id,
        UUID tenantId,
        UUID docketId,
        String docketNo,
        UUID orderId,
        UUID customerPartnerId,
        Status status,
        String currency,
        UUID rateCardId,
        /** The booking date, which chose the rate card version. */
        LocalDate pricedOn,
        String originZone,
        String destinationZone,
        BigDecimal deadWeightKg,
        BigDecimal volumeCft,
        BigDecimal volumetricWeightKg,
        BigDecimal chargeableWeightKg,
        boolean minimumApplied,
        boolean outOfDeliveryArea,
        LtlRateCard.RateBasis rateBasis,
        BigDecimal rateAmount,
        BigDecimal freightAmount,
        BigDecimal docketCharge,
        BigDecimal odaSurcharge,
        /** FLAT or PER_KG when an ODA charge was made; null otherwise. */
        LtlRateCard.OdaBasis odaBasis,
        /**
         * The fuel index of the booking month, recorded beside the amount so a
         * dispute can see which month's figure was used; null when that month
         * had no index and no fuel was charged.
         */
        BigDecimal fuelSurchargePct,
        /** That percentage of the freight amount, as its own line of the total. */
        BigDecimal fuelSurcharge,
        BigDecimal totalAmount,
        /** Why a docket could not be priced; null when it was. */
        String reason,
        @Version Long version,
        Instant createdAt) {

    public enum Status {
        PRICED,
        /**
         * No zone or no rate card covered the docket on its booking date.
         * Raised rather than skipped, as an unpriced freight bill is: a docket
         * with no receivable at all looks exactly like one nobody has got to.
         */
        UNPRICED,
        /**
         * The docket was cancelled, so nothing is billable. The row and the
         * amounts it was quoted at are kept -- a dispute is argued from what
         * was charged -- and {@code reason} says why. Never reached from an
         * invoice: a docket receivable is on none today, and one that is must
         * be credited rather than cancelled.
         */
        CANCELLED
    }

    /** This receivable, cancelled with the reason; a repeat returns it unchanged. */
    public DocketReceivable cancel(String why) {
        if (status == Status.CANCELLED) {
            return this;
        }
        return new DocketReceivable(id, tenantId, docketId, docketNo, orderId, customerPartnerId,
                Status.CANCELLED, currency, rateCardId, pricedOn, originZone, destinationZone,
                deadWeightKg, volumeCft, volumetricWeightKg, chargeableWeightKg, minimumApplied,
                outOfDeliveryArea, rateBasis, rateAmount, freightAmount, docketCharge,
                odaSurcharge, odaBasis, fuelSurchargePct, fuelSurcharge, totalAmount, why,
                version, createdAt);
    }

    public static DocketReceivable priced(UUID id, UUID tenantId, UUID docketId, String docketNo,
                                          UUID orderId, UUID customerPartnerId, LtlRateCard card,
                                          LocalDate pricedOn, BigDecimal deadWeightKg,
                                          LtlQuote quote) {
        return new DocketReceivable(id, tenantId, docketId, docketNo, orderId, customerPartnerId,
                Status.PRICED, card.currency(), card.id(), pricedOn, card.originZone(),
                card.destinationZone(), deadWeightKg, quote.volumeCft(),
                quote.volumetricWeightKg(), quote.chargeableWeightKg(), quote.minimumApplied(),
                quote.outOfDeliveryArea(), card.rateBasis(), card.rateAmount(),
                quote.freightAmount(), quote.docketCharge(), quote.odaSurcharge(),
                quote.odaBasis(), quote.fuelSurchargePct(), quote.fuelSurcharge(), quote.total(),
                null, null, Instant.now());
    }

    public static DocketReceivable unpriced(UUID id, UUID tenantId, UUID docketId, String docketNo,
                                            UUID orderId, UUID customerPartnerId,
                                            LocalDate pricedOn, String originZone,
                                            String destinationZone, BigDecimal deadWeightKg,
                                            String why) {
        return new DocketReceivable(id, tenantId, docketId, docketNo, orderId, customerPartnerId,
                Status.UNPRICED, "INR", null, pricedOn, originZone, destinationZone, deadWeightKg,
                null, null, null, false, false, null, null,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, null, null, BigDecimal.ZERO,
                BigDecimal.ZERO, why, null, Instant.now());
    }
}
