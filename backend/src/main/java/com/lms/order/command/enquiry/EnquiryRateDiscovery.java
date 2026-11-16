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

package com.lms.order.command.enquiry;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import com.lms.order.api.OrderContractPort;
import org.springframework.stereotype.Component;

/**
 * Cost discovery: what the customer's contract lanes would charge for a line.
 * The contract's owner answers through {@link OrderContractPort}; with no
 * contract, no lane, or no client billing at all the answer is empty and the
 * person quoting prices the line by hand.
 */
@Component
class EnquiryRateDiscovery {

    private static final java.util.Set<String> KILO_UNITS = java.util.Set.of("KG", "KGS", "KILOGRAM", "KILOGRAMS");

    private final Optional<OrderContractPort> contracts;

    EnquiryRateDiscovery(Optional<OrderContractPort> contracts) {
        this.contracts = contracts;
    }

    /** The line's weight: the one stated, else the quantity when it is counted in kilograms. */
    static java.math.BigDecimal weightOf(java.math.BigDecimal weightKg, java.math.BigDecimal quantity, String uom) {
        if (weightKg != null) {
            return weightKg;
        }
        return uom != null && KILO_UNITS.contains(uom.strip().toUpperCase(Locale.ROOT)) ? quantity : null;
    }

    Optional<OrderContractPort.RateSuggestion> suggest(UUID customerId, EnquiryStore.Line line) {
        LocalDate day = line.wantedPickupOn() != null ? line.wantedPickupOn()
                : LocalDate.now(ZoneOffset.UTC);
        return contracts.flatMap(port -> port.suggestRate(new OrderContractPort.RateQuery(customerId, day,
                line.originTerminalId(), line.destinationTerminalId(), line.loadType(),
                weightOf(line.weightKg(), line.quantity(), line.uom()))));
    }
}
