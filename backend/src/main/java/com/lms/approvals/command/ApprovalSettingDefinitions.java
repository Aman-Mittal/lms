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
package com.lms.approvals.command;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import com.lms.approvals.api.ApprovalPolicy;
import com.lms.settings.api.ScopeType;
import com.lms.settings.api.SettingContributor;
import com.lms.settings.api.SettingDefinition;
import com.lms.settings.api.SettingKey;
import com.lms.settings.api.SettingType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * One setting per approvable subject type, {@code approvals.policy.<type>}
 * with the type in camel case ({@code approvals.policy.orderCancellation}), whose
 * system default is the type's code default. The organisation default, an
 * organisational unit, a customer or a combination (unit, customer, freight
 * mode) may each set it, so a branch can ask for approval where the
 * organisation does not.
 *
 * <p>A submitted value is refused ({@code setting-invalid-value}) when a
 * property has the wrong shape (see {@link ApprovalPolicyValues}), when a step
 * names a permission that does not exist, and when it would switch off a
 * mandatory flow.
 */
@Component
public class ApprovalSettingDefinitions implements SettingContributor {

    static final ScopeType[] SCOPES = {ScopeType.ORG_UNIT, ScopeType.CUSTOMER, ScopeType.COMBINATION};

    private final JdbcClient jdbc;

    public ApprovalSettingDefinitions(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** The setting key of a subject type: {@code ORDER_CANCELLATION} is {@code approvals.policy.orderCancellation}. */
    public static SettingKey<Map<String, Object>> keyOf(String subjectType) {
        StringBuilder camel = new StringBuilder();
        boolean upper = false;
        for (char c : subjectType.toCharArray()) {
            if (c == '_') {
                upper = true;
            } else {
                camel.append(upper ? Character.toUpperCase(c) : Character.toLowerCase(c));
                upper = false;
            }
        }
        return SettingKey.json("approvals.policy." + camel);
    }

    @Override
    public List<SettingDefinition> definitions() {
        return ApprovalPolicyDefaults.subjectTypes().stream().map(type -> {
            ApprovalPolicy base = ApprovalPolicyDefaults.of(type).orElseThrow();
            return SettingDefinition.of(keyOf(type), "Approval policy: " + type,
                            "Whether " + type + " asks for approval, who decides in what order, whether"
                                    + " the requester may decide, how long a request stays open and what"
                                    + " expiry does. Properties left out take the code default."
                                    + (base.mandatory() ? " Always required: it cannot be switched off." : ""))
                    .defaultValue(ApprovalPolicyValues.toValue(base))
                    .alsoAt(SCOPES)
                    .property(ApprovalPolicyValues.REQUIRED, SettingType.BOOLEAN, true)
                    .property(ApprovalPolicyValues.STEPS, SettingType.STRING, false)
                    .property(ApprovalPolicyValues.ORDERED, SettingType.BOOLEAN, false)
                    .property(ApprovalPolicyValues.SELF_APPROVAL, SettingType.BOOLEAN, false)
                    .property(ApprovalPolicyValues.DUE_AFTER, SettingType.STRING, false)
                    .property(ApprovalPolicyValues.ON_EXPIRY, SettingType.STRING, false)
                    .build();
        }).toList();
    }

    @Override
    public Optional<String> refusal(SettingDefinition definition, Object canonicalValue) {
        Optional<String> type = ApprovalPolicyDefaults.subjectTypes().stream()
                .filter(t -> keyOf(t).name().equals(definition.key())).findFirst();
        if (type.isEmpty() || !(canonicalValue instanceof Map<?, ?> raw)) {
            return Optional.empty();
        }
        ApprovalPolicy base = ApprovalPolicyDefaults.of(type.get()).orElseThrow();
        Map<String, Object> value = new LinkedHashMap<>();
        raw.forEach((k, v) -> value.put(String.valueOf(k), v));
        if (base.mandatory() && Boolean.FALSE.equals(value.get(ApprovalPolicyValues.REQUIRED))) {
            return Optional.of(type.get() + " happens only through an approval; 'required' cannot be false");
        }
        ApprovalPolicy policy;
        try {
            policy = ApprovalPolicyValues.overlay(base, value);
        } catch (IllegalArgumentException e) {
            return Optional.of(e.getMessage());
        }
        Set<String> known = jdbc.sql("SELECT code FROM permission WHERE code IN (:codes)")
                .param("codes", policy.decidePermissions())
                .query(String.class).list().stream().collect(Collectors.toSet());
        return policy.decidePermissions().stream().filter(code -> !known.contains(code)).findFirst()
                .map(code -> "property 'steps' names '" + code + "', which is not a permission");
    }
}
