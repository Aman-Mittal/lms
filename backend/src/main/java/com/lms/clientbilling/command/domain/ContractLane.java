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
package com.lms.clientbilling.command.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * One lane of a client contract version: where freight moves, how, and what it
 * costs.
 *
 * <p>A lane is part of its version, never a record of its own: the database
 * lets it change only while the version is a draft and copies it into a
 * charge-add successor, so a lane in force is as immutable as the rates beside
 * it. Pure and database-free, so matching and pricing are unit tests.
 *
 * <p>An end of the lane is a terminal or an LTL rating zone ({@link Place}).
 * Freight mode and load type are optional: a lane that names neither covers
 * every order between its two places. The price is a {@link RateBasis} and
 * rate, or weight slabs (copied from a carrier rate card's
 * {@code TariffSlab}: half-open {@code [min, max)} bands, each with its own
 * basis) which win when one covers the weight.
 *
 * @param consignors  partners allowed to consign on the lane; empty is anyone
 * @param consignees  partners allowed to receive on it; empty is anyone
 */
public record ContractLane(
        UUID id,
        UUID contractId,
        String laneCode,
        Place origin,
        Place destination,
        String freightMode,
        String loadType,
        RateBasis rateBasis,
        BigDecimal rate,
        Integer transitDays,
        List<Slab> slabs,
        Set<UUID> consignors,
        Set<UUID> consignees) {

    /** How a rate turns a movement into money. */
    public enum RateBasis {
        /** Multiplied by the chargeable weight in kilograms. */
        PER_KG,
        /** The whole movement, whatever is aboard. */
        PER_TRIP,
        /** Multiplied by the chargeable weight in tonnes. */
        PER_TONNE;

        public String unit() {
            return switch (this) {
                case PER_KG -> "KG";
                case PER_TRIP -> "TRIP";
                case PER_TONNE -> "TONNE";
            };
        }
    }

    /** An end of a lane: one terminal, or every terminal of an LTL rating zone. Exactly one is set. */
    public record Place(UUID terminalId, String zone) {

        public Place {
            if ((terminalId == null) == (zone == null || zone.isBlank())) {
                throw new IllegalArgumentException("A lane end is a terminal or a zone code, not both and not neither");
            }
            zone = zone == null || zone.isBlank() ? null : zone.strip().toUpperCase(Locale.ROOT);
        }

        public static Place terminal(UUID terminalId) {
            return new Place(terminalId, null);
        }

        public static Place zone(String zone) {
            return new Place(null, zone);
        }

        /**
         * Whether a terminal, in the zone it belongs to (null when none), is this place.
         */
        public boolean covers(UUID terminal, String terminalZone) {
            if (terminal == null) {
                return false;
            }
            return terminalId != null ? terminalId.equals(terminal)
                    : terminalZone != null && zone.equalsIgnoreCase(terminalZone);
        }
    }

    /**
     * One weight band, [min, max) in kilograms, a null max being the open top
     * band.
     */
    public record Slab(BigDecimal minWeightKg, BigDecimal maxWeightKg, RateBasis rateBasis, BigDecimal rate) {

        public Slab {
            if (minWeightKg == null || minWeightKg.signum() < 0) {
                throw new IllegalArgumentException("A weight band starts at zero or above");
            }
            if (maxWeightKg != null && maxWeightKg.compareTo(minWeightKg) <= 0) {
                throw new IllegalArgumentException("A weight band must end above where it begins");
            }
            if (rateBasis == null) {
                throw new IllegalArgumentException("A weight band needs a rate basis");
            }
            if (rate == null || rate.signum() < 0) {
                throw new IllegalArgumentException("A rate cannot be negative");
            }
        }

        public boolean covers(BigDecimal weightKg) {
            return weightKg.compareTo(minWeightKg) >= 0 && (maxWeightKg == null || weightKg.compareTo(maxWeightKg) < 0);
        }

        boolean overlaps(Slab other) {
            boolean startsAfterOtherEnds = other.maxWeightKg != null && minWeightKg.compareTo(other.maxWeightKg) >= 0;
            boolean endsBeforeOtherStarts = maxWeightKg != null && maxWeightKg.compareTo(other.minWeightKg) <= 0;
            return !startsAfterOtherEnds && !endsBeforeOtherStarts;
        }
    }

    /**
     * What a movement of some weight costs on the lane.
     *
     * @param quantity the quantity the rate applies to, in the basis' unit
     */
    public record Rating(RateBasis rateBasis, BigDecimal rate, BigDecimal quantity, BigDecimal amount) {
    }

    public ContractLane {
        if (laneCode == null || laneCode.isBlank()) {
            throw new IllegalArgumentException("A lane needs a code to be referred to by");
        }
        if (laneCode.strip().length() > 60) {
            throw new IllegalArgumentException("A lane code is at most 60 characters");
        }
        laneCode = laneCode.strip();
        if (origin == null || destination == null) {
            throw new IllegalArgumentException("A lane needs an origin and a destination");
        }
        if (freightMode != null && !List.of("FTL", "LTL").contains(freightMode)) {
            throw new IllegalArgumentException("A lane's freight mode is FTL or LTL, not " + freightMode);
        }
        loadType = normaliseLoadType(loadType);
        if (rateBasis == null) {
            throw new IllegalArgumentException("A lane needs a rate basis");
        }
        if (rate != null && rate.signum() < 0) {
            throw new IllegalArgumentException("A rate cannot be negative");
        }
        if (transitDays != null && transitDays <= 0) {
            throw new IllegalArgumentException("Transit days are a positive number");
        }
        slabs = slabs == null ? List.of() : slabs.stream()
                .sorted(Comparator.comparing(Slab::minWeightKg)).toList();
        for (int i = 1; i < slabs.size(); i++) {
            if (slabs.get(i).overlaps(slabs.get(i - 1))) {
                throw new IllegalArgumentException("The weight bands of lane " + laneCode + " overlap");
            }
        }
        if (rate == null && slabs.isEmpty()) {
            throw new IllegalArgumentException("Lane " + laneCode + " needs a rate or weight slabs");
        }
        consignors = consignors == null ? Set.of() : Set.copyOf(consignors);
        consignees = consignees == null ? Set.of() : Set.copyOf(consignees);
    }

    /** Upper case, trimmed; blank is none. */
    public static String normaliseLoadType(String value) {
        return value == null || value.isBlank() ? null : value.strip().toUpperCase(Locale.ROOT);
    }

    /** A copy of this lane under another contract version, with new identifiers handled by the caller. */
    public ContractLane copiedTo(UUID newId, UUID newContractId) {
        return new ContractLane(newId, newContractId, laneCode, origin, destination, freightMode, loadType,
                rateBasis, rate, transitDays, slabs, new LinkedHashSet<>(consignors), new LinkedHashSet<>(consignees));
    }

    /**
     * The price of moving {@code weightKg} on this lane: the covering slab's,
     * else the lane's own rate; empty when neither applies (the weight falls
     * in a gap between slabs and the lane has no rate of its own).
     */
    public Optional<Rating> rate(BigDecimal weightKg) {
        for (Slab slab : slabs) {
            if (slab.covers(weightKg)) {
                return Optional.of(rating(slab.rateBasis(), slab.rate(), weightKg));
            }
        }
        return rate == null ? Optional.empty() : Optional.of(rating(rateBasis, rate, weightKg));
    }

    private static Rating rating(RateBasis basis, BigDecimal rate, BigDecimal weightKg) {
        return switch (basis) {
            case PER_KG -> new Rating(basis, rate, weightKg, money(weightKg.multiply(rate)));
            case PER_TONNE -> {
                BigDecimal tonnes = weightKg.divide(BigDecimal.valueOf(1000), 3, RoundingMode.HALF_UP);
                yield new Rating(basis, rate, tonnes, money(tonnes.multiply(rate)));
            }
            case PER_TRIP -> new Rating(basis, rate, BigDecimal.ONE, money(rate));
        };
    }

    private static BigDecimal money(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }

    /** The lane's slabs as a mutable copy, for a caller that rebuilds the lane. */
    public List<Slab> slabList() {
        return new ArrayList<>(slabs);
    }
}
