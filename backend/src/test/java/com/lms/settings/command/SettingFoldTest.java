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
package com.lms.settings.command;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.lms.settings.api.Resolved;
import com.lms.settings.api.ScopeType;
import com.lms.settings.api.SettingDefinition;
import com.lms.settings.api.SettingKey;
import com.lms.settings.command.SettingFold.Candidate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SettingFoldTest {

    private static final SettingDefinition EVERYWHERE = SettingDefinition.of(SettingKey.integer("test.every"),
            "Every", "Allowed at every scope").defaultValue(1)
            .alsoAt(ScopeType.ORG_UNIT, ScopeType.CUSTOMER, ScopeType.VEHICLE, ScopeType.USER).build();
    private static final SettingDefinition TENANT_ONLY = SettingDefinition.of(SettingKey.integer("test.tenant"),
            "Tenant", "Organisation default only").defaultValue(7).build();

    private static final UUID ORG = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID REGION = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    private static final UUID BRANCH = UUID.fromString("00000000-0000-0000-0000-00000000000c");
    private static final UUID CUSTOMER = UUID.fromString("00000000-0000-0000-0000-00000000000d");
    private static final UUID USER = UUID.fromString("00000000-0000-0000-0000-00000000000e");

    private static Candidate value(UUID profile, ScopeType type, int depth, String key, Object value) {
        return new Candidate(profile, type, type == ScopeType.ORG_DEFAULT ? null : profile, type + " profile",
                depth, key, value);
    }

    private static Map<String, Resolved<Object>> fold(List<SettingDefinition> wanted, Candidate... candidates) {
        return SettingFold.fold(wanted, Map.of("test.every", 1, "test.tenant", 7), List.of(candidates));
    }

    @Test
    @DisplayName("with no profile setting a key, the system default answers and says so")
    void systemDefault() {
        Resolved<Object> r = fold(List.of(EVERYWHERE)).get("test.every");
        assertThat(r.value()).isEqualTo(1);
        assertThat(r.source().isSystemDefault()).isTrue();
    }

    @Test
    @DisplayName("org default < region < branch (deeper wins) < customer < user, whatever order the rows come in")
    void foldOrder() {
        Candidate org = value(ORG, ScopeType.ORG_DEFAULT, 0, "test.every", 10);
        Candidate region = value(REGION, ScopeType.ORG_UNIT, 10, "test.every", 20);
        Candidate branch = value(BRANCH, ScopeType.ORG_UNIT, 17, "test.every", 30);
        Candidate customer = value(CUSTOMER, ScopeType.CUSTOMER, 0, "test.every", 40);
        Candidate user = value(USER, ScopeType.USER, 0, "test.every", 50);

        assertThat(fold(List.of(EVERYWHERE), org).get("test.every").value()).isEqualTo(10);
        assertThat(fold(List.of(EVERYWHERE), branch, org, region).get("test.every").value()).isEqualTo(30);
        assertThat(fold(List.of(EVERYWHERE), region, org).get("test.every").value()).isEqualTo(20);
        assertThat(fold(List.of(EVERYWHERE), customer, branch, org).get("test.every").value()).isEqualTo(40);
        Resolved<Object> top = fold(List.of(EVERYWHERE), user, customer, branch, region, org).get("test.every");
        assertThat(top.value()).isEqualTo(50);
        assertThat(top.source().scopeType()).isEqualTo(ScopeType.USER);
        assertThat(top.source().profileId()).isEqualTo(USER);
    }

    @Test
    @DisplayName("a vendor's value beats the units' and the organisation's, and lies just above a customer's")
    void vendorScope() {
        SettingDefinition procurement = SettingDefinition.of(SettingKey.integer("test.vendor"), "Vendor",
                "Kept per vendor").defaultValue(0).alsoAt(ScopeType.ORG_UNIT, ScopeType.VENDOR).build();
        UUID vendor = UUID.fromString("00000000-0000-0000-0000-00000000000f");
        Candidate org = value(ORG, ScopeType.ORG_DEFAULT, 0, "test.vendor", 10);
        Candidate branch = value(BRANCH, ScopeType.ORG_UNIT, 17, "test.vendor", 30);
        Candidate forVendor = value(vendor, ScopeType.VENDOR, 0, "test.vendor", 60);

        Resolved<Object> r = SettingFold.fold(List.of(procurement), Map.of("test.vendor", 0),
                List.of(forVendor, branch, org)).get("test.vendor");
        assertThat(r.value()).isEqualTo(60);
        assertThat(r.source().scopeType()).isEqualTo(ScopeType.VENDOR);
        assertThat(ScopeType.VENDOR.precedence()).isGreaterThan(ScopeType.CUSTOMER.precedence())
                .isLessThan(ScopeType.USER.precedence());
        assertThat(ScopeType.VENDOR.needsScopeId()).isTrue();
    }

    @Test
    @DisplayName("each key is resolved on its own: a key a profile does not set falls through to the next one out")
    void perKey() {
        SettingDefinition other = SettingDefinition.of(SettingKey.integer("test.other"), "Other", "Another key")
                .defaultValue(0).alsoAt(ScopeType.CUSTOMER).build();
        Map<String, Resolved<Object>> out = SettingFold.fold(List.of(EVERYWHERE, other),
                Map.of("test.every", 1, "test.other", 0),
                List.of(value(ORG, ScopeType.ORG_DEFAULT, 0, "test.other", 5),
                        value(CUSTOMER, ScopeType.CUSTOMER, 0, "test.every", 40)));
        assertThat(out.get("test.every").value()).isEqualTo(40);
        assertThat(out.get("test.other").value()).isEqualTo(5);
        assertThat(out.get("test.other").source().scopeType()).isEqualTo(ScopeType.ORG_DEFAULT);
    }

    @Test
    @DisplayName("a value held at a scope its key does not allow is never applied")
    void disallowedScopeIgnored() {
        Resolved<Object> r = fold(List.of(TENANT_ONLY),
                value(CUSTOMER, ScopeType.CUSTOMER, 0, "test.tenant", 99),
                value(ORG, ScopeType.ORG_DEFAULT, 0, "test.tenant", 8)).get("test.tenant");
        assertThat(r.value()).isEqualTo(8);
        assertThat(r.source().scopeType()).isEqualTo(ScopeType.ORG_DEFAULT);
    }

    @Test
    @DisplayName("within a profile, the latest version in effect on the day asked applies; a later one not yet")
    void asOfVersions() {
        LocalDate jan = LocalDate.of(2027, 1, 1);
        LocalDate apr = LocalDate.of(2027, 4, 1);
        List<Candidate> versions = List.of(
                new Candidate(ORG, ScopeType.ORG_DEFAULT, null, "org", 0, "test.every", null, 10),
                new Candidate(ORG, ScopeType.ORG_DEFAULT, null, "org", 0, "test.every", apr, 30),
                new Candidate(ORG, ScopeType.ORG_DEFAULT, null, "org", 0, "test.every", jan, 20));
        Map<String, Object> defaults = Map.of("test.every", 1);
        assertThat(SettingFold.fold(List.of(EVERYWHERE), defaults, versions, LocalDate.of(2026, 12, 31))
                .get("test.every").value()).isEqualTo(10);
        Resolved<Object> feb = SettingFold.fold(List.of(EVERYWHERE), defaults, versions, LocalDate.of(2027, 2, 1))
                .get("test.every");
        assertThat(feb.value()).isEqualTo(20);
        assertThat(feb.source().effectiveFrom()).isEqualTo(jan);
        assertThat(SettingFold.fold(List.of(EVERYWHERE), defaults, versions, apr).get("test.every").value())
                .isEqualTo(30);
        // A more specific profile whose only version starts later does not answer before it starts.
        List<Candidate> withBranch = new java.util.ArrayList<>(versions);
        withBranch.add(new Candidate(BRANCH, ScopeType.ORG_UNIT, BRANCH, "branch", 9, "test.every", apr, 99));
        assertThat(SettingFold.fold(List.of(EVERYWHERE), defaults, withBranch, LocalDate.of(2027, 3, 1))
                .get("test.every").value()).isEqualTo(20);
        assertThat(SettingFold.fold(List.of(EVERYWHERE), defaults, withBranch, apr).get("test.every").value())
                .isEqualTo(99);
    }

    @Test
    @DisplayName("platform < org default, and among platform profiles global < country < state")
    void platformFold() {
        SettingDefinition rate = SettingDefinition.of(SettingKey.integer("test.rate"), "Rate", "Platform first")
                .defaultValue(0).alsoAt(ScopeType.PLATFORM).build();
        UUID global = UUID.fromString("00000000-0000-0000-0000-0000000000f1");
        UUID india = UUID.fromString("00000000-0000-0000-0000-0000000000f2");
        UUID state = UUID.fromString("00000000-0000-0000-0000-0000000000f3");
        Map<String, Object> defaults = Map.of("test.rate", 0);
        Candidate g = new Candidate(global, ScopeType.PLATFORM, null, "global", 0, "test.rate", 18);
        Candidate c = new Candidate(india, ScopeType.PLATFORM, null, "IN", 1, "test.rate", 19);
        Candidate st = new Candidate(state, ScopeType.PLATFORM, null, "IN-MH", 2, "test.rate", 20);
        Candidate org = new Candidate(ORG, ScopeType.ORG_DEFAULT, null, "org", 0, "test.rate", 21);
        assertThat(SettingFold.fold(List.of(rate), defaults, List.of(st, g, c)).get("test.rate").value())
                .isEqualTo(20);
        assertThat(SettingFold.fold(List.of(rate), defaults, List.of(g, c)).get("test.rate").value()).isEqualTo(19);
        Resolved<Object> tenant = SettingFold.fold(List.of(rate), defaults, List.of(st, org, g)).get("test.rate");
        assertThat(tenant.value()).isEqualTo(21);
        assertThat(tenant.source().scopeType()).isEqualTo(ScopeType.ORG_DEFAULT);
        // A key only the platform sets ignores a tenant's value even if one were stored.
        SettingDefinition locked = SettingDefinition.of(SettingKey.integer("test.rate"), "Rate", "Platform only")
                .defaultValue(0).platformOnly().build();
        assertThat(SettingFold.fold(List.of(locked), defaults, List.of(org, g)).get("test.rate").value())
                .isEqualTo(18);
    }

    @Test
    @DisplayName("values of keys not asked for are left out")
    void onlyWanted() {
        assertThat(fold(List.of(TENANT_ONLY), value(ORG, ScopeType.ORG_DEFAULT, 0, "test.every", 3)))
                .containsOnlyKeys("test.tenant");
    }

    @Test
    @DisplayName("a combination beats vehicle, customer and vendor but not the user; more conditions, then the deeper"
            + " unit, then the higher priority win among combinations, and the source names the combination")
    void combinations() {
        SettingDefinition combined = SettingDefinition.of(SettingKey.integer("test.combined"), "Combined",
                "Kept per combination").defaultValue(0)
                .alsoAt(ScopeType.ORG_UNIT, ScopeType.CUSTOMER, ScopeType.VEHICLE, ScopeType.VENDOR,
                        ScopeType.COMBINATION, ScopeType.USER).build();
        Map<String, Object> defaults = Map.of("test.combined", 0);
        UUID a = UUID.fromString("00000000-0000-0000-0000-0000000000c1");
        UUID b = UUID.fromString("00000000-0000-0000-0000-0000000000c2");
        UUID c = UUID.fromString("00000000-0000-0000-0000-0000000000c3");
        UUID d = UUID.fromString("00000000-0000-0000-0000-0000000000c4");
        Candidate customer = value(CUSTOMER, ScopeType.CUSTOMER, 0, "test.combined", 40);
        Candidate user = value(USER, ScopeType.USER, 0, "test.combined", 90);
        // Two conditions under the branch (depth 3), priority 0.
        Candidate branchAndCustomer = combo(a, 2, 3, 0, 60, "Branch Gurgaon + Customer Delta Foods");
        // Two conditions under the region (depth 2), higher priority: depth still wins.
        Candidate regionAndCustomer = combo(b, 2, 2, 50, 61, "Zone North + Customer Delta Foods");
        // Three conditions under the region: more conditions win over a deeper unit.
        Candidate three = combo(c, 3, 2, 0, 70, "Zone North + Customer Delta Foods + Mode LTL");
        // Same as the first but a higher priority.
        Candidate prioritised = combo(d, 2, 3, 5, 65, "Branch Gurgaon + Vehicle HR26X");

        Resolved<Object> r = SettingFold.fold(List.of(combined), defaults,
                List.of(customer, regionAndCustomer, branchAndCustomer)).get("test.combined");
        assertThat(r.value()).isEqualTo(60);
        assertThat(r.source().scopeType()).isEqualTo(ScopeType.COMBINATION);
        assertThat(r.source().label()).isEqualTo("Branch Gurgaon + Customer Delta Foods");
        assertThat(SettingFold.fold(List.of(combined), defaults, List.of(branchAndCustomer, three, regionAndCustomer))
                .get("test.combined").value()).isEqualTo(70);
        assertThat(SettingFold.fold(List.of(combined), defaults, List.of(prioritised, branchAndCustomer))
                .get("test.combined").value()).isEqualTo(65);
        assertThat(SettingFold.fold(List.of(combined), defaults, List.of(three, user))
                .get("test.combined").value()).isEqualTo(90);
        assertThat(ScopeType.COMBINATION.precedence()).isGreaterThan(ScopeType.VENDOR.precedence())
                .isGreaterThan(ScopeType.VEHICLE.precedence()).isGreaterThan(ScopeType.CUSTOMER.precedence())
                .isLessThan(ScopeType.USER.precedence());
        assertThat(ScopeType.COMBINATION.needsScopeId()).isFalse();
        // A key that does not allow COMBINATION ignores a combination's value.
        assertThat(SettingFold.fold(List.of(EVERYWHERE), Map.of("test.every", 1),
                List.of(new Candidate(a, ScopeType.COMBINATION, a, "c", 3, "test.every", null, 5, 2, 0, "x"),
                        value(CUSTOMER, ScopeType.CUSTOMER, 0, "test.every", 40)))
                .get("test.every").value()).isEqualTo(40);
    }

    @Test
    @DisplayName("the provenance label names the unit's level, or what a profile is for")
    void labels() {
        assertThat(ScopeLabels.source(ScopeType.ORG_UNIT, "North rules", "COUNTRY", "India", null, null, null))
                .isEqualTo("Country: India");
        assertThat(ScopeLabels.source(ScopeType.ORG_UNIT, "x", "ZONE", "North", null, null, null))
                .isEqualTo("Zone: North");
        assertThat(ScopeLabels.source(ScopeType.ORG_UNIT, "x", "BRANCH", "Gurgaon", null, null, null))
                .isEqualTo("Branch: Gurgaon");
        assertThat(ScopeLabels.source(ScopeType.CUSTOMER, "Terms", null, null, null, null, null))
                .isEqualTo("Customer: Terms");
        assertThat(ScopeLabels.source(ScopeType.PLATFORM, "p", null, null, "IN", "KA", null))
                .isEqualTo("Platform: IN-KA");
        assertThat(ScopeLabels.combination(new ScopeLabels.Parts("BRANCH", "Gurgaon", "Delta Foods", null, null,
                "LTL"))).isEqualTo("Branch Gurgaon + Customer Delta Foods + Mode LTL");
        Resolved<Object> fallback = fold(List.of(EVERYWHERE), value(CUSTOMER, ScopeType.CUSTOMER, 0, "test.every", 4))
                .get("test.every");
        assertThat(fallback.source().label()).isEqualTo("Customer: CUSTOMER profile");
        assertThat(fold(List.of(EVERYWHERE)).get("test.every").source().label()).isEqualTo("System default");
    }

    private static Candidate combo(UUID id, int dimensions, int depth, int priority, Object value, String label) {
        return new Candidate(id, ScopeType.COMBINATION, id, "combination", depth, "test.combined", null, value,
                dimensions, priority, label);
    }
}
