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
package com.lms.masterdata.query;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.lms.masterdata.api.OpenOrderDemandSource;
import com.lms.masterdata.api.OpenOrderDemandSource.OpenOrderDemand;
import com.lms.masterdata.api.ReceivablesSource;
import com.lms.masterdata.api.ReceivablesSource.Receivables;
import com.lms.masterdata.api.ReceivablesSource.TransportTariff;
import com.lms.masterdata.query.CreditPositionView.Exposure;
import com.lms.masterdata.query.CreditPositionView.LimitState;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * The one definition of a customer's credit exposure.
 *
 * <p><b>Exposure = open orders + unbilled charges + outstanding invoices</b>,
 * each taxed as the client would be invoiced:
 * <ul>
 *   <li><b>Open orders</b>: every VALIDATED, PARTIALLY_PLANNED or FULLY_PLANNED
 *       order (accepted, not yet fulfilled or cancelled), plus the order under
 *       check even while it is a draft -- but only for the part of it not yet
 *       charged. Transport is charged per completed trip for the weight of the
 *       client's consignments aboard, so an order's <em>open weight</em> is its
 *       chargeable kg less the kg of its consignments already on a posted
 *       transport charge ({@link ReceivablesSource#chargedWeightByOrder}),
 *       never below zero; an order with no open weight left is not counted
 *       here at all. Orders carry no price, so the open weight is valued at the
 *       client contract in force today: kg times the transport rate per kg,
 *       never less than the contract's minimum charge, plus transport tax.
 *       Without a contract an order counts as zero and is reported as
 *       unpriced -- the limit cannot bite on a value nobody has agreed.</li>
 *   <li><b>Unbilled charges</b>: client charges not yet on an invoice, priced
 *       and taxed as the billing run would price them.</li>
 *   <li><b>Outstanding invoices</b>: issued invoice totals less credit notes
 *       and payments, never below zero.</li>
 * </ul>
 * Each kilogram is therefore counted exactly once: as open order weight until
 * the trip carrying it is charged, then as an unbilled charge until the month
 * is invoiced, then as an invoice until it is paid. It is never dropped
 * before its charge exists (the subtraction is by weight actually charged,
 * not by order status), and never counted in two parts at once. Nothing
 * resets on a calendar.
 *
 * <p>Not guarded: the credit gate calls it on behalf of whoever is validating
 * an order, who need not be able to read partners. The endpoint goes through
 * {@link CreditQueryService}, which is.
 */
@Service
public class CreditExposureService {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private final JdbcClient jdbc;
    private final List<OpenOrderDemandSource> orderSources;
    private final List<ReceivablesSource> receivableSources;

    public CreditExposureService(JdbcClient jdbc, List<OpenOrderDemandSource> orderSources,
                                 List<ReceivablesSource> receivableSources) {
        this.jdbc = jdbc;
        this.orderSources = orderSources;
        this.receivableSources = receivableSources;
    }

    private record PartnerRow(UUID id, String code, String status, BigDecimal creditLimit,
                              BigDecimal creditSoftLimit) {
    }

    /**
     * The position of a partner, counting {@code alsoCount} among its open
     * orders; empty for a partner the caller's tenant does not have.
     */
    public Optional<CreditPositionView> assess(UUID partnerId, UUID alsoCount) {
        Optional<PartnerRow> found = jdbc.sql("""
                        SELECT id, code, status, credit_limit, credit_soft_limit
                          FROM business_partner WHERE id = :id
                        """)
                .param("id", partnerId)
                .query(PartnerRow.class)
                .optional();
        if (found.isEmpty()) {
            return Optional.empty();
        }
        PartnerRow partner = found.get();
        LocalDate today = LocalDate.now(ZoneOffset.UTC);

        Receivables receivables = receivableSources.stream()
                .map(source -> source.receivablesOf(partnerId))
                .reduce(new Receivables(null, BigDecimal.ZERO, 0, 0, BigDecimal.ZERO),
                        CreditExposureService::combine);
        Optional<TransportTariff> tariff = receivableSources.stream()
                .map(source -> source.transportTariff(partnerId, today))
                .flatMap(Optional::stream)
                .findFirst();
        List<OpenOrderDemand> demanded = orderSources.stream()
                .flatMap(source -> source.openDemandOf(partnerId, alsoCount).stream())
                .toList();
        List<UUID> orderIds = demanded.stream().map(OpenOrderDemand::orderId).toList();
        Map<UUID, BigDecimal> charged = new HashMap<>();
        receivableSources.forEach(source -> source.chargedWeightByOrder(partnerId, orderIds)
                .forEach((order, kg) -> charged.merge(order, kg, BigDecimal::add)));
        List<OpenOrderDemand> orders = stillOpen(demanded, charged);

        BigDecimal openOrders = tariff
                .map(t -> orders.stream()
                        .map(order -> valueOf(order.chargeableWeightKg(), t))
                        .reduce(BigDecimal.ZERO, BigDecimal::add))
                .orElse(BigDecimal.ZERO);
        int unpricedOrders = tariff.isPresent() ? 0 : orders.size();

        BigDecimal total = money(openOrders.add(receivables.unbilledCharges())
                .add(receivables.outstandingInvoices()));
        Exposure exposure = new Exposure(money(openOrders), orders.size(), unpricedOrders,
                money(receivables.unbilledCharges()), receivables.unbilledChargeCount(),
                receivables.unpricedChargeCount(), money(receivables.outstandingInvoices()), total);

        BigDecimal limit = partner.creditLimit();
        BigDecimal soft = partner.creditSoftLimit();
        UUID pending = jdbc.sql("""
                        SELECT id FROM credit_limit_change
                         WHERE partner_id = :id AND status = 'PENDING'
                        """)
                .param("id", partnerId)
                .query(UUID.class)
                .optional()
                .orElse(null);
        String currency = tariff.map(TransportTariff::currency).orElse(receivables.currency());

        return Optional.of(new CreditPositionView(partner.id(), partner.code(), partner.status(),
                !"SUSPENDED".equals(partner.status()) && !"BLACKLISTED".equals(partner.status()),
                currency, limit, soft, exposure,
                limit == null ? null : limit.subtract(total),
                stateOf(total, limit, soft), pending, Instant.now()));
    }

    /**
     * The open part of each order: its weight less what is already on a posted
     * charge. An order fully charged drops out, so it is not counted (nor
     * floored at the minimum charge) a second time.
     */
    static List<OpenOrderDemand> stillOpen(List<OpenOrderDemand> orders,
                                           Map<UUID, BigDecimal> chargedKg) {
        return orders.stream()
                .map(order -> new OpenOrderDemand(order.orderId(), order.chargeableWeightKg()
                        .subtract(chargedKg.getOrDefault(order.orderId(), BigDecimal.ZERO))
                        .max(BigDecimal.ZERO)))
                .filter(order -> order.chargeableWeightKg().signum() > 0)
                .toList();
    }

    /**
     * Where an exposure stands against the limits. Reaching a limit exactly is
     * still within it; only going past it counts.
     */
    static LimitState stateOf(BigDecimal exposure, BigDecimal limit, BigDecimal soft) {
        if (limit == null) {
            return LimitState.NO_LIMIT;
        }
        if (exposure.compareTo(limit) > 0) {
            return LimitState.HARD_EXCEEDED;
        }
        if (soft != null && exposure.compareTo(soft) > 0) {
            return LimitState.SOFT_EXCEEDED;
        }
        return LimitState.WITHIN;
    }

    /** One order's value under a contract: rate per kg with its floor, then tax. */
    static BigDecimal valueOf(BigDecimal chargeableKg, TransportTariff tariff) {
        BigDecimal base = money(chargeableKg.multiply(tariff.ratePerKg()));
        BigDecimal floor = tariff.minimumCharge() == null ? BigDecimal.ZERO
                : money(tariff.minimumCharge());
        if (base.compareTo(floor) < 0) {
            base = floor;
        }
        BigDecimal taxPct = tariff.taxRatePct() == null ? BigDecimal.ZERO : tariff.taxRatePct();
        return base.add(money(base.multiply(taxPct).divide(HUNDRED, 6, RoundingMode.HALF_UP)));
    }

    private static Receivables combine(Receivables a, Receivables b) {
        return new Receivables(a.currency() != null ? a.currency() : b.currency(),
                a.unbilledCharges().add(b.unbilledCharges()),
                a.unbilledChargeCount() + b.unbilledChargeCount(),
                a.unpricedChargeCount() + b.unpricedChargeCount(),
                a.outstandingInvoices().add(b.outstandingInvoices()));
    }

    private static BigDecimal money(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }
}
