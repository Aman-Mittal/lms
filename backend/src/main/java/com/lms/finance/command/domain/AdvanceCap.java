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

package com.lms.finance.command.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * How much of a trip's freight may be advanced to its vendor: a percentage of
 * the freight, across every live advance on the trip rather than per advance.
 * Rounded down to the paisa, so the cap is never exceeded by rounding.
 */
public final class AdvanceCap {

    private AdvanceCap() {
    }

    /** The most that may be advanced in all on a trip whose freight is {@code freight}. */
    public static BigDecimal maximum(BigDecimal freight, BigDecimal maxPercent) {
        return freight.multiply(maxPercent).divide(BigDecimal.valueOf(100), 2, RoundingMode.DOWN);
    }

    /** What may still be requested, given what live advances already hold; never negative. */
    public static BigDecimal remaining(BigDecimal freight, BigDecimal maxPercent, BigDecimal alreadyAdvanced) {
        return maximum(freight, maxPercent).subtract(alreadyAdvanced).max(BigDecimal.ZERO);
    }

    /** Whether {@code requested} fits under the cap beside {@code alreadyAdvanced}. */
    public static boolean fits(BigDecimal freight, BigDecimal maxPercent, BigDecimal alreadyAdvanced,
                               BigDecimal requested) {
        return requested.compareTo(remaining(freight, maxPercent, alreadyAdvanced)) <= 0;
    }
}
