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
package com.lms.identity.abac;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

import com.lms.identity.access.AccessSubject;
import com.lms.identity.access.SubjectAttributes;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The condition language and the combining rule, with no database.
 *
 * <p>The default policies are parsed from the exact JSON the migration seeds,
 * so a change to either that breaks the "no attribute, no restriction" promise
 * fails here before it reaches a tenant.
 */
class PolicyDecisionTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final String INVOICE_LIMIT =
            "{\"attr\": \"resource.amount\", \"op\": \"gt\", \"ref\": \"subject.approvalLimit\"}";
    private static final String CLOSE_LIMIT = """
            {"allOf": [{"attr": "resource.suspendsSettlement", "op": "eq", "value": true},
                       {"attr": "subject.approvalLimit", "op": "lt", "value": 100000}]}""";
    private static final String ORG_SUBTREE = """
            {"allOf": [{"attr": "resource.orgPath", "op": "present"},
                       {"attr": "subject.orgPath", "op": "present"},
                       {"not": {"attr": "resource.orgPath", "op": "startsWith", "ref": "subject.orgPath"}}]}""";

    @Test
    @DisplayName("a user with no approval limit is never over it")
    void noLimitNoRestriction() {
        Condition rule = parse(INVOICE_LIMIT);
        assertThat(rule.holds(attributes(subject(null, null), Map.of("amount", 1_000_000)))).isFalse();
    }

    @Test
    @DisplayName("the invoice limit bites above the limit and not at it")
    void invoiceLimit() {
        Condition rule = parse(INVOICE_LIMIT);
        AccessSubject limited = subject("/acme/", new BigDecimal("50000"));
        assertThat(rule.holds(attributes(limited, Map.of("amount", new BigDecimal("50000.00"))))).isFalse();
        assertThat(rule.holds(attributes(limited, Map.of("amount", new BigDecimal("50000.01"))))).isTrue();
    }

    @Test
    @DisplayName("closing a ticket that holds money needs the threshold; one that holds none does not")
    void closeLimit() {
        Condition rule = parse(CLOSE_LIMIT);
        AccessSubject small = subject("/acme/", new BigDecimal("500"));
        assertThat(rule.holds(attributes(small, Map.of("suspendsSettlement", true)))).isTrue();
        assertThat(rule.holds(attributes(small, Map.of("suspendsSettlement", false)))).isFalse();
        assertThat(rule.holds(attributes(subject("/acme/", new BigDecimal("100000")),
                Map.of("suspendsSettlement", true)))).isFalse();
        assertThat(rule.holds(attributes(subject("/acme/", null),
                Map.of("suspendsSettlement", true)))).isFalse();
    }

    @Test
    @DisplayName("the subtree rule refuses siblings and ancestors, never descendants")
    void orgSubtree() {
        Condition rule = parse(ORG_SUBTREE);
        AccessSubject north = subject("/acme/north/", null);
        assertThat(rule.holds(attributes(north, Map.of("orgPath", "/acme/north/pune/")))).isFalse();
        assertThat(rule.holds(attributes(north, Map.of("orgPath", "/acme/north/")))).isFalse();
        assertThat(rule.holds(attributes(north, Map.of("orgPath", "/acme/south/")))).isTrue();
        assertThat(rule.holds(attributes(north, Map.of("orgPath", "/acme/")))).isTrue();
        // Nothing to compare: not this rule's call.
        assertThat(rule.holds(attributes(north, Map.of()))).isFalse();
    }

    @Test
    @DisplayName("freight modes and regions are comparable attributes")
    void modesAndRegions() {
        AccessSubject railOnly = new AccessSubject("u", Set.of(), "/acme/",
                new SubjectAttributes("WEST", List.of("RAIL"), null, null));
        Condition notFtl = parse("""
                {"allOf": [{"attr": "subject.freightModes", "op": "present"},
                           {"not": {"attr": "subject.freightModes", "op": "contains", "value": "FTL"}}]}""");
        assertThat(notFtl.holds(attributes(railOnly, Map.of()))).isTrue();
        assertThat(notFtl.holds(attributes(subject("/acme/", null), Map.of()))).isFalse();

        Condition west = parse("{\"attr\": \"subject.region\", \"op\": \"in\", \"value\": [\"WEST\", \"NORTH\"]}");
        assertThat(west.holds(attributes(railOnly, Map.of()))).isTrue();
    }

    @Test
    @DisplayName("deny overrides, and PERMIT policies turn an action into an allow-list")
    void combining() {
        Function<String, Object> attrs = attributes(subject("/acme/", new BigDecimal("10")),
                Map.of("amount", 5, "partnerId", UUID.randomUUID().toString()));
        PolicyStore.Rule denyOver = new PolicyStore.Rule("limit", "DENY", parse(INVOICE_LIMIT));
        PolicyStore.Rule permitNever = new PolicyStore.Rule("only-west", "PERMIT",
                parse("{\"attr\": \"subject.region\", \"op\": \"eq\", \"value\": \"WEST\"}"));
        PolicyStore.Rule permitAlways = new PolicyStore.Rule("anyone", "PERMIT",
                parse("{\"attr\": \"subject.orgPath\", \"op\": \"present\"}"));

        assertThat(PolicyDecisionPoint.combine(List.of(denyOver), attrs).permitted()).isTrue();
        assertThat(PolicyDecisionPoint.combine(List.of(denyOver, permitNever), attrs).permitted()).isFalse();
        assertThat(PolicyDecisionPoint.combine(List.of(permitNever, permitAlways), attrs).permitted()).isTrue();

        Function<String, Object> over = attributes(subject("/acme/", new BigDecimal("1")),
                Map.of("amount", 5));
        PolicyDecisionPoint.Decision refused =
                PolicyDecisionPoint.combine(List.of(permitAlways, denyOver), over);
        assertThat(refused.permitted()).isFalse();
        assertThat(refused.policy()).isEqualTo("limit");
    }

    @Test
    @DisplayName("comparing a number with a word is a non-match, not an error")
    void mismatchedTypes() {
        Condition rule = parse("{\"attr\": \"subject.region\", \"op\": \"lt\", \"value\": 5}");
        AccessSubject west = new AccessSubject("u", Set.of(), "/a/",
                new SubjectAttributes("WEST", null, null, null));
        assertThat(rule.holds(attributes(west, Map.of()))).isFalse();
    }

    @Test
    @DisplayName("mistakes are refused when the policy is written")
    void parseErrors() {
        assertRefused("{\"attr\": \"subject.region\", \"op\": \"approx\", \"value\": \"W\"}",
                "Unknown operator");
        assertRefused("{\"attr\": \"subject.regoin\", \"op\": \"eq\", \"value\": \"W\"}",
                "Unknown attribute");
        assertRefused("{\"attr\": \"subject.region\", \"op\": \"eq\"}", "exactly one of");
        assertRefused("{\"attr\": \"subject.region\", \"op\": \"eq\", \"value\": \"W\", "
                + "\"ref\": \"subject.orgPath\"}", "exactly one of");
        assertRefused("{\"attr\": \"subject.region\", \"op\": \"present\", \"value\": 1}", "takes no");
        assertRefused("{\"attr\": \"subject.region\", \"op\": \"in\", \"value\": \"W\"}", "list");
        assertRefused("{\"allOf\": []}", "non-empty");
        assertRefused("{}", "non-empty");
        assertRefused("{\"attr\": \"resource.a\", \"op\": \"eq\", \"value\": 1, \"extra\": 1}",
                "Unknown key");

        String deep = "{\"attr\": \"subject.region\", \"op\": \"present\"}";
        for (int i = 0; i < 10; i++) {
            deep = "{\"not\": " + deep + "}";
        }
        assertRefused(deep, "nests deeper");
    }

    // --------------------------------------------------------------- helpers

    private static void assertRefused(String json, String fragment) {
        assertThatThrownBy(() -> parse(json))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(fragment);
    }

    private static Condition parse(String json) {
        return Condition.parse(JSON.readValue(json, Map.class));
    }

    private static AccessSubject subject(String orgPath, BigDecimal limit) {
        return new AccessSubject("user", Set.of("INVOICE_APPROVE"), orgPath,
                new SubjectAttributes(null, null, null, limit));
    }

    private static Function<String, Object> attributes(AccessSubject subject,
                                                       Map<String, ?> resource) {
        return PolicyDecisionPoint.attributesFor(subject, new HashMap<>(resource));
    }
}
