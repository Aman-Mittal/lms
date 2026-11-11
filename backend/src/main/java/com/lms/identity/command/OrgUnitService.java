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
package com.lms.identity.command;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

import com.lms.identity.command.domain.OrgUnit;
import com.lms.identity.command.domain.OrgUnit.UnitType;
import com.lms.shared.audit.AuditTrail;
import com.lms.shared.config.Json;
import com.lms.shared.error.BusinessRuleViolationException;
import com.lms.shared.error.ResourceNotFoundException;
import com.lms.shared.tenant.TenantContext;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creating organisational units beneath an existing one, and renaming,
 * retyping and recoding them. A unit is never moved and its slug never
 * changes, so its materialised path -- and every path beneath it -- stays as
 * it was written.
 *
 * <p>Every write is held to the level order (a child ranks strictly below its
 * parent and above its children), audited in the write's own transaction
 * ({@code OrgUnit}), and confined to the caller's own subtree.
 */
@Service
public class OrgUnitService {

    public static final String AUDIT_TYPE = "OrgUnit";
    public static final String MANAGE = "ORG_UNIT_MANAGE";
    private static final int MAX_NAME = 100;
    private static final Pattern COUNTRY = Pattern.compile("^[A-Z]{2}$");
    private static final Pattern STATE = Pattern.compile("^[A-Z0-9]{1,3}$");

    private final OrgUnitRepository units;
    private final AuditTrail audit;

    public OrgUnitService(OrgUnitRepository units, AuditTrail audit) {
        this.units = units;
        this.audit = audit;
    }

    /** What a unit is called and what it is; the codes are blank or null when absent. */
    public record Draft(String name, UnitType unitType, String countryCode, String stateCode) {
    }

    /**
     * Adds a unit beneath {@code parentId}.
     *
     * @throws ResourceNotFoundException      when the parent is not the tenant's or
     *                                        lies outside the caller's subtree
     * @throws BusinessRuleViolationException {@code org-unit-level-invalid},
     *         {@code org-unit-slug-invalid}, {@code org-unit-slug-taken},
     *         {@code org-unit-name-required}, {@code org-unit-country-invalid},
     *         {@code org-unit-state-invalid}
     */
    @Transactional
    @PreAuthorize("hasAuthority('ORG_UNIT_MANAGE') and @abac.permitsIn('ORG_UNIT_MANAGE', #parentId)")
    public UUID create(UUID parentId, String slug, Draft draft) {
        OrgUnit parent = visible(parentId);
        String cleanSlug = slug == null ? "" : slug.strip();
        if (!OrgUnit.safeSlug(cleanSlug)) {
            throw new BusinessRuleViolationException("org-unit-slug-invalid",
                    "A slug is 1 to 63 lowercase letters, digits or hyphens, starting with a letter or digit");
        }
        Draft clean = check(draft);
        requireBelow(parent, clean.unitType());
        if (units.findByPath(parent.tenantId(), parent.childPath(cleanSlug)).isPresent()) {
            throw new BusinessRuleViolationException("org-unit-slug-taken",
                    parent.name() + " already has a unit with slug " + cleanSlug);
        }
        OrgUnit created = units.save(OrgUnit.childOf(parent, cleanSlug, clean.name(), clean.unitType(),
                clean.countryCode(), clean.stateCode()));
        audit.recordAtomically("ORG_UNIT_CREATED", AUDIT_TYPE, created.id(), null, snapshot(created));
        return created.id();
    }

