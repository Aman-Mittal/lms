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
import java.util.Map;

import com.lms.shared.error.BusinessRuleViolationException;

/**
 * Prices one captured charge against one contract version.
 *
 * <p>Pure, so it can be tested without a database: the inputs are the charge,
 * the contract and that contract's value-added service rates; the output is
 * the line an invoice shows.
 */
public final class ChargePricing {

    /** What a line says and costs. */
    public record Priced(String narrative, String unit, BigDecimal rate, BigDecimal amount) {
    }

    private ChargePricing() {
    }

    /**
     * @param serviceRates the contract's value-added service rates, keyed by
     *                     normalised service code
     */
    public static Priced price(ClientCharge charge, ClientContract contract,
                               Map<String, ContractServiceRate> serviceRates) {
        BigDecimal quantity = charge.quantity();
        String reference = charge.reference() == null || charge.reference().isBlank()
                ? "" : " (" + charge.reference() + ")";

        if (charge.serviceType() == ServiceType.VALUE_ADDED) {
            ContractServiceRate rate = serviceRates.get(charge.serviceCode());
            if (rate == null) {
                throw new BusinessRuleViolationException("client-service-unpriced",
                        "Contract " + contract.code() + " has no rate for value-added service "
                                + charge.serviceCode() + ", recorded on " + charge.occurredOn());
            }
            return new Priced("Value-added service " + charge.serviceCode() + reference,
                    rate.unit(), rate.rate(), money(quantity.multiply(rate.rate())));
        }

        BigDecimal rate = contract.rateFor(charge.serviceType());
        BigDecimal amount = money(quantity.multiply(rate));
        String unit = charge.serviceType().unit();

        return switch (charge.serviceType()) {
            case TRANSPORT -> {
                // A floor per movement, as with a carrier's minimum charge: a
                // small consignment still occupies a slot on a lorry.
                BigDecimal floor = money(contract.transportMinimumCharge());
                if (amount.compareTo(floor) < 0) {
                    yield new Priced("Transport" + reference + ", minimum charge applied",
                            unit, rate, floor);
                }
                yield new Priced("Transport" + reference, unit, rate, amount);
            }
            case STORAGE -> new Priced("Storage" + reference, unit, rate, amount);
            case HANDLING_IN -> new Priced("Handling in" + reference, unit, rate, amount);
            case HANDLING_OUT -> new Priced("Handling out" + reference, unit, rate, amount);
            case VALUE_ADDED -> throw new IllegalStateException("handled above");
        };
    }

    private static BigDecimal money(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }
}
