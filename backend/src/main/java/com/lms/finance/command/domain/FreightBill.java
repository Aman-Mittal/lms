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
 */package com.lms.finance.command.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.lms.shared.error.BusinessRuleViolationException;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.relational.core.mapping.Table;

/**
 * What one trip cost, and whether the vendor's invoice for it agrees (3.9.2).
 *
 * <p>The bill holds two amounts. {@code computedAmount} is what the platform
 * says the movement cost, priced from the rate card in force on the dispatch
 * date. {@code claimedAmount} is what the vendor billed. Settlement is the
 * comparison of the two, and the reason both are kept is that the interesting
 * question is never "what do we owe" but "why do these differ".
 *
 * <p>The tariff version used is recorded on the row. Without it a dispute
 * raised months later can only be answered by re-running today's rates, which
 * produces a different number and settles nothing.
 */
@Table("freight_bill")
public record FreightBill(
        @Id UUID id,
        UUID tenantId,
        UUID orgUnitId,
        UUID tripId,
        UUID loadId,
        UUID vendorPartnerId,
        String billNo,
        BillStatus status,
        String currency,
        UUID tariffId,
        /** The dispatch date, which chose the tariff version. */
        LocalDate pricedOn,
        BigDecimal chargeableWeightKg,
        BigDecimal detentionHours,
        Integer dropCount,
        BigDecimal baseFreight,
        BigDecimal detentionAmount,
        BigDecimal multiDropAmount,
        BigDecimal fuelSurchargeAmount,
        BigDecimal computedAmount,
        BigDecimal claimedAmount,
        /** Claimed less computed. Signed, so an underclaim is visible as one. */
        BigDecimal varianceAmount,
        BigDecimal variancePct,
        String resolution,
        Instant matchedAt,
        @Version Long version,
        Instant createdAt,
        Instant updatedAt) {

    public enum BillStatus {
        /** Priced, waiting for the vendor to invoice. */
        DRAFT,
        /** No rate card covered this lane on the dispatch date. A human has to price it. */
        UNPRICED,
        APPROVED_FOR_PAYMENT,
        DISPUTED,
        CANCELLED
    }

    private static final Map<BillStatus, Set<BillStatus>> TRANSITIONS =
            new EnumMap<>(BillStatus.class);

    static {
        TRANSITIONS.put(BillStatus.DRAFT,
                EnumSet.of(BillStatus.APPROVED_FOR_PAYMENT, BillStatus.DISPUTED,
                        BillStatus.CANCELLED));
        // An unpriced bill can only be cancelled from here. Approving one would
        // mean paying an amount the platform never computed.
        TRANSITIONS.put(BillStatus.UNPRICED, EnumSet.of(BillStatus.CANCELLED));
        // A dispute is resolved, not reversed: it goes to payment with a reason
        // recorded, or the vendor sends a corrected invoice and it is matched
        // again -- which is the same transition.
        TRANSITIONS.put(BillStatus.DISPUTED,
                EnumSet.of(BillStatus.APPROVED_FOR_PAYMENT, BillStatus.DISPUTED,
                        BillStatus.CANCELLED));
        // Terminal. Approved means somebody has been told to pay it.
        TRANSITIONS.put(BillStatus.APPROVED_FOR_PAYMENT, EnumSet.noneOf(BillStatus.class));
        TRANSITIONS.put(BillStatus.CANCELLED, EnumSet.noneOf(BillStatus.class));
    }

    /**
     * A priced draft.
     *
     * @param sheet the arithmetic, already totalled
     */
    public static FreightBill priced(UUID id, UUID tenantId, UUID orgUnitId, UUID tripId,
                                     UUID loadId, UUID vendorPartnerId, String billNo,
                                     UUID tariffId, LocalDate pricedOn, CostSheet sheet) {
        return new FreightBill(id, tenantId, orgUnitId, tripId, loadId, vendorPartnerId,
                billNo, BillStatus.DRAFT, sheet.currency(), tariffId, pricedOn,
                sheet.chargeableWeightKg(), sheet.detentionHours(), sheet.dropCount(),
                sheet.baseFreight(), sheet.detentionAmount(), sheet.multiDropAmount(),
                sheet.fuelSurchargeAmount(), sheet.total(),
                null, null, null, null, null, null, Instant.now(), Instant.now());
    }

    /**
     * A bill that could not be priced.
     *
     * <p>Raised rather than skipped. A trip with no bill at all is invisible --
     * it looks exactly like a trip nobody has got to yet -- whereas an UNPRICED
     * row appears on the same screen as everything else and says why.
     */
    public static FreightBill unpriced(UUID id, UUID tenantId, UUID orgUnitId, UUID tripId,
                                       UUID loadId, UUID vendorPartnerId, String billNo,
                                       LocalDate pricedOn, String why) {
        return new FreightBill(id, tenantId, orgUnitId, tripId, loadId, vendorPartnerId,
                billNo, BillStatus.UNPRICED, "INR", null, pricedOn,
                null, null, null,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, null, null, null, why, null, null,
                Instant.now(), Instant.now());
    }

    // ------------------------------------------------------------- 3.9.2

    /**
     * Compares the vendor's invoice with what the platform computed.
     *
     * <p>The tolerance is a percentage <em>and</em> an absolute floor, and the
     * floor is what makes it usable. A pure percentage disputes a two-rupee
     * rounding difference on a small bill and waves through a five-figure gap
     * on a large one; taking the greater of the two means small differences are
     * ignored because they are small, not because the bill was.
     *
     * <p>An underclaim outside tolerance is disputed just as an overclaim is.
     * It is not free money: it means the vendor's understanding of the rate and
     * ours have diverged, and next month the divergence will point the other
     * way.
     */
    public FreightBill matchAgainst(BigDecimal claimed, BigDecimal tolerancePct,
                                    BigDecimal toleranceAbsolute, Instant at) {
        if (status == BillStatus.UNPRICED) {
            throw new BusinessRuleViolationException("bill-unpriced",
                    "Bill " + billNo + " has no computed amount to match against; "
                            + "no rate card covered this lane on " + pricedOn);
        }
        if (claimed == null || claimed.signum() < 0) {
            throw new IllegalArgumentException("A vendor claim cannot be negative");
        }

        BigDecimal variance = claimed.subtract(computedAmount).setScale(2, RoundingMode.HALF_UP);
        BigDecimal absVariance = variance.abs();

        BigDecimal proportional = computedAmount
                .multiply(tolerancePct)
                .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
        BigDecimal allowance = proportional.max(toleranceAbsolute);

        BigDecimal pct = computedAmount.signum() == 0
                ? null
                : absVariance.multiply(BigDecimal.valueOf(100))
                        .divide(computedAmount, 2, RoundingMode.HALF_UP);

        boolean within = absVariance.compareTo(allowance) <= 0;

        String note = within
                ? "Claimed " + claimed + " against computed " + computedAmount
                        + "; within the allowance of " + allowance
                : "Claimed " + claimed + " against computed " + computedAmount
                        + ", a difference of " + variance
                        + " beyond the allowance of " + allowance;

        return moveTo(within ? BillStatus.APPROVED_FOR_PAYMENT : BillStatus.DISPUTED)
                .withMatch(claimed, variance, pct, note, at);
    }

    /**
     * Settles a dispute in the vendor's favour, on the record.
     *
     * <p>The reason is mandatory. An approval with no explanation is
     * indistinguishable from one nobody looked at, and the whole value of the
     * tolerance check is that somebody had to look.
     */
    public FreightBill resolveDispute(String reason, Instant at) {
        if (status != BillStatus.DISPUTED) {
            throw new BusinessRuleViolationException("bill-not-disputed",
                    "Bill " + billNo + " is " + status + " and has no dispute to resolve");
        }
        if (reason == null || reason.isBlank()) {
            throw new BusinessRuleViolationException("resolution-not-explained",
                    "Approving a disputed bill requires a reason that will be read later");
        }
        return moveTo(BillStatus.APPROVED_FOR_PAYMENT)
                .withMatch(claimedAmount, varianceAmount, variancePct, reason, at);
    }

    public FreightBill cancel(String reason) {
        return moveTo(BillStatus.CANCELLED)
                .withMatch(claimedAmount, varianceAmount, variancePct, reason, matchedAt);
    }

    private FreightBill moveTo(BillStatus target) {
        if (!TRANSITIONS.getOrDefault(status, Set.of()).contains(target)) {
            throw new BusinessRuleViolationException("bill-illegal-transition",
                    "Bill " + billNo + " cannot move from " + status + " to " + target);
        }
        return new FreightBill(id, tenantId, orgUnitId, tripId, loadId, vendorPartnerId,
                billNo, target, currency, tariffId, pricedOn, chargeableWeightKg,
                detentionHours, dropCount, baseFreight, detentionAmount, multiDropAmount,
                fuelSurchargeAmount, computedAmount, claimedAmount, varianceAmount,
                variancePct, resolution, matchedAt, version, createdAt, Instant.now());
    }

    private FreightBill withMatch(BigDecimal claimed, BigDecimal variance, BigDecimal pct,
                                  String note, Instant at) {
        return new FreightBill(id, tenantId, orgUnitId, tripId, loadId, vendorPartnerId,
                billNo, status, currency, tariffId, pricedOn, chargeableWeightKg,
                detentionHours, dropCount, baseFreight, detentionAmount, multiDropAmount,
                fuelSurchargeAmount, computedAmount, claimed, variance, pct, note, at,
                version, createdAt, Instant.now());
    }
}
