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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Pattern;

import com.lms.settings.api.ScopeType;
import com.lms.settings.command.domain.SettingProfile;
import com.lms.settings.command.domain.SettingValue;
import com.lms.shared.audit.TransitionAudit;
import com.lms.shared.config.Json;
import com.lms.shared.error.BusinessRuleViolationException;
import com.lms.shared.error.ResourceNotFoundException;
import com.lms.shared.tenant.TenantContext;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.NullNode;

/**
 * Creating, changing and deleting setting profiles.
 *
 * <p>{@code SETTINGS_MANAGE} for a tenant's scopes but USER; a profile for an
 * organisational unit is further subject to the decision point, which refuses
 * a unit outside the caller's reach (or another tenant's). A PLATFORM profile
 * belongs to no tenant and needs {@code PLATFORM_SETTINGS_MANAGE}, the
 * platform operator's right; its rows are written only inside a transaction
 * that has said so to row-level security. A USER profile is a person's own
 * preferences: changed only by that person, through {@link #updateMine}, and
 * never through the administrative calls, which treat it as not there.
 *
 * <p>A version dated on or before today is in force and is never changed or
 * removed ({@code setting-value-in-force}); a change is a new version dated
 * today or later, and a new version may not be dated in the past
 * ({@code setting-effective-date-past}). Today is the tenant's, in its
 * {@code settings.timeZone}; for a platform profile it is UTC.
 *
 * <p>Every change is audited in the change's own transaction (resource type
 * {@value #AUDIT_TYPE}) with only the versions that changed, before and
 * after, each named {@code key} or {@code key@date}; that is the history.
 */
@Service
public class SettingProfileService {

    public static final String AUDIT_TYPE = "SettingProfile";

    static final String ORG_DEFAULT_NAME = "Organisation default";
    static final String PERSONAL_NAME = "Personal preferences";
    private static final int MAX_NAME = 100;
    private static final Pattern COUNTRY = Pattern.compile("^[A-Z]{2}$");
    private static final Pattern STATE = Pattern.compile("^[A-Z0-9]{1,3}$");

    private final SettingProfileRepository profiles;
    private final SettingRegistry registry;
    private final ScopeReferences scopes;
    private final SettingCalendar calendar;
    private final TransitionAudit audit;
    private final JdbcClient jdbc;
    private final JsonMapper json;

    public SettingProfileService(SettingProfileRepository profiles, SettingRegistry registry,
                                 ScopeReferences scopes, SettingCalendar calendar, TransitionAudit audit,
                                 JdbcClient jdbc, JsonMapper json) {
        this.profiles = profiles;
        this.registry = registry;
        this.scopes = scopes;
        this.calendar = calendar;
        this.audit = audit;
        this.jdbc = jdbc;
        this.json = json;
    }

    /** Where a new profile applies. {@code country} and {@code state} are for PLATFORM only. */
    public record Scope(ScopeType type, UUID id, String country, String state) {
    }

