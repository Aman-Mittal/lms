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

package com.lms.order.command.intake;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.lms.shared.error.BusinessRuleViolationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IntakeParsingTest {

    private static SheetRow header(String... cells) {
        return new SheetRow(1, List.of(cells));
    }

    @Test
    @DisplayName("the default headers match ignoring case, blanks and punctuation")
    void defaultMapping() {
        IntakeColumns columns = IntakeColumns.of(header("Order Ref", "ORIGIN", "Destination", "Quantity", "Weight (kg)"),
                Map.of());
        SheetRow row = new SheetRow(2, List.of("R1", "MUM", "DEL", "4", "800"));
        assertThat(columns.value(row, IntakeField.ORDER_REF)).isEqualTo("R1");
        assertThat(columns.value(row, IntakeField.QUANTITY)).isEqualTo("4");
        assertThat(columns.value(row, IntakeField.LOAD_TYPE)).as("an optional column the file lacks").isEmpty();
    }

    @Test
    @DisplayName("a customer's mapping names its own headers, a field it leaves out keeping the default")
    void customerMapping() {
        IntakeColumns columns = IntakeColumns.of(header("Ref no", "Pick-up point", "destination", "Qty"),
                Map.of("orderRef", "Ref no", "origin", "pick up point", "quantity", "Qty", "uom", " "));
        SheetRow row = new SheetRow(2, List.of("R9", "MUM", "DEL", "2"));
        assertThat(columns.value(row, IntakeField.ORIGIN)).isEqualTo("MUM");
        assertThat(columns.value(row, IntakeField.DESTINATION)).isEqualTo("DEL");
        assertThat(columns.value(row, IntakeField.QUANTITY)).isEqualTo("2");
    }

    @Test
    @DisplayName("every required column that is not found is named in one refusal")
    void missingColumns() {
        assertThatThrownBy(() -> IntakeColumns.of(header("order_ref", "origin"), Map.of()))
                .isInstanceOfSatisfying(BusinessRuleViolationException.class, e -> {
                    assertThat(e.code()).isEqualTo("intake-columns-missing");
                    assertThat(e.getMessage()).contains("destination", "quantity").doesNotContain("orderRef");
                });
    }

    @Test
    @DisplayName("dates are read as ISO text, day-first text or an Excel serial number, and refused otherwise")
    void dates() {
        List<String> errors = new ArrayList<>();
        assertThat(IntakeFileService.dateOf("2026-11-20", "pickup date", errors))
                .isEqualTo(Instant.parse("2026-11-20T00:00:00Z"));
        assertThat(IntakeFileService.dateOf("20/11/2026", "pickup date", errors))
                .isEqualTo(Instant.parse("2026-11-20T00:00:00Z"));
        assertThat(IntakeFileService.dateOf("46346", "pickup date", errors))
                .as("the serial Excel keeps for 2026-11-20").isEqualTo(Instant.parse("2026-11-20T00:00:00Z"));
        assertThat(IntakeFileService.dateOf("", "pickup date", errors)).isNull();
        assertThat(errors).isEmpty();

        assertThat(IntakeFileService.dateOf("next week", "pickup date", errors)).isNull();
        assertThat(errors).singleElement().asString().contains("pickup date", "next week");
    }
}
