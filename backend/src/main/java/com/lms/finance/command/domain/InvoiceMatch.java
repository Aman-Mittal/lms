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
 * Whether a vendor's invoice agrees with what the platform approved for the
 * bills it covers, by the rule the freight bill's own match uses: the
 * difference may be up to the greater of a percentage of the approved amount
 * and an absolute floor, in either direction.
 */
public record InvoiceMatch(BigDecimal approved, BigDecimal claimed, BigDecimal variance, BigDecimal allowance,
                           boolean within) {

    public static InvoiceMatch of(BigDecimal approved, BigDecimal claimed, BigDecimal tolerancePct,
                                  BigDecimal toleranceAbsolute) {
        BigDecimal variance = claimed.subtract(approved).setScale(2, RoundingMode.HALF_UP);
        BigDecimal proportional = approved.multiply(tolerancePct).divide(BigDecimal.valueOf(100), 2,
                RoundingMode.HALF_UP);
        BigDecimal allowance = proportional.max(toleranceAbsolute);
        return new InvoiceMatch(approved, claimed, variance, allowance, variance.abs().compareTo(allowance) <= 0);
    }
}
