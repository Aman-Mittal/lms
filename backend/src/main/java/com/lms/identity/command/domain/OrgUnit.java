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
package com.lms.identity.command.domain;

import java.time.Instant;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.relational.core.mapping.Table;

/**
 * A node in the N-level organisational hierarchy of vision document 2.1:
 * HQ, Line of Business, Country, Zone (or Region), Branch, Operational Hub.
 *
 * <p>Visibility is strictly top-down and is evaluated as a prefix match against
 * {@link #path}. Sibling subtrees are invisible to each other; cross-branch
 * ACLs are not implemented (see DOCS/adr/0005).
 *
 * <p>Each type has a level ({@link UnitType#rank()}), and a unit must rank
 * strictly below its parent. A COUNTRY carries its ISO 3166-1 alpha-2
 * {@link #countryCode}; a zone, region, branch or hub may carry a
 * {@link #stateCode}. The rank rule is the application's, applied to every
 * create and retype: trees written before it existed are not held to it.
 */
@Table("org_unit")
public record OrgUnit(
        @Id UUID id,
        UUID tenantId,
        UUID parentId,
        String slug,
        String path,
        String name,
        UnitType unitType,
        String countryCode,
        String stateCode,
        @Version Long version,
        Instant createdAt,
        Instant updatedAt) {

    /** The organisational levels, highest first. REGION is the older name for a zone and ranks as one. */
    public enum UnitType {
        HQ(0), LOB(1), COUNTRY(2), ZONE(3), REGION(3), BRANCH(4), HUB(5);

        private final int rank;

        UnitType(int rank) {
            this.rank = rank;
        }

        /** 0 for the head office; a child's rank is strictly greater than its parent's. */
        public int rank() {
            return rank;
        }

        /** Whether a unit of this type may carry a state code. */
        public boolean carriesState() {
            return rank >= ZONE.rank;
        }
    }

    /**
     * Mirrors the {@code org_unit_slug_safe} database constraint.
     *
     * <p>Enforced in two places on purpose. The path separator is structural,
     * and {@code LIKE} gives {@code %} and {@code _} meaning, so a slug carrying
     * either could widen a visibility query rather than narrow it.
     */
    private static final Pattern SAFE_SLUG = Pattern.compile("^[a-z0-9][a-z0-9-]{0,62}$");

    public OrgUnit {
        if (slug != null && !SAFE_SLUG.matcher(slug).matches()) {
            throw new IllegalArgumentException(
                    "Organisational slug must be lowercase alphanumeric with hyphens: " + slug);
        }
    }

    /** Whether {@code slug} is one a unit may have. */
    public static boolean safeSlug(String slug) {
        return slug != null && SAFE_SLUG.matcher(slug).matches();
    }

    /** A new unit beneath {@code parent}; its path is the parent's and its own slug. */
    public static OrgUnit childOf(OrgUnit parent, String slug, String name, UnitType unitType,
                                  String countryCode, String stateCode) {
        Instant now = Instant.now();
        return new OrgUnit(UUID.randomUUID(), parent.tenantId(), parent.id(), slug, parent.childPath(slug), name,
                unitType, countryCode, stateCode, null, now, now);
    }

    /** As read at {@code expectedVersion}, renamed, retyped and recoded; the slug and path never change. */
    public OrgUnit revised(long expectedVersion, String newName, UnitType newType, String newCountry,
                           String newState) {
        return new OrgUnit(id, tenantId, parentId, slug, path, newName, newType, newCountry, newState,
                expectedVersion, createdAt, Instant.now());
    }

    /** Builds the child path for a unit nested beneath this one. */
    public String childPath(String childSlug) {
        if (!SAFE_SLUG.matcher(childSlug).matches()) {
            throw new IllegalArgumentException("Unsafe organisational slug: " + childSlug);
        }
        return path + childSlug + "/";
    }

    /** True if {@code other} is this unit or lies beneath it. */
    public boolean covers(String otherPath) {
        return otherPath != null && otherPath.startsWith(path);
    }
}