    /**
     * Renames, retypes and recodes a unit, at {@code expectedVersion}.
     *
     * @return the unit's new version, its next ETag
     */
    @Transactional
    @PreAuthorize("hasAuthority('ORG_UNIT_MANAGE') and @abac.permitsIn('ORG_UNIT_MANAGE', #id)")
    public long update(UUID id, long expectedVersion, Draft draft) {
        OrgUnit unit = visible(id);
        if (unit.version() == null || unit.version() != expectedVersion) {
            throw new OptimisticLockingFailureException("Organisational unit " + id
                    + " has changed since version " + expectedVersion);
        }
        Draft clean = check(draft);
        if (clean.unitType() != unit.unitType()) {
            if (unit.parentId() != null) {
                units.findById(unit.parentId()).ifPresent(parent -> requireBelow(parent, clean.unitType()));
            }
            List<OrgUnit> children = units.findSubtree(unit.tenantId(), unit.path()).stream()
                    .filter(u -> unit.id().equals(u.parentId())).toList();
            for (OrgUnit child : children) {
                if (child.unitType().rank() <= clean.unitType().rank()) {
                    throw new BusinessRuleViolationException("org-unit-level-invalid",
                            "A " + clean.unitType() + " cannot hold " + child.name() + ", a "
                                    + child.unitType() + ": a unit ranks strictly below its parent");
                }
            }
        }
        if (clean.name().equals(unit.name()) && clean.unitType() == unit.unitType()
                && Objects.equals(clean.countryCode(), unit.countryCode())
                && Objects.equals(clean.stateCode(), unit.stateCode())) {
            return expectedVersion;
        }
        OrgUnit saved = units.save(unit.revised(expectedVersion, clean.name(), clean.unitType(),
                clean.countryCode(), clean.stateCode()));
        audit.recordAtomically("ORG_UNIT_UPDATED", AUDIT_TYPE, saved.id(), snapshot(unit), snapshot(saved));
        return saved.version();
    }

    /** A unit of the tenant's, at or beneath the caller's own; otherwise as if it did not exist. */
    private OrgUnit visible(UUID id) {
        String callerPath = TenantContext.requireOrgPath();
        return units.findById(id)
                .filter(u -> u.path().startsWith(callerPath))
                .orElseThrow(() -> new ResourceNotFoundException("OrgUnit", id));
    }

    private static void requireBelow(OrgUnit parent, UnitType type) {
        if (type.rank() <= parent.unitType().rank()) {
            throw new BusinessRuleViolationException("org-unit-level-invalid",
                    "A " + type + " cannot sit beneath " + parent.name() + ", a " + parent.unitType()
                            + ": the levels run HQ, LOB, COUNTRY, ZONE or REGION, BRANCH, HUB");
        }
    }

    private static Draft check(Draft draft) {
        if (draft == null || draft.unitType() == null) {
            throw new BusinessRuleViolationException("org-unit-level-invalid", "A unit needs a type");
        }
        String name = draft.name() == null ? "" : draft.name().strip();
        if (name.isEmpty() || name.length() > MAX_NAME) {
            throw new BusinessRuleViolationException("org-unit-name-required",
                    "A unit needs a name of 1 to " + MAX_NAME + " characters");
        }
        String country = code(draft.countryCode());
        String state = code(draft.stateCode());
        UnitType type = draft.unitType();
        if (type == UnitType.COUNTRY && (country == null || !COUNTRY.matcher(country).matches())) {
            throw new BusinessRuleViolationException("org-unit-country-invalid",
                    "A COUNTRY unit needs its ISO 3166-1 alpha-2 country code, such as IN");
        }
        if (type != UnitType.COUNTRY && country != null) {
            throw new BusinessRuleViolationException("org-unit-country-invalid",
                    "Only a COUNTRY unit carries a country code");
        }
        if (state != null && (!type.carriesState() || !STATE.matcher(state).matches())) {
            throw new BusinessRuleViolationException("org-unit-state-invalid", type.carriesState()
                    ? "A state code is 1 to 3 capital letters or digits"
                    : "Only a ZONE, REGION, BRANCH or HUB carries a state code");
        }
        return new Draft(name, type, country, state);
    }

    private static String code(String raw) {
        return raw == null || raw.isBlank() ? null : raw.strip().toUpperCase(Locale.ROOT);
    }

    private static Json snapshot(OrgUnit u) {
        return Json.object("name", u.name(), "unitType", u.unitType().name(), "path", u.path(),
                "countryCode", u.countryCode(), "stateCode", u.stateCode());
    }
}