    /**
     * @throws BusinessRuleViolationException {@code setting-profile-scope-taken}
     *         when the scope already has a profile, {@code setting-scope-invalid}
     *         for a USER scope or a scope id or jurisdiction given or missing
     *         wrongly, and the value refusals of {@link #update}
     */
    @Transactional
    @PreAuthorize("hasAuthority(@settingScopes.authorityFor(#scope.type()))"
            + " and @abac.permitsIn('SETTINGS_MANAGE', @settingScopes.unitOf(#scope.type(), #scope.id()))")
    public UUID create(Scope scope, String name, List<SettingRegistry.Requested> requested) {
        ScopeType type = scope.type();
        if (type == ScopeType.USER) {
            throw new BusinessRuleViolationException("setting-scope-invalid",
                    "Personal preferences are set by their owner, through /api/v1/settings/me");
        }
        if (type.needsScopeId() != (scope.id() != null)) {
            throw new BusinessRuleViolationException("setting-scope-invalid", type.needsScopeId()
                    ? "A " + type + " profile needs the id of its " + type
                    : "A " + type + " profile has no scope id");
        }
        checkJurisdiction(scope);
        scopes.requireExists(type, scope.id());
        String clean = name(name, type == ScopeType.ORG_DEFAULT ? ORG_DEFAULT_NAME : null);
        SettingProfile fresh;
        if (type == ScopeType.PLATFORM) {
            platformWrite();
            if (profiles.idOfPlatform(scope.country(), scope.state()).isPresent()) {
                throw new BusinessRuleViolationException("setting-profile-scope-taken",
                        "This jurisdiction already has a platform profile; change that one");
            }
            fresh = SettingProfile.platform(scope.country(), scope.state(), clean);
        } else {
            UUID tenantId = TenantContext.requireTenantId();
            if (profiles.idOfScope(tenantId, type.name(), scope.id()).isPresent()) {
                throw new BusinessRuleViolationException("setting-profile-scope-taken",
                        "This scope already has a profile; change that one");
            }
            fresh = SettingProfile.of(tenantId, type, scope.id(), clean);
        }
        List<SettingRegistry.Change> changes = registry.validate(type, requested);
        return apply(fresh, clean, changes, true).id();
    }

    /**
     * Renames the profile (when {@code name} is given) and sets or, for a null
     * value, removes each version requested; versions not mentioned are kept.
     *
     * @return the profile's new version, its next ETag
     */
    @Transactional
    @PreAuthorize("hasAuthority(@settingScopes.authorityForProfile(#id))"
            + " and @abac.permitsIn('SETTINGS_MANAGE', @settingScopes.unitOfProfile(#id))")
    public long update(UUID id, long expectedVersion, String name, List<SettingRegistry.Requested> requested) {
        SettingProfile profile = administered(id, expectedVersion);
        List<SettingRegistry.Change> changes = registry.validate(profile.scopeType(), requested);
        String clean = name == null ? profile.name() : name(name, null);
        return apply(profile.atVersion(expectedVersion), clean, changes, false).version();
    }

    /**
     * Deletes a profile and its values, so its scope falls back to the next
     * profile out. The organisation default is not deleted: clear its values
     * instead, so its history stays on one record. Nor is a profile holding a
     * version in force: what was in force stays on record.
     */
    @Transactional
    @PreAuthorize("hasAuthority(@settingScopes.authorityForProfile(#id))"
            + " and @abac.permitsIn('SETTINGS_MANAGE', @settingScopes.unitOfProfile(#id))")
    public void delete(UUID id, long expectedVersion) {
        SettingProfile profile = administered(id, expectedVersion);
        if (profile.scopeType() == ScopeType.ORG_DEFAULT) {
            throw new BusinessRuleViolationException("setting-profile-org-default",
                    "The organisation default cannot be deleted; remove its values instead");
        }
        LocalDate today = today(profile);
        Map<String, String> inForce = new TreeMap<>();
        for (SettingValue v : profile.values()) {
            if (v.effectiveFrom() != null && !v.effectiveFrom().isAfter(today)) {
                inForce.put(label(v.settingKey(), v.effectiveFrom()), "has been in force since " + v.effectiveFrom());
            }
        }
        if (!inForce.isEmpty()) {
            throw new BusinessRuleViolationException("setting-value-in-force",
                    "A profile holding a version in force is kept: " + String.join(", ", inForce.keySet()),
                    inForce);
        }
        if (profile.scopeType() == ScopeType.PLATFORM) {
            platformWrite();
        }
        Map<String, JsonNode> before = snapshot(profile);
        before.put("name", json.getNodeFactory().stringNode(profile.name()));
        profiles.delete(profile.atVersion(expectedVersion));
        audit.record("SETTING_PROFILE_DELETED", AUDIT_TYPE, id, write(before), null);
    }

