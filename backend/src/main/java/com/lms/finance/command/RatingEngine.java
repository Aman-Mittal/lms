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

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import com.lms.finance.command.domain.CostSheet;
import com.lms.finance.command.domain.FreightBillLine.ChargeType;
import com.lms.finance.command.domain.Tariff;
import com.lms.finance.command.domain.TariffSlab;
import org.springframework.stereotype.Component;

/**
 * Turns a movement into money (vision document 3.9.1).
 *
 * <p>Deliberately free of any database, clock or tenant. Everything it needs
 * arrives as an argument, so a rating question can be answered in a unit test
 * in microseconds and the answer is reproducible years later -- which is
 * exactly the property an invoice dispute needs.
 */
@Component
public class RatingEngine {

    /** Money is held to two places throughout; anything else is a rounding argument. */
    private static final int MONEY_SCALE = 2;

    /**
     * Prices one trip.
     *
     * @param slabs        the bands of {@code tariff}, in any order
     * @param originDwell  gate-in to gate-out, or null if the trip never gated in
     * @param dropCount    distinct delivery points, at least one
     * @param fuelPct      the surcharge percentage for the dispatch month, or null if
     *                     none has been published -- in which case none is charged
     */
    public CostSheet rate(Tariff tariff, List<TariffSlab> slabs, BigDecimal chargeableWeightKg,
                          Duration originDwell, int dropCount, BigDecimal fuelPct) {

        List<CostSheet.Charge> charges = new ArrayList<>();

        BigDecimal weight = chargeableWeightKg == null ? BigDecimal.ZERO : chargeableWeightKg;
        BigDecimal base = baseFreight(tariff, slabs, weight, charges);
        BigDecimal detentionHours = chargeableDetentionHours(tariff, originDwell);
        BigDecimal detention = detention(tariff, detentionHours, charges);
        BigDecimal drops = additionalDrops(tariff, dropCount, charges);
        BigDecimal fuel = fuelSurcharge(base, fuelPct, charges);

        return new CostSheet(tariff.currency(), weight, detentionHours, Math.max(dropCount, 1),
                base, detention, drops, fuel, List.copyOf(charges));
    }

    /**
     * The linehaul, from the band the chargeable weight falls in.
     *
     * <p>A lane with a minimum charge is billed the greater of the two, and the
     * uplift is shown as its own line rather than folded into the base. A
     * vendor looking at 8 000 for a 300 kg movement needs to see the words
     * "minimum charge" to understand it, and a controller reviewing margins
     * needs to see how often the floor is being hit.
     */
    private BigDecimal baseFreight(Tariff tariff, List<TariffSlab> slabs, BigDecimal weight,
                                   List<CostSheet.Charge> charges) {
        Optional<TariffSlab> band = slabs.stream()
                .sorted(Comparator.comparing(TariffSlab::minWeightKg))
                .filter(slab -> slab.covers(weight))
                .findFirst();

        if (band.isEmpty()) {
            // No band covers the weight. The floor is the only defensible
            // number left, and if there is no floor either the movement is
            // genuinely unpriceable -- which the caller turns into an UNPRICED
            // bill rather than a zero one.
            if (tariff.minimumCharge().signum() > 0) {
                charges.add(new CostSheet.Charge(ChargeType.BASE_FREIGHT,
                        "Minimum charge; no weight band covers " + weight + " kg",
                        weight, "kg", null, scale(tariff.minimumCharge())));
                return scale(tariff.minimumCharge());
            }
            return BigDecimal.ZERO;
        }

        TariffSlab slab = band.get();
        BigDecimal slabCharge = slab.chargeFor(weight);

        String narrative = slab.rateBasis() == TariffSlab.RateBasis.FLAT
                ? "Linehaul, flat rate for the " + bandLabel(slab) + " band"
                : "Linehaul at " + slab.rateAmount() + " per kg";

        charges.add(new CostSheet.Charge(ChargeType.BASE_FREIGHT, narrative,
                weight, "kg",
                slab.rateBasis() == TariffSlab.RateBasis.FLAT ? null : slab.rateAmount(),
                slabCharge));

        if (slabCharge.compareTo(tariff.minimumCharge()) < 0) {
            BigDecimal uplift = scale(tariff.minimumCharge().subtract(slabCharge));
            charges.add(new CostSheet.Charge(ChargeType.MINIMUM_CHARGE_UPLIFT,
                    "Uplift to the lane minimum of " + tariff.minimumCharge(),
                    null, null, null, uplift));
            return scale(tariff.minimumCharge());
        }
        return slabCharge;
    }

