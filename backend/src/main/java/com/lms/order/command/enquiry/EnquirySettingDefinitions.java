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

package com.lms.order.command.enquiry;

import java.util.List;

import com.lms.settings.api.ScopeType;
import com.lms.settings.api.SettingContributor;
import com.lms.settings.api.SettingDefinition;
import com.lms.settings.api.SettingKey;
import org.springframework.stereotype.Component;

/**
 * The enquiry desk's policy: how long a customer's request stays open for an
 * answer, and how long a quote holds. Both are resolved for the enquiry's own
 * unit and customer when the enquiry or the quote is made, and the result is
 * stored on the record -- changing the policy later moves no deadline that was
 * already given.
 */
@Component
public class EnquirySettingDefinitions implements SettingContributor {

    /** Hours from creation until an enquiry with open lines is closed by the sweep. */
    public static final SettingKey<Integer> RESPONSE_DEADLINE_HOURS =
            SettingKey.integer("order.enquiry.responseDeadlineHours");
    /** Hours a quote holds from the moment it is issued or revised. */
    public static final SettingKey<Integer> QUOTE_VALIDITY_HOURS =
            SettingKey.integer("order.enquiry.quoteValidityHours");

    @Override
    public List<SettingDefinition> definitions() {
        return List.of(
                SettingDefinition.of(RESPONSE_DEADLINE_HOURS, "Enquiry response deadline (hours)",
                                "Hours after an enquiry is created by which every line should be decided."
                                        + " The deadline is fixed on the enquiry when it is created; once it has"
                                        + " passed, a sweep cancels the lines still open. Lines already won are"
                                        + " never cancelled.")
                        .defaultValue(72).range(1, 2_160)
                        .alsoAt(ScopeType.ORG_UNIT, ScopeType.CUSTOMER).build(),
                SettingDefinition.of(QUOTE_VALIDITY_HOURS, "Quote validity (hours)",
                                "Hours a quote holds from the moment it is issued or revised, unless the person"
                                        + " issuing it states a date.")
                        .defaultValue(168).range(1, 8_760)
                        .alsoAt(ScopeType.ORG_UNIT, ScopeType.CUSTOMER).build());
    }
}
