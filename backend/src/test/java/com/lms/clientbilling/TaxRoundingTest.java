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
package com.lms.clientbilling;

import java.math.BigDecimal;

import com.lms.clientbilling.command.domain.StoragePallets;
import com.lms.clientbilling.command.domain.Tax;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The rounding rules, pinned: tax is half-up to two decimals on each line,
 * and ledger storage rounds a part pallet up to a whole position.
 */
class TaxRoundingTest {

    @ParameterizedTest(name = "{1}% of {0} is {2}")
    @CsvSource({
            // Exact: no rounding to speak of.
            "1000.00, 18,     180.00",
            "6050.00, 18,     1089.00",
            // Half-up at the third decimal, not banker's rounding.
            "0.25,    18,     0.05",   // 0.045 -> 0.05
            "0.35,    10,     0.04",   // 0.035 -> 0.04 (half-even would give 0.04 too)
            "0.45,    10,     0.05",   // 0.045 -> 0.05 (half-even would give 0.04)
            "10.05,   5,      0.50",   // 0.5025 -> 0.50
            "10.10,   5,      0.51",   // 0.505 -> 0.51
            // Fractional rates.
            "100.00,  2.5,    2.50",
            "33.33,   12.345, 4.11",   // 4.11458... -> 4.11
            "33.37,   12.345, 4.12",   // 4.11952... -> 4.12
            // No tax.
            "123.45,  0,      0.00"
    })
    void taxIsHalfUpToTwoDecimals(String taxable, String rate, String expected) {
        BigDecimal tax = Tax.on(new BigDecimal(taxable), new BigDecimal(rate));
        assertThat(tax).isEqualByComparingTo(expected);
        assertThat(tax.scale()).isEqualTo(2);
    }

    @Test
    void taxIsRoundedPerLineNotOnTheTotal() {
        // Three lines of 0.25 at 18 %: 0.045 each rounds to 0.05, so the
        // invoice's tax is 0.15 -- not 18 % of 0.75 = 0.135 -> 0.14. The
        // per-line figure is the one a client can check line by line.
        BigDecimal perLine = Tax.on(new BigDecimal("0.25"), new BigDecimal("18"));
        BigDecimal summed = perLine.multiply(BigDecimal.valueOf(3));
        assertThat(summed).isEqualByComparingTo("0.15");
        assertThat(Tax.on(new BigDecimal("0.75"), new BigDecimal("18")))
                .isEqualByComparingTo("0.14");
    }

    @Test
    void nullOrZeroRateIsNoTax() {
        assertThat(Tax.on(new BigDecimal("10"), null)).isEqualByComparingTo("0");
        assertThat(Tax.on(null, new BigDecimal("18"))).isEqualByComparingTo("0");
    }

    @ParameterizedTest(name = "{0} units at {1} per pallet is {2} pallets")
    @CsvSource({
            "0,   10, 0",
            "1,   10, 1",
            "10,  10, 1",
            "11,  10, 2",
            "95,  10, 10",
            "50,  10, 5",
            "7,   1,  7"
    })
    void aPartPalletOccupiesAWholePosition(long units, int perPallet, long pallets) {
        assertThat(StoragePallets.of(units, perPallet)).isEqualTo(pallets);
    }

    @Test
    void palletsNeedASensibleDefinition() {
        assertThatThrownBy(() -> StoragePallets.of(5, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> StoragePallets.of(-1, 10))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
