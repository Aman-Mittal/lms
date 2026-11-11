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
package com.lms.settings.web;

import java.math.BigDecimal;
import java.net.URI;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.lms.settings.api.Resolved;
import com.lms.settings.api.ScopeType;
import com.lms.settings.api.SettingCategory;
import com.lms.settings.api.SettingContext;
import com.lms.settings.api.SettingDefinition;
import com.lms.settings.api.SettingSource;
import com.lms.settings.api.SettingType;
import com.lms.settings.api.StringFormat;
import com.lms.settings.command.ScopeReferences;
import com.lms.settings.command.SettingProfileService;
import com.lms.settings.command.SettingRegistry;
import com.lms.settings.command.SettingValueRules;
import com.lms.settings.query.EffectiveSettingsQuery;
import com.lms.settings.query.SettingProfileQueryService;
import com.lms.settings.query.SettingProfileView;
import com.lms.shared.audit.AuditHistoryQueryService;
import com.lms.shared.audit.HistoryEntryView;
import com.lms.shared.audit.HistoryViewer;
import com.lms.shared.error.ResourceNotFoundException;
import com.lms.shared.query.Slice;
import com.lms.shared.tenant.TenantContext;
import com.lms.shared.web.IdResponse;
import com.lms.shared.web.IfMatch;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Setting definitions, profiles, effective values and the caller's own
 * preferences.
 *
 * <p>Profile edits carry {@code If-Match}, as order and load edits do: the
 * {@code ETag} of the last read, quoted or bare. A missing header is 400, a
 * stale one 409 {@code concurrent-modification}. The caller's preferences
 * take the {@code ETag} of {@code GET /settings/me}, which is "0" before
 * anything has been saved.
 */
@RestController
@RequestMapping("/api/v1/settings")
public class SettingsController {

    private static final String PROFILE_VERSION = "the profile's version";
    private static final String MY_VERSION = "the version of your preferences";

    private final SettingRegistry registry;
    private final SettingProfileService profiles;
    private final SettingProfileQueryService profileQueries;
    private final EffectiveSettingsQuery effective;
    private final AuditHistoryQueryService history;

    public SettingsController(SettingRegistry registry, SettingProfileService profiles,
                              SettingProfileQueryService profileQueries, EffectiveSettingsQuery effective,
                              AuditHistoryQueryService history) {
        this.registry = registry;
        this.profiles = profiles;
        this.profileQueries = profileQueries;
        this.effective = effective;
        this.history = history;
    }

    /** Every setting the platform has, in key order. Code, not tenant data: any signed-in caller may read it. */
    @GetMapping("/definitions")
    public Slice<DefinitionView> definitions() {
        return new Slice<>(registry.all().stream().map(this::definition).toList(), null, false);
    }

    /** Every setting's effective value for a context, and where it came from. */
    @GetMapping("/effective")
    @PreAuthorize("hasAnyAuthority('SETTINGS_READ', 'SETTINGS_MANAGE')")
    public EffectiveView effective(@RequestParam(required = false) UUID orgUnitId,
                                   @RequestParam(required = false) UUID customerId,
                                   @RequestParam(required = false) UUID vehicleId,
                                   @RequestParam(required = false) UUID vendorId,
                                   @RequestParam(required = false) LocalDate asOf,
                                   @RequestParam(required = false) String country,
                                   @RequestParam(required = false) String state) {
        SettingContext context = SettingContext.ofOrgUnit(orgUnitId).withCustomer(customerId)
                .withVehicle(vehicleId).withVendor(vendorId).asOf(asOf).inJurisdiction(blankToNull(country), blankToNull(state));
        return new EffectiveView(entries(registry.all(), context));
    }

    @GetMapping("/profiles")
    public Slice<SettingProfileView> list(@RequestParam(required = false) ScopeType scopeType,
                                          @RequestParam(required = false) String q,
                                          @RequestParam(required = false) String cursor,
                                          @RequestParam(required = false) Integer limit) {
        return profileQueries.list(scopeType, q, cursor, limit);
    }

