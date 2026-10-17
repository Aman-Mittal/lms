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
package com.lms.multimodal.command.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.lms.shared.error.BusinessRuleViolationException;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.relational.core.mapping.Table;

/**
 * A shipment's route as an ordered list of legs (vision document 3.4).
 *
 * <p>The header only. Legs are their own aggregates -- see
 * {@link ItineraryLeg} -- and the rules about how legs fit together are
 * checked here, once, when the itinerary is planned.
 */
@Table("itinerary")
public record Itinerary(
        @Id UUID id,
        UUID tenantId,
        String itineraryNo,
        String shipmentRef,
        String currency,
        String createdBy,
        @Version Long version,
        Instant createdAt,
        Instant updatedAt) {

    /** More than this is a routing problem, not an itinerary. */
    public static final int MAX_LEGS = 10;

    /**
     * Plans an itinerary, checking that its legs make a route.
     *
     * <p>Contiguous: each leg starts at the terminal where the previous one
     * ended, or goods would have to teleport between them. Feasible: each leg
     * is planned to start no earlier than its predecessor's planned arrival
     * plus its own transfer dwell -- a plan that misses its own connections
     * is not a plan.
     */
    public static Itinerary plan(UUID id, UUID tenantId, String itineraryNo, String shipmentRef,
                                 String currency, List<ItineraryLeg> legs, String createdBy,
                                 Instant at) {
        if (shipmentRef == null || shipmentRef.isBlank()) {
            throw new BusinessRuleViolationException("itinerary-shipment-required",
                    "An itinerary needs the shipment reference it moves");
        }
        if (legs == null || legs.isEmpty()) {
            throw new BusinessRuleViolationException("itinerary-needs-legs",
                    "An itinerary needs at least one leg");
        }
        if (legs.size() > MAX_LEGS) {
            throw new BusinessRuleViolationException("itinerary-too-many-legs",
                    "An itinerary may have at most " + MAX_LEGS + " legs");
        }
        for (int i = 1; i < legs.size(); i++) {
            ItineraryLeg previous = legs.get(i - 1);
            ItineraryLeg next = legs.get(i);
            if (!previous.toTerminalId().equals(next.fromTerminalId())) {
                throw new BusinessRuleViolationException("itinerary-not-contiguous",
                        "Leg " + next.seq() + " starts somewhere other than where leg "
                                + previous.seq() + " ends");
            }
            Instant earliest = previous.plannedArrivalAt()
                    .plus(Duration.ofMinutes(next.transferDwellMinutes()));
            if (next.plannedDepartureAt().isBefore(earliest)) {
                throw new BusinessRuleViolationException("itinerary-plan-misses-connection",
                        "Leg " + next.seq() + " is planned to start at " + next.plannedDepartureAt()
                                + ", before leg " + previous.seq() + " is planned to arrive plus "
                                + next.transferDwellMinutes() + " minutes of transfer, "
                                + earliest);
            }
        }
        String cur = currency == null || currency.isBlank() ? "INR" : currency.strip();
        return new Itinerary(id, tenantId, itineraryNo, shipmentRef.strip(), cur, createdBy,
                null, at, at);
    }
}
