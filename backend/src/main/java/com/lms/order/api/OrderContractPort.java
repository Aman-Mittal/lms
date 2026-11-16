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
package com.lms.order.api;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Implemented by client billing, so that an order can name the client
 * contract it is raised under and the lane it travels on.
 *
 * <p>The same direction as {@link OrderCancellationHook}: client billing
 * depends on this module's {@code api}, never the reverse, so the order module
 * publishes the question and the contract's owner answers it. Synchronous,
 * because a refusal has to stop the order being raised or validated; an event
 * afterwards could only apologise.
 *
 * <p>A customer with no contract in force on the order's date is not an
 * error: {@link #resolve} answers empty and the order is accepted as it always
 * was, priced by the per-kilo rate when it is eventually charged.
 */
public interface OrderContractPort {

    /**
     * The contract version in force on {@code request.orderDate()} for the
     * customer, and the lane the order travels on.
     *
     * @return empty when the customer has no contract in force on the date
     * @throws com.lms.shared.error.BusinessRuleViolationException one of
     *         {@code order-outside-contract-lanes}, {@code order-load-type-not-allowed}
     *         and {@code order-consignee-not-allowed}
     */
    Optional<Resolution> resolve(Request request);

    /**
     * The rate a customer's contract lanes would give for freight between two
     * terminals, for the enquiry desk's cost discovery. Never refuses: a
     * customer with no contract in force, a load type the contract does not
     * cover, or no lane that serves the journey is simply an empty answer, so
     * the person quoting prices it by hand.
     *
     * <p>Defaults to empty, so a slice of the application with no client
     * billing quotes without suggestions.
     */
    default Optional<RateSuggestion> suggestRate(RateQuery query) {
        return Optional.empty();
    }

    /**
     * What is known of an enquiry line when a rate is wanted.
     *
     * @param onDate   the day the contract is read for: the wanted pickup day, else today
     * @param loadType the load or vehicle type, or null
     * @param weightKg the weight, or null when unknown (then only a flat lane rate can answer)
     */
    record RateQuery(UUID customerPartnerId, LocalDate onDate, UUID originTerminalId, UUID destinationTerminalId,
                     String loadType, BigDecimal weightKg) {
    }

    /**
     * The lane's price.
     *
     * @param rateBasis PER_KG, PER_TONNE or PER_TRIP
     * @param amount    the price for the weight asked about; null when no weight was given
     */
    record RateSuggestion(UUID contractId, String contractCode, String laneCode, String rateBasis,
                          BigDecimal rate, BigDecimal amount) {
    }

    /**
     * What is known of the order when its contract is wanted.
     *
     * @param orderDate     the day the order is judged by: its requested pickup
     *                      day, else today, so a back-dated order finds the
     *                      version in force then
     * @param loadType      the load or vehicle type the order is booked for, or null
     * @param requestedLane a lane the person chose, or null to have one found
     * @param legs          where each line's freight is delivered and to whom;
     *                      empty while the order has no lines, when only the
     *                      header can be judged and no lane is assigned
     */
    record Request(UUID customerPartnerId, LocalDate orderDate, UUID originTerminalId, String freightMode,
                   String loadType, UUID requestedLane, List<Leg> legs) {

        public Request {
            legs = legs == null ? List.of() : List.copyOf(legs);
        }
    }

    /** One line's delivery: the destination terminal and the consignee, when stated. */
    record Leg(UUID destinationTerminalId, UUID consigneePartnerId) {
    }

    /**
     * The answer.
     *
     * @param contractId       the contract version in force
     * @param contractFamilyId the contract as a person means it, shared by its versions
     * @param laneId           the lane the order travels on; null for a direct order or before lines exist
     * @param transitDays      the lane's promised days; null when the lane names none or there is no lane
     */
    record Resolution(UUID contractId, UUID contractFamilyId, String contractCode, UUID laneId, String laneCode,
                      Integer transitDays) {
    }
}
