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
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.lms.settings.api.Resolved;
import com.lms.settings.api.ScopeType;
import com.lms.settings.api.SettingDefinition;
import com.lms.settings.api.SettingSource;

/**
 * The fold itself, with no I/O: from the values the applicable profiles hold
 * to one effective value per key.
 *
 * <p>Two steps. Within one profile, of a key's versions the latest one in
 * effect on {@code asOf} applies (an undated version counts as in effect
 * since always; one dated after {@code asOf} does not exist yet). Across
 * profiles, only those of a scope type the key allows are considered, and the
 * one with the highest {@link ScopeType#precedence()} wins; among
 * organisational units the deepest, among platform profiles the most specific
 * jurisdiction. With none, the system default answers. A key is taken whole
 * from one version: there is no merging inside a value.
 */
public final class SettingFold {

    private SettingFold() {
    }

    /**
     * One version of a value one applicable profile holds.
     *
     * @param depth         how specific the profile is within its scope type: an
     *                      ORG_UNIT's depth, a PLATFORM profile's jurisdiction (0
     *                      global, 1 country, 2 state), a COMBINATION's unit
     *                      depth (0 with no unit); 0 otherwise
     * @param effectiveFrom the version's first day; null for an undated value
     * @param value         already canonical
     * @param dimensions    a COMBINATION's number of conditions; 0 otherwise
     * @param priority      a COMBINATION's priority; 0 otherwise
     * @param label         the provenance in words, see {@link ScopeLabels}; null
     *                      to leave it to the caller
     */
    public record Candidate(UUID profileId, ScopeType scopeType, UUID scopeId, String profileName,
                            int depth, String key, LocalDate effectiveFrom, Object value,
                            int dimensions, int priority, String label) {

        public Candidate(UUID profileId, ScopeType scopeType, UUID scopeId, String profileName,
                         int depth, String key, LocalDate effectiveFrom, Object value) {
            this(profileId, scopeType, scopeId, profileName, depth, key, effectiveFrom, value, 0, 0, null);
        }

        public Candidate(UUID profileId, ScopeType scopeType, UUID scopeId, String profileName,
                         int depth, String key, Object value) {
            this(profileId, scopeType, scopeId, profileName, depth, key, null, value);
        }
    }

    /**
     * Scope type first; within COMBINATION more dimensions, then the deeper
     * unit, then the higher priority; within ORG_UNIT and PLATFORM the deeper
     * one. The profile id settles what nothing else does, so the answer is
     * at least stable; the write path refuses combinations it would have to
     * settle.
     */
    private static final Comparator<Candidate> SPECIFICITY = Comparator
            .comparingInt((Candidate c) -> c.scopeType().precedence())
            .thenComparingInt(Candidate::dimensions)
            .thenComparingInt(Candidate::depth)
            .thenComparingInt(Candidate::priority)
            .thenComparing(Candidate::profileId);

    private static final Comparator<LocalDate> UNDATED_FIRST = Comparator.nullsFirst(Comparator.naturalOrder());

    /** As {@link #fold(List, Map, List, LocalDate)} with every version in effect. */
    public static Map<String, Resolved<Object>> fold(List<SettingDefinition> wanted,
                                                     Map<String, Object> defaults,
                                                     List<Candidate> candidates) {
        return fold(wanted, defaults, candidates, LocalDate.MAX);
    }

    /**
     * @param wanted     the keys to resolve, in the order to answer them
     * @param defaults   each key's canonical system default
     * @param candidates every version the applicable profiles hold, in any order
     * @param asOf       the day to judge by
     */
    public static Map<String, Resolved<Object>> fold(List<SettingDefinition> wanted,
                                                     Map<String, Object> defaults,
                                                     List<Candidate> candidates, LocalDate asOf) {
        Map<String, SettingDefinition> byKey = new LinkedHashMap<>();
        wanted.forEach(d -> byKey.put(d.key(), d));
        // Step one: per profile and key, the version in effect on asOf.
        Map<String, Candidate> inEffect = new LinkedHashMap<>();
        for (Candidate c : candidates) {
            SettingDefinition d = byKey.get(c.key());
            if (d == null || !d.allows(c.scopeType())
                    || (c.effectiveFrom() != null && c.effectiveFrom().isAfter(asOf))) {
                continue;
            }
            inEffect.merge(c.profileId() + "|" + c.key(), c,
                    (a, b) -> UNDATED_FIRST.compare(a.effectiveFrom(), b.effectiveFrom()) >= 0 ? a : b);
        }
        // Step two: per key, the most specific profile.
        Map<String, Candidate> best = new LinkedHashMap<>();
        for (Candidate c : inEffect.values()) {
            best.merge(c.key(), c, (a, b) -> SPECIFICITY.compare(a, b) >= 0 ? a : b);
        }
        Map<String, Resolved<Object>> out = new LinkedHashMap<>();
        for (SettingDefinition d : wanted) {
            Candidate c = best.get(d.key());
            out.put(d.key(), c == null
                    ? new Resolved<>(d.key(), defaults.get(d.key()), SettingSource.SYSTEM_DEFAULT)
                    : new Resolved<>(d.key(), c.value(), new SettingSource(c.scopeType(), c.scopeId(),
                            c.profileId(), c.profileName(), c.effectiveFrom(), c.label() != null ? c.label()
                                    : ScopeLabels.source(c.scopeType(), c.profileName(), null, null, null, null,
                                            null))));
        }
        return out;
    }
}
