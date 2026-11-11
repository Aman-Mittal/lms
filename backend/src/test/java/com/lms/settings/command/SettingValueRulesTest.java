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

import java.math.BigDecimal;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.lms.settings.api.ScopeType;
import com.lms.settings.api.SettingContributor;
import com.lms.settings.api.SettingDefinition;
import com.lms.settings.api.SettingKey;
import com.lms.settings.api.SettingType;
import com.lms.settings.api.StringFormat;
import com.lms.shared.error.BusinessRuleViolationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SettingValueRulesTest {

    private static final SettingDefinition PERCENT = SettingDefinition.of(SettingKey.decimal("t.percent"), "P", "d")
            .defaultValue(BigDecimal.ZERO).range(0, 100).scale(2).alsoAt(ScopeType.CUSTOMER).build();
    private static final SettingDefinition SIZE = SettingDefinition.of(SettingKey.integer("t.size"), "S", "d")
            .defaultValue(50).range(10, 200).alsoAt(ScopeType.USER).build();
    private static final SettingDefinition WAIT = SettingDefinition.of(SettingKey.duration("t.wait"), "W", "d")
            .defaultValue(Duration.ofMinutes(15)).between(Duration.ofMinutes(1), Duration.ofDays(7)).build();
    private static final SettingDefinition THEME = SettingDefinition.of(SettingKey.enumeration("t.theme"), "T", "d")
            .defaultValue("light").values("light", "dark").build();
    private static final SettingDefinition ZONE = SettingDefinition.of(SettingKey.string("t.zone"), "Z", "d")
            .defaultValue("UTC").format(StringFormat.TIME_ZONE).build();
    private static final SettingDefinition LOCALE = SettingDefinition.of(SettingKey.string("t.locale"), "L", "d")
            .defaultValue("en-GB").format(StringFormat.LOCALE).build();
    private static final SettingDefinition FLAG = SettingDefinition.of(SettingKey.bool("t.flag"), "F", "d")
            .defaultValue(true).build();
    private static final SettingDefinition HOURS = SettingDefinition.of(SettingKey.json("t.hours"), "H", "d")
            .defaultValue(Map.of("from", "22:00", "to", "06:00"))
            .property("from", SettingType.STRING, true).property("to", SettingType.STRING, true)
            .property("weekendsOnly", SettingType.BOOLEAN, false).build();

    private static void refused(SettingDefinition d, Object raw, String fragment) {
        assertThatThrownBy(() -> SettingValueRules.coerce(d, raw))
                .isInstanceOf(SettingValueRules.InvalidValue.class).hasMessageContaining(fragment);
    }

    @Test
    @DisplayName("a decimal is bounded, keeps at most its scale and is stored without trailing zeros")
    void decimals() {
        assertThat(SettingValueRules.coerce(PERCENT, 5)).isEqualTo(new BigDecimal("5"));
        assertThat(SettingValueRules.coerce(PERCENT, 2.50)).isEqualTo(new BigDecimal("2.5"));
        assertThat(SettingValueRules.coerce(PERCENT, new BigDecimal("100.00"))).isEqualTo(new BigDecimal("100"));
        refused(PERCENT, 100.01, "from 0 to 100");
        refused(PERCENT, -1, "from 0 to 100");
        refused(PERCENT, 1.234, "at most 2 decimal places");
        refused(PERCENT, "5", "must be a number");
        refused(PERCENT, Double.NaN, "must be a number");
    }

    @Test
    @DisplayName("an integer refuses a fraction, text and a value outside its range")
    void integers() {
        assertThat(SettingValueRules.coerce(SIZE, 25)).isEqualTo(25);
        assertThat(SettingValueRules.coerce(SIZE, 25.0)).isEqualTo(25);
        refused(SIZE, 25.5, "whole number");
        refused(SIZE, 9, "from 10 to 200");
        refused(SIZE, true, "whole number");
    }

    @Test
    @DisplayName("a duration is ISO-8601 within its bounds, and a Duration is taken as it is")
    void durations() {
        assertThat(SettingValueRules.coerce(WAIT, "PT45M")).isEqualTo(Duration.ofMinutes(45));
        assertThat(SettingValueRules.coerce(WAIT, Duration.ofHours(2))).isEqualTo(Duration.ofHours(2));
        assertThat(SettingValueRules.toJson(Duration.ofMinutes(90))).isEqualTo("PT1H30M");
        refused(WAIT, "45 minutes", "ISO-8601");
        refused(WAIT, "PT30S", "from PT1M to PT168H");
        refused(WAIT, "PT-5M", "ISO-8601");
        refused(WAIT, 45, "ISO-8601");
    }

    @Test
    @DisplayName("enumerations, booleans, time zones and language tags take only what they name")
    void closedSets() {
        assertThat(SettingValueRules.coerce(THEME, "dark")).isEqualTo("dark");
        refused(THEME, "Dark", "one of light, dark");
        assertThat(SettingValueRules.coerce(FLAG, false)).isEqualTo(false);
        refused(FLAG, "false", "true or false");
        assertThat(SettingValueRules.coerce(ZONE, "Asia/Kolkata")).isEqualTo("Asia/Kolkata");
        refused(ZONE, "+05:30", "time-zone id");
        refused(ZONE, "Mars/Olympus", "time-zone id");
        assertThat(SettingValueRules.coerce(LOCALE, "en-IN")).isEqualTo("en-IN");
        refused(LOCALE, "english", "language tag");
        refused(LOCALE, "", "must be text");
    }

    @Test
    @DisplayName("a JSON value carries only its named properties, each typed, the required ones present")
    void jsonSchemaLite() {
        Map<String, Object> ok = new LinkedHashMap<>();
        ok.put("to", "06:00");
        ok.put("from", "22:00");
        ok.put("weekendsOnly", null);
        assertThat(SettingValueRules.coerce(HOURS, ok)).isEqualTo(Map.of("from", "22:00", "to", "06:00"));
        refused(HOURS, Map.of("from", "22:00"), "property 'to' is required");
        refused(HOURS, Map.of("from", "22:00", "to", "06:00", "colour", "red"), "no property 'colour'");
        refused(HOURS, Map.of("from", "22:00", "to", 6), "property 'to' must be text");
        refused(HOURS, List.of("22:00"), "must be an object");
    }

    @Test
    @DisplayName("a definition that contradicts itself is refused when built")
    void definitionsAreChecked() {
        assertThatThrownBy(() -> SettingDefinition.of(SettingKey.bool("t.bad"), "B", "d").defaultValue(true)
                .range(0, 1).build()).hasMessageContaining("bounded but not a number");
        assertThatThrownBy(() -> SettingDefinition.of(SettingKey.enumeration("t.bad"), "B", "d")
                .defaultValue("x").build()).hasMessageContaining("must list values");
        assertThatThrownBy(() -> SettingDefinition.of(SettingKey.integer("t.bad"), "B", "d").build())
                .hasMessageContaining("no system default");
        assertThatThrownBy(() -> SettingKey.integer("Order.Close")).hasMessageContaining("is not a setting key");
        assertThatThrownBy(() -> SettingDefinition.of(SettingKey.integer("t.bad"), "B", "d").defaultValue(1)
                .category(com.lms.settings.api.SettingCategory.COMPLIANCE).build())
                .hasMessageContaining("compliance rule but not effective-dated");
        assertThatThrownBy(() -> SettingDefinition.of(SettingKey.integer("t.bad"), "B", "d").defaultValue(1)
                .platformOnly().alsoAt(ScopeType.ORG_UNIT).build())
                .hasMessageContaining("platform only but allows another scope");
        SettingDefinition locked = SettingDefinition.of(SettingKey.integer("t.locked"), "B", "d").defaultValue(1)
                .platformOnly().effectiveDated().build();
        assertThat(locked.tenantOverridable()).isFalse();
        assertThat(locked.scopes()).containsExactly(ScopeType.PLATFORM);
        assertThat(SIZE.userPreference()).isTrue();
        assertThat(PERCENT.userPreference()).isFalse();
    }

    @Test
    @DisplayName("the registry refuses to start on a key registered twice or a default its definition refuses")
    void registryStartUp() {
        SettingContributor twice = () -> List.of(FLAG, FLAG);
        assertThatThrownBy(() -> new SettingRegistry(List.of(twice))).hasMessageContaining("registered twice");
        SettingDefinition badDefault = SettingDefinition.of(SettingKey.integer("t.bad"), "B", "d")
                .defaultValue(5).range(10, 20).build();
        assertThatThrownBy(() -> new SettingRegistry(List.of(() -> List.of(badDefault))))
                .hasMessageContaining("system default of t.bad");
    }

    @Test
    @DisplayName("a change is refused key by key: unknown first, then the scope, then the value")
    void registryValidation() {
        SettingRegistry registry = new SettingRegistry(List.of(() -> List.of(PERCENT, SIZE, FLAG)));
        assertThat(registry.validate(ScopeType.CUSTOMER, Map.of("t.percent", 3)))
                .containsExactly(new SettingRegistry.Change("t.percent", java.util.Optional.of(new BigDecimal("3"))));

        Map<String, Object> mixed = new LinkedHashMap<>();
        mixed.put("t.nothing", 1);
        mixed.put("t.flag", true);
        mixed.put("t.percent", 101);
        assertThatThrownBy(() -> registry.validate(ScopeType.CUSTOMER, mixed))
                .isInstanceOfSatisfying(BusinessRuleViolationException.class, e -> {
                    assertThat(e.code()).isEqualTo("setting-unknown");
                    assertThat(e.errors()).containsOnlyKeys("t.nothing", "t.flag", "t.percent");
                });
        assertThatThrownBy(() -> registry.validate(ScopeType.CUSTOMER, Map.of("t.flag", true)))
                .isInstanceOfSatisfying(BusinessRuleViolationException.class,
                        e -> assertThat(e.code()).isEqualTo("setting-scope-not-allowed"));
        assertThatThrownBy(() -> registry.validate(ScopeType.USER, Map.of("t.size", 5)))
                .isInstanceOfSatisfying(BusinessRuleViolationException.class,
                        e -> assertThat(e.code()).isEqualTo("setting-invalid-value"));
        SettingDefinition dated = SettingDefinition.of(SettingKey.integer("t.dated"), "D", "d").defaultValue(1)
                .effectiveDated().alsoAt(ScopeType.PLATFORM).build();
        SettingDefinition locked = SettingDefinition.of(SettingKey.integer("t.locked"), "L", "d").defaultValue(1)
                .platformOnly().build();
        SettingRegistry withDates = new SettingRegistry(List.of(() -> List.of(dated, locked)));
        assertThatThrownBy(() -> withDates.validate(ScopeType.ORG_DEFAULT, Map.of("t.dated", 2)))
                .isInstanceOfSatisfying(BusinessRuleViolationException.class,
                        e -> assertThat(e.code()).isEqualTo("setting-effective-date-required"));
        assertThat(withDates.validate(ScopeType.ORG_DEFAULT, List.of(new SettingRegistry.Requested("t.dated",
                java.time.LocalDate.of(2030, 1, 1), 2))).get(0).label()).isEqualTo("t.dated@2030-01-01");
        assertThatThrownBy(() -> withDates.validate(ScopeType.ORG_DEFAULT, Map.of("t.locked", 2)))
                .isInstanceOfSatisfying(BusinessRuleViolationException.class,
                        e -> assertThat(e.code()).isEqualTo("setting-not-overridable"));
        assertThat(withDates.validate(ScopeType.PLATFORM, Map.of("t.locked", 2))).hasSize(1);
        Map<String, Object> removal = new LinkedHashMap<>();
        removal.put("t.size", null);
        assertThat(registry.validate(ScopeType.USER, removal).get(0).value()).isEmpty();
    }
}
