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
package com.lms.execution.command;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

import com.lms.settings.api.ScopeType;
import com.lms.settings.api.SettingCategory;
import com.lms.settings.api.SettingDefinition;
import com.lms.settings.api.SettingSource;
import com.lms.settings.command.SettingValueRules;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExecutionSettingDefinitionsTest {

    private final Map<String, SettingDefinition> byKey = new ExecutionSettingDefinitions(new BigDecimal("5"))
            .definitions().stream().collect(java.util.stream.Collectors.toMap(SettingDefinition::key, d -> d));

    private SettingDefinition documents() {
        return byKey.get(ExecutionSettingDefinitions.REQUIRED_DOCUMENTS.name());
    }

    @Test
    @DisplayName("by default a dispatch needs a consignment note and an e-way bill, as it always did")
    void defaults() {
        @SuppressWarnings("unchecked")
        Map<String, Object> checklist = (Map<String, Object>) SettingValueRules.coerce(documents(),
                documents().defaultValue());
        assertThat(ExecutionSettingDefinitions.required(checklist)).containsExactly("CONSIGNMENT_NOTE", "EWAY_BILL");
        assertThat(byKey.get(ExecutionSettingDefinitions.WEIGHT_TOLERANCE.name()).defaultValue())
                .isEqualTo(new BigDecimal("5"));
        assertThat(byKey.get(ExecutionSettingDefinitions.AUTO_ARRIVAL.name()).defaultValue()).isEqualTo(true);
        assertThat(byKey.get(ExecutionSettingDefinitions.AUTO_DEPARTURE.name()).defaultValue()).isEqualTo(true);
        assertThat(byKey.get(ExecutionSettingDefinitions.AUTO_COMPLETION.name()).defaultValue()).isEqualTo(false);
    }

    @Test
    @DisplayName("the required documents are a dated compliance rule, kept per unit, vehicle and customer")
    void documentsAreCompliance() {
        assertThat(documents().category()).isEqualTo(SettingCategory.COMPLIANCE);
        assertThat(documents().effectiveDated()).isTrue();
        assertThat(documents().scopes()).containsExactly(ScopeType.ORG_DEFAULT, ScopeType.ORG_UNIT,
                ScopeType.VEHICLE, ScopeType.CUSTOMER, ScopeType.COMBINATION);
    }

    @Test
    @DisplayName("only known document types may be required; one set false is not required")
    void knownTypesOnly() {
        assertThatThrownBy(() -> SettingValueRules.coerce(documents(), Map.of("BOARDING_PASS", true)))
                .isInstanceOf(SettingValueRules.InvalidValue.class).hasMessageContaining("BOARDING_PASS");
        assertThatThrownBy(() -> SettingValueRules.coerce(documents(), Map.of("EWAY_BILL", "yes")))
                .isInstanceOf(SettingValueRules.InvalidValue.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> checklist = (Map<String, Object>) SettingValueRules.coerce(documents(),
                Map.of("EWAY_BILL", false, "PACKING_LIST", true, "CONSIGNMENT_NOTE", true));
        assertThat(ExecutionSettingDefinitions.required(checklist)).containsExactly("CONSIGNMENT_NOTE",
                "PACKING_LIST");
    }

    @Test
    @DisplayName("the weight tolerance is bounded")
    void toleranceBounds() {
        SettingDefinition tolerance = byKey.get(ExecutionSettingDefinitions.WEIGHT_TOLERANCE.name());
        assertThatThrownBy(() -> SettingValueRules.coerce(tolerance, new BigDecimal("50.01")))
                .isInstanceOf(SettingValueRules.InvalidValue.class);
        assertThatThrownBy(() -> SettingValueRules.coerce(tolerance, -1))
                .isInstanceOf(SettingValueRules.InvalidValue.class);
        assertThat(SettingValueRules.coerce(tolerance, new BigDecimal("0.5"))).isEqualTo(new BigDecimal("0.5"));
    }

    @Test
    @DisplayName("a refusal names where the rule came from: the system default or a profile, and its date")
    void describesTheSource() {
        assertThat(TripSettings.describe(SettingSource.SYSTEM_DEFAULT)).isEqualTo("the system default");
        assertThat(TripSettings.describe(new SettingSource(ScopeType.CUSTOMER, UUID.randomUUID(), UUID.randomUUID(),
                "Acme terms", LocalDate.of(2027, 4, 1))))
                .isEqualTo("customer profile \"Acme terms\" from 2027-04-01");
        assertThat(TripSettings.describe(new SettingSource(ScopeType.ORG_UNIT, UUID.randomUUID(), UUID.randomUUID(),
                "North", null))).isEqualTo("org unit profile \"North\"");
        assertThat(TripSettings.describe(new SettingSource(ScopeType.COMBINATION, UUID.randomUUID(), UUID.randomUUID(),
                "North LTL", null, "Zone North + Mode LTL")))
                .isEqualTo("combination profile \"North LTL\" (Zone North + Mode LTL)");
    }
}
