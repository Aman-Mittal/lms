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
package com.lms.clientbilling.command;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.lms.clientbilling.command.domain.ClientContract;

/**
 * The completeness rules a draft must satisfy before verification can be
 * asked for -- and again when it is approved.
 *
 * <p>Pure: the facts that live elsewhere (is the customer trading, when did
 * the version in force start) are handed in, so every rule is a unit test.
 * Which of the rules apply is a tenant's choice
 * ({@code clientbilling.contract.requiredChecklist}); a refusal names every
 * failing rule by its code, not the first.
 */
public final class ContractChecklist {

    /** The customer is not suspended or blacklisted. */
    public static final String CUSTOMER_TRADING = "CUSTOMER_TRADING";
    /** Start and end dates make sense, and the start follows the version in force. */
    public static final String EFFECTIVE_DATES = "EFFECTIVE_DATES";
    /** At least a transport rate per kilo or a minimum charge is priced. */
    public static final String TRANSPORT_RATE = "TRANSPORT_RATE";
    /** The tax rates are percentages. */
    public static final String TAX_RATES = "TAX_RATES";
    /** The contract belongs to an organisational unit. */
    public static final String ORG_UNIT = "ORG_UNIT";

    /** Every rule, in the order a refusal lists them. */
    public static final List<String> ITEMS =
            List.of(CUSTOMER_TRADING, EFFECTIVE_DATES, TRANSPORT_RATE, TAX_RATES, ORG_UNIT);

    private ContractChecklist() {
    }

    /**
     * What the rules need to know that the contract does not say.
     *
     * @param customerTrading whether the client may be dealt with
     * @param today           the day to judge an end date by
     * @param incumbentFrom   when the version in force began; null when there is none
     */
    public record Facts(boolean customerTrading, LocalDate today, LocalDate incumbentFrom) {
    }

    /**
     * The required rules the contract fails, each with why, in {@link #ITEMS}
     * order. Empty when the contract may go for verification.
     *
     * @param required the codes of the rules that apply; others are not checked
     */
    public static Map<String, String> failures(ClientContract contract, Facts facts, Collection<String> required) {
        Map<String, String> failed = new LinkedHashMap<>();
        for (String item : ITEMS) {
            if (!required.contains(item)) {
                continue;
            }
            String why = check(item, contract, facts);
            if (why != null) {
                failed.put(item, why);
            }
        }
        return failed;
    }

    private static String check(String item, ClientContract c, Facts facts) {
        return switch (item) {
            case CUSTOMER_TRADING -> facts.customerTrading() ? null
                    : "The client is not a customer in good standing: it is suspended, blacklisted or not a customer";
            case EFFECTIVE_DATES -> dates(c, facts);
            case TRANSPORT_RATE -> positive(c.transportRatePerKg()) || positive(c.transportMinimumCharge()) ? null
                    : "Price transport: a rate per kilo or a minimum charge";
            case TAX_RATES -> percentage(c.transportTaxRatePct()) && percentage(c.warehouseTaxRatePct()) ? null
                    : "Tax rates must be percentages between 0 and 100";
            case ORG_UNIT -> c.orgUnitId() != null ? null : "Name the organisational unit the contract belongs to";
            default -> throw new IllegalArgumentException("Unknown checklist item " + item);
        };
    }

    private static String dates(ClientContract c, Facts facts) {
        if (c.effectiveFrom() == null) {
            return "Say when the contract starts";
        }
        if (c.validTill() != null && c.validTill().isBefore(c.effectiveFrom())) {
            return "The contract ends on " + c.validTill() + ", before it starts on " + c.effectiveFrom();
        }
        if (c.validTill() != null && c.validTill().isBefore(facts.today())) {
            return "The contract's end date " + c.validTill() + " has already passed";
        }
        if (facts.incumbentFrom() != null && !c.effectiveFrom().isAfter(facts.incumbentFrom())) {
            return "The version in force has applied since " + facts.incumbentFrom()
                    + "; a successor must start after that";
        }
        return null;
    }

    private static boolean positive(BigDecimal value) {
        return value != null && value.signum() > 0;
    }

    private static boolean percentage(BigDecimal value) {
        return value != null && value.signum() >= 0 && value.compareTo(BigDecimal.valueOf(100)) <= 0;
    }
}
