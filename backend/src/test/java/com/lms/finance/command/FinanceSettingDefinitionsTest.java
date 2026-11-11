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
package com.lms.finance.command;

import java.math.BigDecimal;
import java.util.List;

import com.lms.settings.api.ScopeType;
import com.lms.settings.api.SettingDefinition;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FinanceSettingDefinitionsTest {

    @Test
    @DisplayName("the match tolerance defaults to the deployment's properties and may be set per unit and vendor")
    void defaultsAndScopes() {
        List<SettingDefinition> definitions =
                new FinanceSettingDefinitions(new BigDecimal("2"), new BigDecimal("100")).definitions();

        assertThat(definitions).extracting(SettingDefinition::key)
                .containsExactly("finance.match.tolerancePercent", "finance.match.toleranceAbsolute");
        assertThat(definitions).extracting(SettingDefinition::defaultValue)
                .containsExactly(new BigDecimal("2"), new BigDecimal("100"));
        assertThat(definitions).allSatisfy(d -> assertThat(d.scopes())
                .containsExactly(ScopeType.ORG_DEFAULT, ScopeType.ORG_UNIT, ScopeType.VENDOR, ScopeType.COMBINATION));
    }
}
