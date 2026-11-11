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
package com.lms.settings.command.domain;

import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

import com.lms.settings.api.ScopeType;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.relational.core.mapping.MappedCollection;
import org.springframework.data.relational.core.mapping.Table;

/**
 * A named set of setting values for one scope: the organisation default
 * (no scope id), an organisational unit, a customer, a vehicle, or one user --
 * or, with no tenant at all, the platform's own values for every tenant, by
 * jurisdiction. A key may hold several versions, each from its own day.
 * One per scope in a tenant. Its values are part of the aggregate, so a change
 * to any of them advances the profile's version.
 *
 * <p>The version is a primitive, unlike most aggregates here: 0 means new and
 * the first save writes 1. A person's preferences are created by the same
 * conditional write that changes them, with {@code If-Match: "0"} meaning
 * "none saved yet"; a first version of 0 would read the same as none, and the
 * second save from a stale read would not be refused.
 */
@Table("setting_profile")
public record SettingProfile(
        @Id UUID id,
        UUID tenantId,
        ScopeType scopeType,
        UUID scopeId,
        String name,
        String country,
        String state,
        UUID matchOrgUnitId,
        UUID matchCustomerId,
        UUID matchVendorId,
        UUID matchVehicleId,
        String matchFreightMode,
        int priority,
        @MappedCollection(idColumn = "profile_id")
        Set<SettingValue> values,
        @Version long version,
        Instant createdAt,
        Instant updatedAt) {

    public SettingProfile {
        values = values == null ? Set.of() : Set.copyOf(values);
    }

    public static SettingProfile of(UUID tenantId, ScopeType scopeType, UUID scopeId, String name) {
        Instant now = Instant.now();
        return new SettingProfile(UUID.randomUUID(), tenantId, scopeType, scopeId, name, null, null,
                null, null, null, null, null, 0, Set.of(), 0L, now, now);
    }

    /** A platform profile: no tenant, for every jurisdiction or one country, optionally one state. */
    public static SettingProfile platform(String country, String state, String name) {
        Instant now = Instant.now();
        return new SettingProfile(UUID.randomUUID(), null, ScopeType.PLATFORM, null, name, country, state,
                null, null, null, null, null, 0, Set.of(), 0L, now, now);
    }

    /** A combination profile: its scope id is its own id, so a tenant may keep many. */
    public static SettingProfile combination(UUID tenantId, String name, UUID orgUnitId, UUID customerId,
                                             UUID vendorId, UUID vehicleId, String freightMode, int priority) {
        Instant now = Instant.now();
        UUID id = UUID.randomUUID();
        return new SettingProfile(id, tenantId, ScopeType.COMBINATION, id, name, null, null, orgUnitId,
                customerId, vendorId, vehicleId, freightMode, priority, Set.of(), 0L, now, now);
    }

    /** As read at {@code expectedVersion}, so a save checks that nothing moved since. */
    public SettingProfile atVersion(long expectedVersion) {
        return new SettingProfile(id, tenantId, scopeType, scopeId, name, country, state, matchOrgUnitId,
                matchCustomerId, matchVendorId, matchVehicleId, matchFreightMode, priority, values,
                expectedVersion, createdAt, updatedAt);
    }

    public SettingProfile with(String newName, Collection<SettingValue> newValues) {
        return new SettingProfile(id, tenantId, scopeType, scopeId, newName, country, state, matchOrgUnitId,
                matchCustomerId, matchVendorId, matchVehicleId, matchFreightMode, priority,
                new LinkedHashSet<>(newValues), version, createdAt, Instant.now());
    }

    /** A combination's conditions and priority replaced. */
    public SettingProfile withConditions(UUID orgUnitId, UUID customerId, UUID vendorId, UUID vehicleId,
                                         String freightMode, int newPriority) {
        return new SettingProfile(id, tenantId, scopeType, scopeId, name, country, state, orgUnitId, customerId,
                vendorId, vehicleId, freightMode, newPriority, values, version, createdAt, updatedAt);
    }
}
