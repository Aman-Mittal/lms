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
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

import com.lms.settings.api.ScopeType;
import com.lms.settings.api.SettingContributor;
import com.lms.settings.api.SettingDefinition;
import com.lms.settings.api.SettingKey;
import com.lms.shared.error.BusinessRuleViolationException;
import org.springframework.stereotype.Component;

/**
 * Every setting the platform has, as the owning modules registered them.
 *
 * <p>Built once from the {@link SettingContributor} beans. The application
 * does not start on a key registered twice or a system default its own
 * definition refuses: both are code defects, and finding them at start-up is
 * cheaper than finding them in a tenant's data.
 */
@Component
public class SettingRegistry {

    /**
     * What {@link #validate} found acceptable: a value to set, or empty to
     * remove it, for one version of a key.
     *
     * @param effectiveFrom the version's first day; null for the undated value
     */
    public record Change(String key, LocalDate effectiveFrom, Optional<Object> value) {

        public Change(String key, Optional<Object> value) {
            this(key, null, value);
        }

        /** How the change is named in a refusal and on the history: {@code key} or {@code key@date}. */
        public String label() {
            return effectiveFrom == null ? key : key + "@" + effectiveFrom;
        }
    }

    /** One version a caller asks for; a null value removes it. */
    public record Requested(String key, LocalDate effectiveFrom, Object value) {
    }

    private final Map<String, SettingDefinition> definitions;
    private final Map<String, Object> defaults;

    public SettingRegistry(List<SettingContributor> contributors) {
        Map<String, SettingDefinition> byKey = new TreeMap<>();
        Map<String, Object> canonicalDefaults = new LinkedHashMap<>();
        for (SettingContributor contributor : contributors) {
            for (SettingDefinition d : contributor.definitions()) {
                if (byKey.putIfAbsent(d.key(), d) != null) {
                    throw new IllegalStateException("Setting " + d.key() + " is registered twice");
                }
                try {
                    canonicalDefaults.put(d.key(), SettingValueRules.coerce(d, d.defaultValue()));
                } catch (SettingValueRules.InvalidValue e) {
                    throw new IllegalStateException("The system default of " + d.key() + " "
                            + e.getMessage(), e);
                }
            }
        }
        this.definitions = byKey;
        this.defaults = canonicalDefaults;
    }

    /** In key order. */
    public Collection<SettingDefinition> all() {
        return definitions.values();
    }

    public Optional<SettingDefinition> find(String key) {
        return Optional.ofNullable(definitions.get(key));
    }

    /** The canonical system default. */
    public Object defaultOf(String key) {
        return defaults.get(key);
    }

    /**
     * The definition behind a module's key constant. A constant with no
     * registration, or registered with another type, is a defect in the module
     * that holds it, so this throws rather than answering a default.
     */
    public SettingDefinition require(SettingKey<?> key) {
        SettingDefinition d = definitions.get(key.name());
        if (d == null || d.type() != key.type()) {
            throw new IllegalStateException("Setting " + key.name() + " is not registered as "
                    + key.type());
        }
        return d;
    }

    /**
     * Checks a change to a profile of {@code scope}: every key known, allowed
     * at the scope and given a valid value (null removes the key). All problems
     * are reported together, keyed by setting; the code is the first kind
     * found, in the order unknown, scope, value.
     *
     * @throws BusinessRuleViolationException {@code setting-unknown},
     *         {@code setting-scope-not-allowed} or {@code setting-invalid-value}
     */
    public List<Change> validate(ScopeType scope, Map<String, ?> submitted) {
        List<Requested> requested = new ArrayList<>();
        submitted.forEach((key, value) -> requested.add(new Requested(key, null, value)));
        return validate(scope, requested);
    }

    /**
     * As {@link #validate(ScopeType, Map)}, for versions that may carry the day
     * they take effect. An effective-dated key needs that day on every version
     * ({@code setting-effective-date-required}); a key only the platform sets
     * is refused at a tenant's scope ({@code setting-not-overridable}). Whether
     * a day is already in force is the caller's to judge: it knows today.
     */
    public List<Change> validate(ScopeType scope, List<Requested> requested) {
        Map<String, String> unknown = new TreeMap<>();
        Map<String, String> locked = new TreeMap<>();
        Map<String, String> misplaced = new TreeMap<>();
        Map<String, String> undated = new TreeMap<>();
        Map<String, String> invalid = new TreeMap<>();
        List<Change> changes = new ArrayList<>();
        for (Requested entry : requested) {
            String key = entry.key();
            String label = entry.effectiveFrom() == null ? key : key + "@" + entry.effectiveFrom();
            SettingDefinition d = definitions.get(key);
            if (d == null) {
                unknown.put(shown(key), "is not a setting");
            } else if (!d.tenantOverridable() && scope != ScopeType.PLATFORM) {
                locked.put(key, "is set by the platform for every tenant and cannot be overridden");
            } else if (!d.allows(scope)) {
                misplaced.put(key, "cannot be set for " + scope + "; it may be set for "
                        + d.scopes());
            } else if (d.effectiveDated() && entry.effectiveFrom() == null) {
                undated.put(key, "takes effect from a date: give each version its effectiveFrom");
            } else if (entry.value() == null) {
                changes.add(new Change(key, entry.effectiveFrom(), Optional.empty()));
            } else {
                try {
                    changes.add(new Change(key, entry.effectiveFrom(),
                            Optional.of(SettingValueRules.coerce(d, entry.value()))));
                } catch (SettingValueRules.InvalidValue e) {
                    invalid.put(label, e.getMessage());
                }
            }
        }
        refuse("setting-unknown", "Unknown setting", unknown, locked, misplaced, undated, invalid);
        refuse("setting-not-overridable", "Setting kept by the platform", locked, misplaced, undated, invalid);
        refuse("setting-scope-not-allowed", "Setting not allowed at this scope", misplaced, undated, invalid);
        refuse("setting-effective-date-required", "Setting needs an effective date", undated, invalid);
        refuse("setting-invalid-value", "Invalid setting value", invalid);
        return changes;
    }

    @SafeVarargs
    private static void refuse(String code, String title, Map<String, String> first,
                               Map<String, String>... rest) {
        if (first.isEmpty()) {
            return;
        }
        Map<String, String> all = new TreeMap<>(first);
        for (Map<String, String> more : rest) {
            all.putAll(more);
        }
        throw new BusinessRuleViolationException(code, title + ": " + String.join("; ",
                all.entrySet().stream().map(e -> e.getKey() + " " + e.getValue()).toList()), all);
    }

    /** An unregistered key is echoed only when shaped like one, and never at length. */
    private static String shown(String key) {
        if (SettingKey.wellFormed(key)) {
            return key;
        }
        String clipped = key == null ? "" : key.replaceAll("[^A-Za-z0-9._-]", "?");
        return clipped.length() > 40 ? clipped.substring(0, 40) + "..." : clipped;
    }
}
