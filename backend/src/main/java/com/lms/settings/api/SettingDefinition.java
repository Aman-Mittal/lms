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
package com.lms.settings.api;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Everything the platform knows about one setting: its type, its system
 * default, where it may be set, and what a value must satisfy.
 *
 * <p>Built with {@link #of}; {@link Builder#build} refuses a definition that
 * contradicts itself (bounds on a type that has none, a personal preference
 * that may not be set per user). Whether the default satisfies the bounds is
 * checked when the registry starts, by the same rules a submitted value meets.
 *
 * @param scopes         the scope types the key may be set at, in fold order;
 *                       always includes ORG_DEFAULT
 * @param min            INTEGER and DECIMAL: the least value; null for none
 * @param max            INTEGER and DECIMAL: the greatest value; null for none
 * @param scale          DECIMAL: the most decimal places
 * @param minDuration    DURATION: the shortest; null for none
 * @param maxDuration    DURATION: the longest; null for none
 * @param maxLength      STRING: the most characters
 * @param format         STRING: a further check on the text
 * @param allowedValues  ENUM: the values, in the order a screen offers them
 * @param properties     JSON: each property name and its type (BOOLEAN,
 *                       INTEGER, DECIMAL or STRING); no others are accepted
 * @param required       JSON: the properties a value must carry
 * @param userPreference whether a user sets it for themself at USER scope
 * @param category       what it is about; COMPLIANCE implies {@code effectiveDated}
 * @param effectiveDated every value carries the date it takes effect from, and
 *                       a value already in force is never changed: a change
 *                       is a new version dated today or later
 * @param tenantOverridable false when only the platform operator sets it
 *                       (its only scope is PLATFORM); tenants then read the
 *                       platform's value and cannot override it
 */
public record SettingDefinition(
        String key,
        SettingType type,
        String label,
        String description,
        Object defaultValue,
        List<ScopeType> scopes,
        BigDecimal min,
        BigDecimal max,
        Integer scale,
        Duration minDuration,
        Duration maxDuration,
        Integer maxLength,
        StringFormat format,
        List<String> allowedValues,
        Map<String, SettingType> properties,
        Set<String> required,
        boolean userPreference,
        SettingCategory category,
        boolean effectiveDated,
        boolean tenantOverridable) {

    /** STRING values longer than this are refused whatever the definition says. */
    public static final int MAX_STRING = 500;

    public static Builder of(SettingKey<?> key, String label, String description) {
        return new Builder(key, label, description);
    }

    public boolean allows(ScopeType scope) {
        return scopes.contains(scope);
    }

    public static final class Builder {

        private final SettingKey<?> key;
        private final String label;
        private final String description;
        private Object defaultValue;
        private final Set<ScopeType> scopes = EnumSet.of(ScopeType.ORG_DEFAULT);
        private BigDecimal min;
        private BigDecimal max;
        private Integer scale;
        private Duration minDuration;
        private Duration maxDuration;
        private Integer maxLength;
        private StringFormat format;
        private final List<String> allowedValues = new ArrayList<>();
        private final Map<String, SettingType> properties = new LinkedHashMap<>();
        private final Set<String> required = new LinkedHashSet<>();
        private SettingCategory category = SettingCategory.OPERATIONS;
        private boolean effectiveDated;
        private boolean platformOnly;

        private Builder(SettingKey<?> key, String label, String description) {
            this.key = Objects.requireNonNull(key, "key");
            this.label = Objects.requireNonNull(label, "label");
            this.description = Objects.requireNonNull(description, "description");
        }

        public Builder defaultValue(Object value) {
            this.defaultValue = value;
            return this;
        }

        /** Scope types besides ORG_DEFAULT, which every setting has. */
        public Builder alsoAt(ScopeType... more) {
            scopes.addAll(List.of(more));
            return this;
        }

        public Builder category(SettingCategory what) {
            this.category = what;
            return this;
        }

        /** Every value carries the date it takes effect from; one in force is never edited. */
        public Builder effectiveDated() {
            this.effectiveDated = true;
            return this;
        }

        /** Set by the platform operator only, at PLATFORM scope; no tenant may override it. */
        public Builder platformOnly() {
            this.platformOnly = true;
            scopes.clear();
            scopes.add(ScopeType.PLATFORM);
            return this;
        }

        public Builder range(Number least, Number greatest) {
            this.min = least == null ? null : new BigDecimal(least.toString());
            this.max = greatest == null ? null : new BigDecimal(greatest.toString());
            return this;
        }

        public Builder scale(int places) {
            this.scale = places;
            return this;
        }

        public Builder between(Duration shortest, Duration longest) {
            this.minDuration = shortest;
            this.maxDuration = longest;
            return this;
        }

        public Builder maxLength(int characters) {
            this.maxLength = characters;
            return this;
        }

        public Builder format(StringFormat stringFormat) {
            this.format = stringFormat;
            return this;
        }

        public Builder values(String... values) {
            allowedValues.addAll(List.of(values));
            return this;
        }

        public Builder property(String name, SettingType propertyType, boolean isRequired) {
            properties.put(name, propertyType);
            if (isRequired) {
                required.add(name);
            }
            return this;
        }

        public SettingDefinition build() {
            SettingType type = key.type();
            refuseUnless(defaultValue != null, "has no system default");
            refuseUnless((min == null && max == null)
                    || type == SettingType.INTEGER || type == SettingType.DECIMAL, "is bounded but not a number");
            refuseUnless(scale == null || type == SettingType.DECIMAL, "has a scale but is not a decimal");
            refuseUnless((minDuration == null && maxDuration == null) || type == SettingType.DURATION,
                    "has duration bounds but is not a duration");
            refuseUnless((maxLength == null && format == null) || type == SettingType.STRING,
                    "has a length or format but is not text");
            refuseUnless(allowedValues.isEmpty() == (type != SettingType.ENUM),
                    "must list values exactly when it is an enumeration");
            refuseUnless(properties.isEmpty() == (type != SettingType.JSON),
                    "must name its properties exactly when it is JSON");
            refuseUnless(properties.keySet().containsAll(required), "requires a property it does not name");
            refuseUnless(properties.values().stream().allMatch(t -> t == SettingType.BOOLEAN
                            || t == SettingType.INTEGER || t == SettingType.DECIMAL || t == SettingType.STRING),
                    "has a JSON property that is not a boolean, number or text");
            refuseUnless(maxLength == null || (maxLength > 0 && maxLength <= MAX_STRING),
                    "has a length outside 1.." + MAX_STRING);
            refuseUnless(category != SettingCategory.COMPLIANCE || effectiveDated,
                    "is a compliance rule but not effective-dated");
            refuseUnless(!platformOnly || scopes.equals(EnumSet.of(ScopeType.PLATFORM)),
                    "is set by the platform only but allows another scope");
            refuseUnless(!(effectiveDated && scopes.contains(ScopeType.USER)),
                    "is effective-dated, which a personal preference never is");
            List<ScopeType> ordered = scopes.stream().sorted().toList();
            return new SettingDefinition(key.name(), type, label, description, defaultValue, ordered,
                    min, max, type == SettingType.DECIMAL && scale == null ? Integer.valueOf(4) : scale,
                    minDuration, maxDuration,
                    type == SettingType.STRING && maxLength == null ? Integer.valueOf(200) : maxLength,
                    type == SettingType.STRING && format == null ? StringFormat.TEXT : format,
                    List.copyOf(allowedValues), Collections.unmodifiableMap(new LinkedHashMap<>(properties)),
                    Set.copyOf(required), scopes.contains(ScopeType.USER), category, effectiveDated, !platformOnly);
        }

        private void refuseUnless(boolean ok, String why) {
            if (!ok) {
                throw new IllegalStateException("Setting " + key.name() + " " + why);
            }
        }
    }
}
