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

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.lms.order.api.FreightMode;
import com.lms.settings.api.SettingContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The ambiguity rule for combination profiles, without a database: which two
 * combinations could both match one context, and which of those the fold
 * could not choose between.
 */
class SettingCombinationTest {

    private static final UUID UNIT = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID OTHER_UNIT = UUID.fromString("00000000-0000-0000-0000-0000000000a2");
    private static final UUID CUSTOMER = UUID.fromString("00000000-0000-0000-0000-0000000000b1");
    private static final UUID OTHER_CUSTOMER = UUID.fromString("00000000-0000-0000-0000-0000000000b2");
    private static final UUID VEHICLE = UUID.fromString("00000000-0000-0000-0000-0000000000d1");
    private static final String NORTH = "/t/in/north/";
    private static final String GURGAON = "/t/in/north/gurgaon/";
    private static final String SOUTH = "/t/in/south/";

    private static SettingCombination unitAndCustomer(String path, UUID customer, int priority) {
        return new SettingCombination(path == null ? null : UNIT, path, customer, null, null, null, priority);
    }

    @Test
    @DisplayName("dimensions count the conditions named; depth is the unit's place in the tree")
    void dimensionsAndDepth() {
        SettingCombination c = new SettingCombination(UNIT, GURGAON, CUSTOMER, null, VEHICLE, "LTL", 0);
        assertThat(c.dimensions()).isEqualTo(4);
        assertThat(c.orgDepth()).isEqualTo(4);
        assertThat(unitAndCustomer(NORTH, CUSTOMER, 0).orgDepth()).isEqualTo(3);
        assertThat(new SettingCombination(null, null, CUSTOMER, null, VEHICLE, null, 0).orgDepth()).isZero();
    }

    @Test
    @DisplayName("two combinations overlap unless a dimension names different values or the units' subtrees are disjoint")
    void overlap() {
        assertThat(unitAndCustomer(NORTH, CUSTOMER, 0).couldOverlap(unitAndCustomer(GURGAON, CUSTOMER, 0))).isTrue();
        assertThat(unitAndCustomer(NORTH, CUSTOMER, 0).couldOverlap(unitAndCustomer(SOUTH, CUSTOMER, 0)))
                .as("disjoint subtrees").isFalse();
        assertThat(unitAndCustomer(NORTH, CUSTOMER, 0).couldOverlap(unitAndCustomer(NORTH, OTHER_CUSTOMER, 0)))
                .as("different customers").isFalse();
        SettingCombination customerAndVehicle = new SettingCombination(null, null, CUSTOMER, null, VEHICLE, null, 0);
        assertThat(unitAndCustomer(NORTH, CUSTOMER, 0).couldOverlap(customerAndVehicle))
                .as("a dimension one leaves open matches anything").isTrue();
        SettingCombination ltl = new SettingCombination(UNIT, NORTH, null, null, null, "LTL", 0);
        SettingCombination ftl = new SettingCombination(UNIT, NORTH, null, null, null, "FTL", 0);
        assertThat(ltl.couldOverlap(ftl)).isFalse();
    }

    @Test
    @DisplayName("a tie needs an overlap, equal dimensions, an equally deep unit and equal priority")
    void ties() {
        SettingCombination north = unitAndCustomer(NORTH, CUSTOMER, 0);
        assertThat(north.tiesWith(unitAndCustomer(NORTH, CUSTOMER, 0))).isTrue();
        assertThat(north.tiesWith(unitAndCustomer(NORTH, CUSTOMER, 1))).as("priority settles it").isFalse();
        assertThat(north.tiesWith(unitAndCustomer(GURGAON, CUSTOMER, 0))).as("the deeper unit settles it").isFalse();
        assertThat(north.tiesWith(new SettingCombination(UNIT, NORTH, CUSTOMER, null, null, "LTL", 0)))
                .as("more conditions settle it").isFalse();
        // Same depth on disjoint subtrees never meet.
        assertThat(north.tiesWith(new SettingCombination(OTHER_UNIT, SOUTH, CUSTOMER, null, null, null, 0))).isFalse();
        // Customer + vehicle and unit + customer: both two dimensions, but the first names no unit (depth 0).
        assertThat(new SettingCombination(null, null, CUSTOMER, null, VEHICLE, null, 0)
                .tiesWith(new SettingCombination(null, null, CUSTOMER, null, null, "LTL", 0))).isTrue();
    }

    @Test
    @DisplayName("only a key both set is a clash; each is named with the profile it ties with")
    void clashes() {
        SettingCombination mine = unitAndCustomer(NORTH, CUSTOMER, 0);
        List<CombinationRules.Other> others = List.of(
                new CombinationRules.Other(UUID.randomUUID(), "Twin", unitAndCustomer(NORTH, CUSTOMER, 0),
                        Set.of("a.b", "c.d")),
                new CombinationRules.Other(UUID.randomUUID(), "Higher", unitAndCustomer(NORTH, CUSTOMER, 9),
                        Set.of("a.b")));
        Map<String, String> out = CombinationRules.clashes(mine, Set.of("a.b", "e.f"), others);
        assertThat(out).containsOnlyKeys("a.b");
        assertThat(out.get("a.b")).contains("Twin");
        assertThat(CombinationRules.clashes(mine, Set.of("e.f"), others)).isEmpty();
    }

    @Test
    @DisplayName("the settings vocabulary of freight modes is order's, name for name")
    void freightModes() {
        assertThat(SettingContext.FREIGHT_MODES)
                .containsExactlyElementsOf(Arrays.stream(FreightMode.values()).map(Enum::name).toList());
        assertThatThrownBy(() -> SettingContext.tenant().withFreightMode("SEA"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
