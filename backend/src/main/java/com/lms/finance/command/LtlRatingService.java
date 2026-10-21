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

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import com.lms.finance.command.domain.DocketReceivable;
import com.lms.finance.command.domain.FuelSurchargeIndex;
import com.lms.finance.command.domain.LtlQuote;
import com.lms.finance.command.domain.LtlRateCard;
import com.lms.planning.events.DocketBooked;
import com.lms.shared.audit.AuditTrail;
import com.lms.shared.config.Json;
import com.lms.shared.error.BusinessRuleViolationException;
import com.lms.shared.tenant.TenantContext;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Part-load rating: zones, rate cards, and the receivable each docket earns.
 *
 * <p>Zone and card maintenance are guarded by {@code TARIFF_MANAGE}, the
 * permission that already decides what a lane costs; deciding what a customer
 * pays is the same authority over the other side of the margin.
 */
@Service
public class LtlRatingService {

    private final LtlZones zones;
    private final LtlRateCardRepository cards;
    private final DocketReceivableRepository receivables;
    private final LtlRatingEngine engine;
    private final FuelSurchargeIndexRepository fuelIndex;
    private final AuditTrail audit;

    public LtlRatingService(LtlZones zones, LtlRateCardRepository cards,
                            DocketReceivableRepository receivables, LtlRatingEngine engine,
                            FuelSurchargeIndexRepository fuelIndex, AuditTrail audit) {
        this.zones = zones;
        this.cards = cards;
        this.receivables = receivables;
        this.engine = engine;
        this.fuelIndex = fuelIndex;
        this.audit = audit;
    }

    /**
     * Places a terminal in a rating zone, and says whether delivering there is
     * out of the delivery area.
     *
     * <p>Applies to the next docket priced, never to one already billed: a
     * receivable records the zones and surcharge it was priced with.
     */
    @Transactional
    @PreAuthorize("hasAuthority('TARIFF_MANAGE')")
    public void assignZone(UUID terminalId, String zoneCode, boolean outOfDeliveryArea) {
        String zone = LtlRateCard.zone(zoneCode);
        try {
            zones.assign(terminalId, zone, outOfDeliveryArea);
        } catch (DataIntegrityViolationException e) {
            throw new BusinessRuleViolationException("terminal-unknown",
                    "Terminal " + terminalId + " does not exist and cannot be zoned");
        }
        audit.record("LTL_ZONE_ASSIGNED", "TERMINAL", terminalId, null,
                Json.object("zone", zone, "outOfDeliveryArea", String.valueOf(outOfDeliveryArea)));
    }

    /**
     * Publishes a version of the price between two zones.
     *
     * <p>A successor must start after the version it supersedes. Backdating
     * one would reprice, without trace, every docket booked between the two
     * dates the next time anybody recomputed it.
     */
    @Transactional
    @PreAuthorize("hasAuthority('TARIFF_MANAGE')")
    public UUID publishRateCard(String code, String originZone, String destinationZone,
                                String currency, LocalDate effectiveFrom,
                                LtlRateCard.RateBasis rateBasis, BigDecimal rateAmount,
                                BigDecimal minimumChargeableWeightKg, BigDecimal kgPerCft,
                                BigDecimal docketCharge, BigDecimal odaSurcharge) {
        return publishRateCard(code, originZone, destinationZone, currency, effectiveFrom,
                rateBasis, rateAmount, minimumChargeableWeightKg, kgPerCft, docketCharge,
                odaSurcharge, LtlRateCard.OdaBasis.FLAT, null, null);
    }

    /**
     * Publishes a version with a stated ODA basis: a flat amount, or a rate
     * per kg with a minimum. Like every other term, fixed for the life of the
     * version.
     */
    @Transactional
    @PreAuthorize("hasAuthority('TARIFF_MANAGE')")
    public UUID publishRateCard(String code, String originZone, String destinationZone,
                                String currency, LocalDate effectiveFrom,
                                LtlRateCard.RateBasis rateBasis, BigDecimal rateAmount,
                                BigDecimal minimumChargeableWeightKg, BigDecimal kgPerCft,
                                BigDecimal docketCharge, BigDecimal odaSurcharge,
                                LtlRateCard.OdaBasis odaBasis, BigDecimal odaRatePerKg,
                                BigDecimal odaMinimum) {
        UUID tenantId = TenantContext.requireTenantId();

        LtlRateCard card = LtlRateCard.publish(UUID.randomUUID(), tenantId, code, originZone,
                destinationZone, currency, effectiveFrom, rateBasis, rateAmount,
                minimumChargeableWeightKg, kgPerCft, docketCharge, odaSurcharge,
                odaBasis, odaRatePerKg, odaMinimum);

        cards.findLatest(tenantId, card.originZone(), card.destinationZone()).ifPresent(latest -> {
            if (!card.effectiveFrom().isAfter(latest.effectiveFrom())) {
                throw new BusinessRuleViolationException("ltl-rate-card-backdated",
                        "Rate card " + latest.code() + " for " + card.originZone() + " to "
                                + card.destinationZone() + " starts on " + latest.effectiveFrom()
                                + "; a successor cannot start on " + card.effectiveFrom());
            }
        });

        LtlRateCard published = cards.save(card);
        audit.record("LTL_RATE_CARD_PUBLISHED", "LTL_RATE_CARD", published.id(), null,
                Json.object("code", published.code(),
                        "lane", published.originZone() + "-" + published.destinationZone(),
                        "effectiveFrom", published.effectiveFrom().toString(),
                        "odaBasis", published.odaBasis().name()));
        return published.id();
    }