    /**
     * Sets the caller's own preferences: only keys a user may set for
     * themself. {@code expectedVersion} is the ETag of the caller's last read,
     * 0 when they had no preferences yet.
     *
     * @return the new version
     */
    @Transactional
    public long updateMine(long expectedVersion, Map<String, Object> values) {
        UUID userId = ScopeReferences.currentUserId();
        if (userId == null) {
            throw new AccessDeniedException("Only a signed-in user has preferences");
        }
        UUID tenantId = TenantContext.requireTenantId();
        List<SettingRegistry.Change> changes = registry.validate(ScopeType.USER,
                values == null ? Map.of() : values);
        Optional<SettingProfile> mine = profiles.idOfScope(tenantId, ScopeType.USER.name(), userId)
                .flatMap(profiles::findById);
        long current = mine.map(SettingProfile::version).orElse(0L);
        if (current != expectedVersion) {
            throw new OptimisticLockingFailureException("Your preferences have changed since version "
                    + expectedVersion);
        }
        SettingProfile profile = mine.map(p -> p.atVersion(expectedVersion))
                .orElseGet(() -> SettingProfile.of(tenantId, ScopeType.USER, userId, PERSONAL_NAME));
        return apply(profile, profile.name(), changes, mine.isEmpty()).version();
    }

    /** The port's write: organisation default values, created on first use, no version asked. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void setOrgDefaults(Map<String, Object> values) {
        UUID tenantId = TenantContext.requireTenantId();
        List<SettingRegistry.Change> changes = registry.validate(ScopeType.ORG_DEFAULT, values);
        Optional<SettingProfile> existing = profiles.idOfScope(tenantId, ScopeType.ORG_DEFAULT.name(), null)
                .flatMap(profiles::findById);
        SettingProfile profile = existing.orElseGet(
                () -> SettingProfile.of(tenantId, ScopeType.ORG_DEFAULT, null, ORG_DEFAULT_NAME));
        apply(profile, profile.name(), changes, existing.isEmpty());
    }

    /** A profile the administrative calls may touch: it exists, is not a user's, and is at the version given. */
    private SettingProfile administered(UUID id, long expectedVersion) {
        SettingProfile profile = profiles.findById(id)
                .filter(p -> p.scopeType() != ScopeType.USER)
                .orElseThrow(() -> new ResourceNotFoundException("SettingProfile", id));
        if (profile.version() != expectedVersion) {
            throw new OptimisticLockingFailureException("Setting profile " + id
                    + " has changed since version " + expectedVersion);
        }
        return profile;
    }

    private SettingProfile apply(SettingProfile profile, String name,
                                 List<SettingRegistry.Change> changes, boolean creating) {
        LocalDate today = today(profile);
        Map<String, SettingValue> current = new TreeMap<>();
        profile.values().forEach(v -> current.put(label(v.settingKey(), v.effectiveFrom()), v));
        Map<String, SettingValue> next = new TreeMap<>(current);
        Map<String, String> inForce = new TreeMap<>();
        Map<String, String> past = new TreeMap<>();
        for (SettingRegistry.Change change : changes) {
            String label = change.label();
            SettingValue existing = current.get(label);
            LocalDate from = change.effectiveFrom();
            if (from != null && existing != null && !from.isAfter(today)) {
                inForce.put(label, "has been in force since " + from + "; add a version dated today or later");
                continue;
            }
            if (from != null && existing == null && from.isBefore(today)) {
                past.put(label, "cannot take effect in the past; date it " + today + " or later");
                continue;
            }
            if (change.value().isEmpty()) {
                next.remove(label);
            } else {
                next.put(label, new SettingValue(profile.tenantId(), change.key(), from,
                        Json.of(json.writeValueAsString(SettingValueRules.toJson(change.value().get())))));
            }
        }
        if (!inForce.isEmpty()) {
            throw new BusinessRuleViolationException("setting-value-in-force",
                    "A version in force is never changed: " + String.join(", ", inForce.keySet()), inForce);
        }
        if (!past.isEmpty()) {
            throw new BusinessRuleViolationException("setting-effective-date-past",
                    "A new version takes effect today or later: " + String.join(", ", past.keySet()), past);
        }
        Map<String, JsonNode> before = new LinkedHashMap<>();
        Map<String, JsonNode> after = new LinkedHashMap<>();
        TreeSet<String> labels = new TreeSet<>(current.keySet());
        labels.addAll(next.keySet());
        for (String label : labels) {
            JsonNode was = node(current.get(label));
            JsonNode now = node(next.get(label));
            if (!Objects.equals(was, now)) {
                before.put(label, was);
                after.put(label, now);
            }
        }
        if (!name.equals(profile.name())) {
            before.put("name", json.getNodeFactory().stringNode(profile.name()));
            after.put("name", json.getNodeFactory().stringNode(name));
        }
        if (!creating && after.isEmpty()) {
            return profile;
        }
        if (profile.scopeType() == ScopeType.PLATFORM) {
            platformWrite();
        }
        SettingProfile saved = profiles.save(profile.with(name, new ArrayList<>(next.values())));
        if (creating) {
            after.put("scopeType", json.getNodeFactory().stringNode(profile.scopeType().name()));
            after.put("name", json.getNodeFactory().stringNode(name));
            audit.record("SETTING_PROFILE_CREATED", AUDIT_TYPE, saved.id(), null, write(after));
        } else {
            audit.record("SETTING_PROFILE_UPDATED", AUDIT_TYPE, saved.id(), write(before), write(after));
        }
        return saved;
    }

