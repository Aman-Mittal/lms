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
package com.lms.clientbilling.command;

import java.util.List;
import java.util.Map;

import com.lms.settings.api.ScopeType;
import com.lms.settings.api.SettingCategory;
import com.lms.settings.api.SettingContributor;
import com.lms.settings.api.SettingDefinition;
import com.lms.settings.api.SettingKey;
import com.lms.settings.api.SettingType;
import org.springframework.stereotype.Component;

/**
 * The tenant-tunable values client billing owns.
 *
 * <p>{@code clientbilling.contract.requiredChecklist} says which completeness
 * rules a contract draft must pass before verification can be asked for (see
 * {@link ContractChecklist}). It is a compliance rule -- what an organisation
 * insists on before a contract binds it changes by decision, and a contract is
 * judged by the rule in force the day it is submitted -- so every value is
 * effective-dated. The system default is every rule.
 */
@Component
public class ClientBillingSettingDefinitions implements SettingContributor {

    public static final SettingKey<Map<String, Object>> REQUIRED_CHECKLIST =
            SettingKey.json("clientbilling.contract.requiredChecklist");

    @Override
    public List<SettingDefinition> definitions() {
        SettingDefinition.Builder checklist = SettingDefinition.of(REQUIRED_CHECKLIST,
                        "Contract checklist",
                        "The completeness rules a client contract draft must pass before verification can be"
                                + " asked for, as {\"TRANSPORT_RATE\": true, ...}; a rule set false or left out is"
                                + " not checked. Dated: a draft is judged by the rule in force on the day.")
                .defaultValue(Map.of(ContractChecklist.CUSTOMER_TRADING, true, ContractChecklist.EFFECTIVE_DATES, true,
                        ContractChecklist.TRANSPORT_RATE, true, ContractChecklist.TAX_RATES, true,
                        ContractChecklist.ORG_UNIT, true))
                .category(SettingCategory.COMPLIANCE).effectiveDated()
                .alsoAt(ScopeType.ORG_UNIT, ScopeType.CUSTOMER);
        ContractChecklist.ITEMS.forEach(item -> checklist.property(item, SettingType.BOOLEAN, false));
        return List.of(checklist.build());
    }

    /** The rules a resolved value turns on, in checklist order. */
    static List<String> required(Map<String, Object> resolved) {
        return ContractChecklist.ITEMS.stream().filter(item -> Boolean.TRUE.equals(resolved.get(item))).toList();
    }
}
