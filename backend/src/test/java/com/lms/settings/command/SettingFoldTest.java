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
}
