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
import java.util.List;

/**
 * The arithmetic behind one bill, before it becomes rows.
 *
 * <p>Produced by the rating engine and consumed by the billing service. It
 * exists so that pricing can be a pure function of its inputs: given a rate
 * card, a weight, a dwell and a drop count, the sheet is fully determined, and
 * a test can check the whole of 3.9.1 without a database.
 *
 * <p>{@link #total()} is the sum of the lines, not an independently computed
 * figure. A total that did not equal its own breakdown would be the single
 * most damaging bug this module could have, because it would be argued over
 * rather than noticed.
 */
public record CostSheet(
        String currency,
        BigDecimal chargeableWeightKg,
        BigDecimal detentionHours,
        int dropCount,
        BigDecimal baseFreight,
        BigDecimal detentionAmount,
        BigDecimal multiDropAmount,
        BigDecimal fuelSurchargeAmount,
        List<Charge> charges) {

    /** One line of the breakdown, before it is given an identity and a bill. */
    public record Charge(
            FreightBillLine.ChargeType type,
            String narrative,
            BigDecimal quantity,
            String unit,
            BigDecimal rate,
            BigDecimal amount) {
    }

    public BigDecimal total() {
        return charges.stream()
                .map(Charge::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