    /** The organisational units a profile can be scoped to: the caller's own and those beneath it. */
    @GetMapping("/org-units")
    public Slice<SettingProfileQueryService.OrgUnitChoice> orgUnits(@RequestParam(required = false) String q,
                                                                    @RequestParam(required = false) String cursor,
                                                                    @RequestParam(required = false) Integer limit) {
        return profileQueries.orgUnits(q, cursor, limit);
    }

    @PostMapping("/profiles")
    public ResponseEntity<IdResponse> create(@Valid @RequestBody CreateProfileRequest request) {
        UUID id = profiles.create(new SettingProfileService.Scope(request.scopeType(), request.scopeId(),
                blankToNull(request.country()), blankToNull(request.state())), request.name(),
                requested(request.values(), request.versions()));
        return ResponseEntity.created(URI.create("/api/v1/settings/profiles/" + id)).body(new IdResponse(id));
    }

    @GetMapping("/profiles/{id}")
    public ResponseEntity<SettingProfileView> get(@PathVariable UUID id) {
        SettingProfileView view = profileQueries.find(id)
                .orElseThrow(() -> new ResourceNotFoundException("SettingProfile", id));
        return ResponseEntity.ok().header(HttpHeaders.ETAG, IfMatch.etag(view.version())).body(view);
    }

    /** Sets, or with a null value removes, the keys named; renames when a name is given. */
    @PatchMapping(value = "/profiles/{id}", consumes = {"application/json", "application/merge-patch+json"})
    public ResponseEntity<SettingProfileView> update(@PathVariable UUID id,
                                              @RequestHeader(value = "If-Match", required = false) String ifMatch,
                                              @Valid @RequestBody UpdateProfileRequest request) {
        profiles.update(id, IfMatch.version(ifMatch, PROFILE_VERSION), request.name(),
                requested(request.values(), request.versions()));
        return get(id);
    }

    @DeleteMapping("/profiles/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id,
                                       @RequestHeader(value = "If-Match", required = false) String ifMatch) {
        profiles.delete(id, IfMatch.version(ifMatch, PROFILE_VERSION));
        return ResponseEntity.noContent().build();
    }

    /** What has been changed on the profile, newest first, key by key. */
    @GetMapping("/profiles/{id}/history")
    public Slice<HistoryEntryView> history(@PathVariable UUID id,
                                           @RequestParam(required = false) String cursor,
                                           @RequestParam(required = false) Integer limit) {
        if (profileQueries.find(id).isEmpty()) {
            throw new ResourceNotFoundException("SettingProfile", id);
        }
        return history.historyOf(SettingProfileService.AUDIT_TYPE, id, cursor, limit,
                new HistoryViewer(ScopeReferences.currentUserId(), true));
    }

    /** The caller's preferences: what they have set, and what is in force for them. */
    @GetMapping("/me")
    public ResponseEntity<MyPreferencesView> mine() {
        UUID userId = requireUser();
        SettingProfileView own = profileQueries.mine(userId).orElse(null);
        long version = own == null ? 0L : own.version();
        SettingContext context = SettingContext.tenant().withOrgPath(TenantContext.requireOrgPath())
                .withUser(userId);
        List<SettingDefinition> personal = registry.all().stream().filter(SettingDefinition::userPreference)
                .toList();
        return ResponseEntity.ok().header(HttpHeaders.ETAG, IfMatch.etag(version))
                .body(new MyPreferencesView(version, own == null ? Map.of() : own.values(),
                        entries(personal, context)));
    }

    @PatchMapping(value = "/me", consumes = {"application/json", "application/merge-patch+json"})
    public ResponseEntity<MyPreferencesView> updateMine(
            @RequestHeader(value = "If-Match", required = false) String ifMatch,
            @Valid @RequestBody UpdatePreferencesRequest request) {
        requireUser();
        profiles.updateMine(IfMatch.version(ifMatch, MY_VERSION), request.values());
        return mine();
    }

    /** Undated values first, then dated versions, in the order given. */
    private static List<SettingRegistry.Requested> requested(Map<String, Object> values,
                                                             List<VersionRequest> versions) {
        List<SettingRegistry.Requested> out = new ArrayList<>();
        if (values != null) {
            values.forEach((key, value) -> out.add(new SettingRegistry.Requested(key, null, value)));
        }
        if (versions != null) {
            versions.forEach(v -> out.add(new SettingRegistry.Requested(v.key(), v.effectiveFrom(), v.value())));
        }
        return out;
    }

