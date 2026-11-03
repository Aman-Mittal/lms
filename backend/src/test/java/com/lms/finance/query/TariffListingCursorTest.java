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
package com.lms.finance.query;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import com.lms.shared.error.BusinessRuleViolationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TariffListingCursorTest {

    @Test
    @DisplayName("a cursor the listing issues is read back as the same position")
    void roundTrip() {
        TariffQueryService.Cursor issued = new TariffQueryService.Cursor(
                OffsetDateTime.of(2026, 11, 3, 9, 15, 30, 123_456_000, ZoneOffset.UTC),
                UUID.fromString("7d8c1d0e-6a37-4f7e-9d43-0f6a3f1b2c55"));

        assertThat(TariffQueryService.Cursor.parse(issued.encode())).isEqualTo(issued);
    }

    @Test
    @DisplayName("no cursor means the first page")
    void absent() {
        assertThat(TariffQueryService.Cursor.parse(null)).isNull();
        assertThat(TariffQueryService.Cursor.parse("  ")).isNull();
    }

    @Test
    @DisplayName("a cursor the listing never issued is refused by code")
    void foreign() {
        for (String forged : new String[] {"not-a-cursor", "2026-11-03T09:15:30Z_nope", "_"}) {
            assertThatThrownBy(() -> TariffQueryService.Cursor.parse(forged))
                    .isInstanceOfSatisfying(BusinessRuleViolationException.class,
                            e -> assertThat(e.code()).isEqualTo("invalid-cursor"));
        }
    }
}