    private LocalDate today(SettingProfile profile) {
        return profile.scopeType() == ScopeType.PLATFORM ? calendar.platformToday() : calendar.today();
    }

    /**
     * Tells row-level security that this transaction writes platform rows
     * (those with no tenant). Only this service says so, and only after the
     * caller has been checked for {@code PLATFORM_SETTINGS_MANAGE}; the flag
     * ends with the transaction.
     */
    private void platformWrite() {
        jdbc.sql("SELECT set_config('app.platform_write', 'on', true)").query(String.class).single();
    }

    private static void checkJurisdiction(Scope scope) {
        boolean platform = scope.type() == ScopeType.PLATFORM;
        if (!platform && (scope.country() != null || scope.state() != null)) {
            throw new BusinessRuleViolationException("setting-scope-invalid",
                    "Only a platform profile is kept per jurisdiction");
        }
        if (scope.country() != null && !COUNTRY.matcher(scope.country()).matches()
                || scope.state() != null && (scope.country() == null || !STATE.matcher(scope.state()).matches())) {
            throw new BusinessRuleViolationException("setting-scope-invalid",
                    "A jurisdiction is an ISO 3166-1 alpha-2 country, optionally with a state code of 1 to 3"
                            + " capital letters or digits");
        }
    }

    static String label(String key, LocalDate effectiveFrom) {
        return effectiveFrom == null ? key : key + "@" + effectiveFrom;
    }

    private Map<String, JsonNode> snapshot(SettingProfile profile) {
        Map<String, JsonNode> out = new TreeMap<>();
        profile.values().forEach(v -> out.put(label(v.settingKey(), v.effectiveFrom()), node(v)));
        return out;
    }

    private JsonNode node(SettingValue value) {
        return value == null ? NullNode.getInstance() : json.readTree(value.value().value());
    }

    private Json write(Map<String, JsonNode> snapshot) {
        return Json.of(json.writeValueAsString(snapshot));
    }

    private static String name(String requested, String fallback) {
        String trimmed = requested == null ? null : requested.strip();
        if (trimmed == null || trimmed.isEmpty()) {
            if (fallback != null) {
                return fallback;
            }
            throw new BusinessRuleViolationException("setting-profile-name-required",
                    "A profile needs a name of 1 to " + MAX_NAME + " characters");
        }
        if (trimmed.length() > MAX_NAME) {
            throw new BusinessRuleViolationException("setting-profile-name-required",
                    "A profile needs a name of 1 to " + MAX_NAME + " characters");
        }
        return trimmed;
    }
}
