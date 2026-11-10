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
package com.lms.identity.command.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;

import com.lms.identity.command.domain.SeriesFormat.Parsed;
import com.lms.identity.command.domain.SeriesFormat.YearCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SeriesFormatTest {

    private static final ZoneId UTC = ZoneOffset.UTC;

    private static SeriesFormat format(String prefix, YearCode code, String separator, int length, int month) {
        return new SeriesFormat(prefix, code, separator, length, month, UTC);
    }

    @Test
    @DisplayName("the order default reads ORD-<calendar year>-<six digits>")
    void orderDefault() {
        SeriesFormat order = format("ORD", YearCode.START_YEAR, "-", 6, 1);
        assertThat(order.format(order.financialYearOf(LocalDate.of(2026, 11, 4)), 1))
                .isEqualTo("ORD-2026-000001");
        assertThat(order.format(2026, 1_000_000)).isEqualTo("ORD-2026-1000000");
    }

    @Test
    @DisplayName("an April financial year names the year it starts in and rolls over on the first of April")
    void aprilRollover() {
        SeriesFormat lr = format("LR", YearCode.START_YEAR, "/", 5, 4);
        assertThat(lr.financialYearOf(LocalDate.of(2027, 3, 31))).isEqualTo(2026);
        assertThat(lr.financialYearOf(LocalDate.of(2027, 4, 1))).isEqualTo(2027);
        assertThat(lr.format(2026, 7)).isEqualTo("LR/2026/00007");
    }

    @Test
    @DisplayName("a span code shows both calendar years of the financial year")
    void spanCode() {
        SeriesFormat gp = format("GP", YearCode.SPAN, "-", 4, 4);
        assertThat(gp.format(2026, 12)).isEqualTo("GP-2627-0012");
        assertThat(gp.format(2099, 1)).isEqualTo("GP-9900-0001");
        assertThat(gp.format(Integer.MAX_VALUE, 1)).as("no overflow at the edge of int").isEqualTo("GP-4748-0001");
        assertThat(gp.format(Integer.MIN_VALUE, 1)).isEqualTo("GP-5253-0001");
        assertThat(gp.parse("GP-2627-0012")).contains(new Parsed(2026, 12));
        // A span whose second half is not the next year is not this series' number.
        assertThat(gp.parse("GP-2628-0012")).isEmpty();
    }

    @Test
    @DisplayName("a series without a year code never restarts and shows no year")
    void continuous() {
        SeriesFormat inv = format("INV", YearCode.NONE, "", 6, 4);
        assertThat(inv.financialYearOf(LocalDate.of(2027, 4, 1))).isEqualTo(SeriesFormat.CONTINUOUS);
        assertThat(inv.format(SeriesFormat.CONTINUOUS, 3)).isEqualTo("INV000003");
        assertThat(inv.parse("INV000003")).contains(new Parsed(0, 3));
    }

    @Test
    @DisplayName("the financial year is decided in the series' time zone")
    void zoneDecidesTheDay() {
        SeriesFormat ist = new SeriesFormat("LR", YearCode.START_YEAR, "-", 5, 4, ZoneId.of("Asia/Kolkata"));
        // 20:00 UTC on 31 March is already 1 April in India.
        assertThat(ist.financialYear(Instant.parse("2027-03-31T20:00:00Z"))).isEqualTo(2027);
        assertThat(format("LR", YearCode.START_YEAR, "-", 5, 4)
                .financialYear(Instant.parse("2027-03-31T20:00:00Z"))).isEqualTo(2026);
    }

    @Test
    @DisplayName("only a number the series could have produced parses back")
    void parseIsExact() {
        SeriesFormat lr = format("LR", YearCode.START_YEAR, "-", 5, 4);
        assertThat(lr.parse("LR-2026-00042")).contains(new Parsed(2026, 42));
        assertThat(lr.parse("LR-2026-123456")).contains(new Parsed(2026, 123_456));
        assertThat(lr.parse("LR-2026-0042")).isEmpty();
        assertThat(lr.parse("LR-2026-000042")).isEmpty();
        assertThat(lr.parse("LR-2026-00000")).isEmpty();
        assertThat(lr.parse("LR/2026/00042")).isEmpty();
        assertThat(lr.parse("DKT-2026-00042")).isEmpty();
        assertThat(lr.parse("LR-20X6-00042")).isEmpty();
        assertThat(lr.parse("LR-2026-")).isEmpty();
        assertThat(lr.parse(null)).isEmpty();
    }

    @Test
    @DisplayName("formats that could not be stored are refused as bad input")
    void refusals() {
        assertThatThrownBy(() -> format("lr", YearCode.NONE, "-", 5, 4))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> format("LR", YearCode.NONE, "#", 5, 4))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> format("LR", YearCode.NONE, "-", 0, 4))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> format("LR", YearCode.NONE, "-", 5, 13))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SeriesFormat.zoneOf("Mars/Olympus"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> format("LR", YearCode.NONE, "-", 5, 4).format(0, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