    private static String blankToNull(String text) {
        return text == null || text.isBlank() ? null : text.strip().toUpperCase(java.util.Locale.ROOT);
    }

    private static UUID requireUser() {
        UUID userId = ScopeReferences.currentUserId();
        if (userId == null) {
            throw new AccessDeniedException("Only a signed-in user has preferences");
        }
        return userId;
    }

    private List<EffectiveEntry> entries(Collection<SettingDefinition> wanted, SettingContext context) {
        Map<String, Resolved<Object>> resolved = effective.resolve(wanted, context);
        return wanted.stream().map(d -> {
            Resolved<Object> r = resolved.get(d.key());
            return new EffectiveEntry(d.key(), d.type(), d.category(), d.label(),
                    SettingValueRules.toJson(r.value()), SourceView.of(r.source()));
        }).toList();
    }

    private DefinitionView definition(SettingDefinition d) {
        return new DefinitionView(d.key(), d.type(), d.label(), d.description(),
                SettingValueRules.toJson(registry.defaultOf(d.key())), d.scopes(), d.min(), d.max(),
                d.type() == SettingType.DECIMAL ? d.scale() : null,
                d.minDuration() == null ? null : d.minDuration().toString(),
                d.maxDuration() == null ? null : d.maxDuration().toString(),
                d.type() == SettingType.STRING ? d.maxLength() : null,
                d.type() == SettingType.STRING ? d.format() : null,
                d.allowedValues().isEmpty() ? null : d.allowedValues(),
                d.properties().isEmpty() ? null : d.properties(),
                d.required().isEmpty() ? null : d.required(),
                d.userPreference(), d.category(), d.effectiveDated(), d.tenantOverridable());
    }

    public record DefinitionView(String key, SettingType type, String label, String description,
                                 Object defaultValue, List<ScopeType> scopes, BigDecimal min, BigDecimal max,
                                 Integer scale, String minDuration, String maxDuration, Integer maxLength,
                                 StringFormat format, List<String> allowedValues,
                                 Map<String, SettingType> jsonProperties, Set<String> requiredProperties,
                                 boolean userPreference, SettingCategory category, boolean effectiveDated,
                                 boolean tenantOverridable) {
    }

    /** @param kind {@code SYSTEM_DEFAULT} when no profile set the key, else {@code PROFILE} */
    public record SourceView(String kind, ScopeType scopeType, UUID scopeId, UUID profileId,
                             String profileName, LocalDate effectiveFrom) {

        static SourceView of(SettingSource source) {
            return source.isSystemDefault()
                    ? new SourceView("SYSTEM_DEFAULT", null, null, null, null, null)
                    : new SourceView("PROFILE", source.scopeType(), source.scopeId(), source.profileId(),
                            source.profileName(), source.effectiveFrom());
        }
    }

    public record EffectiveEntry(String key, SettingType type, SettingCategory category, String label,
                                 Object value, SourceView source) {
    }

    public record EffectiveView(List<EffectiveEntry> items) {
    }

    public record MyPreferencesView(long version, Map<String, Object> values, List<EffectiveEntry> effective) {
    }

    /** One version of a key, from its first day; a null value removes the version. */
    public record VersionRequest(@NotNull @Size(max = 100) String key, @NotNull LocalDate effectiveFrom,
                                 Object value) {
    }

    public record CreateProfileRequest(@NotNull ScopeType scopeType, UUID scopeId, @Size(max = 100) String name,
                                       @Size(max = 2) String country, @Size(max = 3) String state,
                                       @Size(max = 200) Map<String, Object> values,
                                       @Size(max = 200) List<@Valid VersionRequest> versions) {
    }

    public record UpdateProfileRequest(@Size(max = 100) String name, @Size(max = 200) Map<String, Object> values,
                                       @Size(max = 200) List<@Valid VersionRequest> versions) {
    }

    public record UpdatePreferencesRequest(@NotNull @Size(max = 200) Map<String, Object> values) {
    }
}
