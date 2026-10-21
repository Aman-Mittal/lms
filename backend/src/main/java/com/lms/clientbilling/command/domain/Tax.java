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

/**
 * The one rounding rule for tax on client invoices and credit notes.
 *
 * <p>Tax is computed <em>per line</em>, on the line's taxable amount, and
 * rounded half-up to two decimals; the document's tax is the sum of its lines'.
 * Rounding per line rather than once on the total is what lets a line be
 * credited on its own and its tax reversed exactly, without a residual cent
 * landing on whichever line is credited last.
 */
public final class Tax {

    /** Money is held to two decimals. */
    public static final int SCALE = 2;

    /** Half-up: 0.005 rounds to 0.01, as a client checking by hand expects. */
    public static final RoundingMode ROUNDING = RoundingMode.HALF_UP;

    private Tax() {
    }

    /**
     * Tax on one line.
     *
     * @param taxable the line's taxable amount
     * @param ratePct the rate as a percentage, e.g. {@code 18} for 18 %
     * @return {@code taxable x ratePct / 100}, half-up to two decimals
     */
    public static BigDecimal on(BigDecimal taxable, BigDecimal ratePct) {
        if (taxable == null || ratePct == null || ratePct.signum() == 0) {
            return BigDecimal.ZERO.setScale(SCALE);
        }
        // movePointLeft is exact, so the only rounding is the one stated.
        return taxable.multiply(ratePct).movePointLeft(2).setScale(SCALE, ROUNDING);
    }

    /** Rounds an amount of money with the same rule. */
    public static BigDecimal money(BigDecimal amount) {
        return amount.setScale(SCALE, ROUNDING);
    }
}
