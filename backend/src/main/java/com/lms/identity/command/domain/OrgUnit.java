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
import org.springframework.data.relational.core.mapping.Table;

/**
 * A node in the N-level organisational hierarchy of vision document 2.1:
 * HQ, Line of Business, Region, Branch, Operational Hub.
 *
 * <p>Visibility is strictly top-down and is evaluated as a prefix match against
 * {@link #path}. Sibling subtrees are invisible to each other; cross-branch
 * ACLs are not implemented (see DOCS/adr/0005).
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
        Instant createdAt,
        Instant updatedAt) {

    public enum UnitType {
        HQ, LOB, REGION, BRANCH, HUB
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