    /**
     * Dwell beyond the free allowance, in hours to two places.
     *
     * <p>Two places rather than whole hours because the alternative is arguing
     * about the twelve minutes: rounding up gives the vendor an hour they did
     * not wait, and rounding down gives them nothing for fifty-nine minutes
     * they did.
     */
    private BigDecimal chargeableDetentionHours(Tariff tariff, Duration originDwell) {
        if (originDwell == null || originDwell.isNegative()) {
            return BigDecimal.ZERO;
        }
        BigDecimal hours = BigDecimal.valueOf(originDwell.toMinutes())
                .divide(BigDecimal.valueOf(60), 2, RoundingMode.HALF_UP);
        return hours.subtract(tariff.detentionFreeHours()).max(BigDecimal.ZERO);
    }

    private BigDecimal detention(Tariff tariff, BigDecimal chargeableHours,
                                 List<CostSheet.Charge> charges) {
        if (chargeableHours.signum() == 0 || tariff.detentionHourlyRate().signum() == 0) {
            return BigDecimal.ZERO;
        }
        BigDecimal amount = scale(chargeableHours.multiply(tariff.detentionHourlyRate()));
        charges.add(new CostSheet.Charge(ChargeType.DETENTION,
                "Detention beyond " + tariff.detentionFreeHours() + " free hours at origin",
                chargeableHours, "h", tariff.detentionHourlyRate(), amount));
        return amount;
    }

    /**
     * The fee for every drop after the first.
     *
     * <p>The first is not additional -- it is the journey. Charging for it
     * would double-bill the linehaul, which already prices origin to final
     * drop.
     */
    private BigDecimal additionalDrops(Tariff tariff, int dropCount,
                                       List<CostSheet.Charge> charges) {
        int additional = Math.max(dropCount - 1, 0);
        if (additional == 0 || tariff.additionalDropFee().signum() == 0) {
            return BigDecimal.ZERO;
        }
        BigDecimal amount = scale(tariff.additionalDropFee()
                .multiply(BigDecimal.valueOf(additional)));
        charges.add(new CostSheet.Charge(ChargeType.ADDITIONAL_DROP,
                additional + " drop" + (additional == 1 ? "" : "s") + " beyond the first",
                BigDecimal.valueOf(additional), "drops", tariff.additionalDropFee(), amount));
        return amount;
    }

    /**
     * The fuel surcharge, applied to the linehaul only.
     *
     * <p>Not to the whole bill. Detention is a lorry standing still and a
     * multi-drop fee is a handling cost; neither burns the diesel the surcharge
     * exists to index. Applying it to the total would quietly inflate the
     * surcharge every time a vendor was kept waiting.
     */
    private BigDecimal fuelSurcharge(BigDecimal baseFreight, BigDecimal fuelPct,
                                     List<CostSheet.Charge> charges) {
        if (fuelPct == null || fuelPct.signum() == 0 || baseFreight.signum() == 0) {
            return BigDecimal.ZERO;
        }
        BigDecimal amount = scale(baseFreight.multiply(fuelPct)
                .divide(BigDecimal.valueOf(100), MONEY_SCALE, RoundingMode.HALF_UP));
        charges.add(new CostSheet.Charge(ChargeType.FUEL_SURCHARGE,
                "Fuel surcharge at " + fuelPct + "% of linehaul",
                null, null, fuelPct, amount));
        return amount;
    }

    private static String bandLabel(TariffSlab slab) {
        return slab.maxWeightKg() == null
                ? slab.minWeightKg() + " kg and above"
                : slab.minWeightKg() + "-" + slab.maxWeightKg() + " kg";
    }

    private static BigDecimal scale(BigDecimal value) {
        return value.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }
}
