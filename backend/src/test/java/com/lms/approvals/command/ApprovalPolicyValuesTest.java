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

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.lms.approvals.api.ApprovalPolicy;
import com.lms.approvals.api.ApprovalPolicy.Expiry;
import com.lms.approvals.api.ApprovalSubjectTypes;
import com.lms.settings.api.SettingDefinition;
import com.lms.settings.command.SettingRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** An approval policy as a profile setting's value: shape, mapping and the mandatory flag, no database. */
class ApprovalPolicyValuesTest {

    private static final ApprovalPolicy ORDER =
            ApprovalPolicyDefaults.of(ApprovalSubjectTypes.ORDER_CANCELLATION).orElseThrow();
    private static final ApprovalPolicy POD =
            ApprovalPolicyDefaults.of(ApprovalSubjectTypes.POD_REVERSAL).orElseThrow();

    private final ApprovalSettingDefinitions contributor = new ApprovalSettingDefinitions(null);

    private static Map<String, Object> value(Object... pairs) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put((String) pairs[i], pairs[i + 1]);
        }
        return map;
    }

    @Test
    @DisplayName("a policy survives the round trip through its setting value")
    void roundTrip() {
        ApprovalPolicy custom = new ApprovalPolicy(ApprovalSubjectTypes.ORDER_CANCELLATION, true, false,
                List.of("ORDER_APPROVE", "INVOICE_APPROVE"), false, true, Duration.ofHours(8), Expiry.AUTO_APPROVE);
        Map<String, Object> value = ApprovalPolicyValues.toValue(custom);
        assertThat(value).containsEntry("steps", "ORDER_APPROVE,INVOICE_APPROVE").containsEntry("dueAfter", "PT8H");
        assertThat(ApprovalPolicyValues.overlay(ORDER, value)).isEqualTo(custom);
        assertThat(ApprovalPolicyValues.overlay(ORDER, ApprovalPolicyValues.toValue(ORDER))).isEqualTo(ORDER);
    }

    @Test
    @DisplayName("a property left out is the code default's")
    void omittedPropertiesTakeTheDefault() {
        ApprovalPolicy policy = ApprovalPolicyValues.overlay(ORDER, value("required", true));
        assertThat(policy.required()).isTrue();
        assertThat(policy.decidePermissions()).isEqualTo(ORDER.decidePermissions());
        assertThat(policy.due()).isEqualTo(ORDER.due());
        assertThat(policy.onExpiry()).isEqualTo(ORDER.onExpiry());
    }

    @Test
    @DisplayName("the mandatory flag of the code default survives a value that says required=false")
    void mandatoryStaysRequired() {
        assertThat(ApprovalPolicyValues.overlay(POD, value("required", false)).required()).isTrue();
        assertThat(contributor.refusal(definition(ApprovalSubjectTypes.POD_REVERSAL), value("required", false)))
                .hasValueSatisfying(why -> assertThat(why).contains("only through an approval"));
    }

    @Test
    @DisplayName("bad shapes are refused with the property named")
    void badShapes() {
        for (Map<String, Object> bad : List.<Map<String, Object>>of(
                value("required", true, "steps", ""),
                value("required", true, "steps", "ORDER_APPROVE,,INVOICE_APPROVE"),
                value("required", true, "steps", "order_approve"),
                value("required", true, "steps", "A,B,C,D,E,F"),
                value("required", true, "dueAfter", "three days"),
                value("required", true, "dueAfter", "PT0S"),
                value("required", true, "dueAfter", "P91D"),
                value("required", true, "onExpiry", "IGNORE"),
                value("required", true, "ordered", "yes"),
                value("required", true, "approvers", 2))) {
            assertThatThrownBy(() -> ApprovalPolicyValues.overlay(ORDER, bad)).as(bad.toString())
                    .isInstanceOf(IllegalArgumentException.class);
            assertThat(contributor.refusal(definition(ApprovalSubjectTypes.ORDER_CANCELLATION), bad))
                    .as(bad.toString()).isPresent();
        }
    }

    @Test
    @DisplayName("every subject type is registered as a well-formed JSON setting whose default is its code default")
    void registeredPerSubjectType() {
        SettingRegistry registry = new SettingRegistry(List.of(contributor));
        assertThat(registry.all()).extracting(SettingDefinition::key)
                .containsExactly("approvals.policy.orderCancellation", "approvals.policy.podReversal");
        assertThat(ApprovalSettingDefinitions.keyOf("POD_REVERSAL").name()).isEqualTo("approvals.policy.podReversal");
        for (String type : ApprovalPolicyDefaults.subjectTypes()) {
            Object stored = registry.defaultOf(ApprovalSettingDefinitions.keyOf(type).name());
            @SuppressWarnings("unchecked")
            Map<String, Object> asMap = (Map<String, Object>) stored;
            assertThat(ApprovalPolicyValues.overlay(ApprovalPolicyDefaults.of(type).orElseThrow(), asMap))
                    .isEqualTo(ApprovalPolicyDefaults.of(type).orElseThrow());
        }
        assertThat(registry.find("approvals.policy.orderCancellation").orElseThrow().scopes())
                .extracting(Enum::name).containsExactlyInAnyOrder("ORG_DEFAULT", "ORG_UNIT", "CUSTOMER", "COMBINATION");
    }

    @Test
    @DisplayName("an unknown property or a wrong type is refused by the definition itself")
    void definitionRefusesShape() {
        SettingRegistry registry = new SettingRegistry(List.of(contributor));
        String key = "approvals.policy.orderCancellation";
        assertThatThrownBy(() -> registry.validate(com.lms.settings.api.ScopeType.ORG_DEFAULT,
                Map.of(key, value("required", true, "approvers", 2)))).hasMessageContaining("approvers");
        assertThatThrownBy(() -> registry.validate(com.lms.settings.api.ScopeType.ORG_DEFAULT,
                Map.of(key, value("steps", "ORDER_APPROVE")))).hasMessageContaining("required");
    }

    private SettingDefinition definition(String type) {
        return contributor.definitions().stream()
                .filter(d -> d.key().equals(ApprovalSettingDefinitions.keyOf(type).name())).findFirst().orElseThrow();
    }
}