    /**
     * Raises the receivable for a docket that has just been booked.
     *
     * <p>Unguarded by {@code @PreAuthorize}, like the freight bill: the actor
     * is the platform reacting to a booking, and a tenant that forgot to grant
     * a permission must not quietly stop billing its customers.
     *
     * <p>Idempotent on the docket. The unique constraint is the guarantee;
     * this check is what turns a redelivered event into a no-op rather than a
     * constraint violation.
     *
     * <p>A docket that cannot be priced -- a hub with no zone, or no card for
     * the zone pair on the booking date -- still gets a receivable, marked
     * UNPRICED with the reason, so the gap is visible on the same screen as
     * everything else.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public UUID rateDocket(DocketBooked docket) {
        UUID tenantId = TenantContext.requireTenantId();

        Optional<DocketReceivable> already = receivables.findByDocket(tenantId, docket.docketId());
        if (already.isPresent()) {
            return already.get().id();
        }

        LocalDate pricedOn = (docket.bookedAt() == null ? Instant.now() : docket.bookedAt())
                .atZone(ZoneOffset.UTC).toLocalDate();

        Optional<String> originZone = zones.placementOf(docket.originHubTerminalId())
                .map(LtlZones.Placement::zoneCode);
        Optional<String> destinationZone = zones.placementOf(docket.destinationHubTerminalId())
                .map(LtlZones.Placement::zoneCode);

        if (originZone.isEmpty() || destinationZone.isEmpty()) {
            return receivables.save(DocketReceivable.unpriced(UUID.randomUUID(), tenantId,
                    docket.docketId(), docket.docketNo(), docket.orderId(),
                    docket.customerPartnerId(), pricedOn, originZone.orElse(null),
                    destinationZone.orElse(null), docket.deadWeightKg(),
                    "The " + (originZone.isEmpty() ? "origin" : "destination")
                            + " hub of this docket is not in any LTL zone")).id();
        }

        Optional<LtlRateCard> card = cards.findInForceOn(tenantId, originZone.get(),
                destinationZone.get(), pricedOn);
        if (card.isEmpty()) {
            return receivables.save(DocketReceivable.unpriced(UUID.randomUUID(), tenantId,
                    docket.docketId(), docket.docketNo(), docket.orderId(),
                    docket.customerPartnerId(), pricedOn, originZone.get(), destinationZone.get(),
                    docket.deadWeightKg(),
                    "No LTL rate card from zone " + originZone.get() + " to zone "
                            + destinationZone.get() + " was in force on " + pricedOn)).id();
        }

        // Out of the delivery area is a property of where the docket is finally
        // delivered, not of the hub: the hub is in the network by definition.
        boolean oda = zones.placementOf(docket.deliveryTerminalId())
                .map(LtlZones.Placement::outOfDeliveryArea)
                .orElse(false);

        // The month of booking, as the rate card is the one in force on the
        // booking date: a docket is quoted when it is cut, so it is priced
        // entirely as of that day.
        BigDecimal fuelPct = fuelIndex.findForMonth(tenantId, pricedOn.withDayOfMonth(1))
                .map(FuelSurchargeIndex::surchargePct)
                .orElse(null);

        LtlQuote quote = engine.quote(card.get(), docket.deadWeightKg(), docket.volumeM3(), oda,
                fuelPct);
        return receivables.save(DocketReceivable.priced(UUID.randomUUID(), tenantId,
                docket.docketId(), docket.docketNo(), docket.orderId(), docket.customerPartnerId(),
                card.get(), pricedOn, docket.deadWeightKg(), quote)).id();
    }
}
