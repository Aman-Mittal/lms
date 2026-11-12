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

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * What a resolution knows about the thing it resolves for. Every field is
 * optional; a profile whose scope the context does not name does not apply.
 * The tenant is always the one in scope.
 *
 * @param orgUnitId   the organisational unit; its profile and those of every
 *                    unit above it apply, and when the context names no
 *                    jurisdiction, the unit's own (the nearest country and
 *                    state at or above it) is used for platform values
 * @param orgPath     the unit's materialised path, when that is what the
 *                    caller holds (an access token carries it); used when
 *                    {@code orgUnitId} is null
 * @param vendorId    the vendor partner, for a key kept per vendor (a freight
 *                    bill's match tolerance, say)
 * @param asOf        the day to judge by: of each profile's dated versions of
 *                    a key, the latest effective on or before it applies;
 *                    null for today, in the tenant's {@code settings.timeZone}
 * @param country     ISO 3166-1 alpha-2 code of the jurisdiction, for platform
 *                    values kept per country; null to take the unit's
 * @param state       the state or province within {@code country}; null for
 *                    the country's values only (or the unit's, when
 *                    {@code country} is null too)
 * @param freightMode how the freight moves, one of {@link #FREIGHT_MODES};
 *                    only combination profiles condition on it
 * @param contractId  the client contract's family id (see {@link ScopeType#CONTRACT}),
 *                    for a key that opts into the CONTRACT scope
 */
public record SettingContext(UUID orgUnitId, String orgPath, UUID customerId, UUID vehicleId,
                             UUID userId, LocalDate asOf, String country, String state, UUID vendorId,
                             String freightMode, UUID contractId) {

    /**
     * The names of {@code order::api} {@code FreightMode}, which this module
     * cannot import (order depends on settings). A test holds the two equal.
     */
    public static final List<String> FREIGHT_MODES = List.of("FTL", "LTL", "RAIL", "MILK_RUN");

    private static final SettingContext TENANT =
            new SettingContext(null, null, null, null, null, null, null, null, null, null, null);

    public SettingContext {
        if (freightMode != null && !FREIGHT_MODES.contains(freightMode)) {
            throw new IllegalArgumentException("Unknown freight mode " + freightMode + "; one of " + FREIGHT_MODES);
        }
    }

    /** The organisation default alone. */
    public static SettingContext tenant() {
        return TENANT;
    }

    public static SettingContext ofOrgUnit(UUID orgUnitId) {
        return new SettingContext(orgUnitId, null, null, null, null, null, null, null, null, null, null);
    }

    /** As the rule stood on {@code day}: a document judged by the rule in force on its own date. */
    public SettingContext asOf(LocalDate day) {
        return new SettingContext(orgUnitId, orgPath, customerId, vehicleId, userId, day, country, state, vendorId,
                freightMode, contractId);
    }

    public SettingContext inJurisdiction(String countryCode, String stateCode) {
        return new SettingContext(orgUnitId, orgPath, customerId, vehicleId, userId, asOf, countryCode,
                stateCode, vendorId, freightMode, contractId);
    }

    public SettingContext withOrgPath(String path) {
        return new SettingContext(orgUnitId, path, customerId, vehicleId, userId, asOf, country, state, vendorId,
                freightMode, contractId);
    }

    public SettingContext withCustomer(UUID customer) {
        return new SettingContext(orgUnitId, orgPath, customer, vehicleId, userId, asOf, country, state, vendorId,
                freightMode, contractId);
    }

    public SettingContext withVehicle(UUID vehicle) {
        return new SettingContext(orgUnitId, orgPath, customerId, vehicle, userId, asOf, country, state, vendorId,
                freightMode, contractId);
    }

    public SettingContext withVendor(UUID vendor) {
        return new SettingContext(orgUnitId, orgPath, customerId, vehicleId, userId, asOf, country, state, vendor,
                freightMode, contractId);
    }

    public SettingContext withUser(UUID user) {
        return new SettingContext(orgUnitId, orgPath, customerId, vehicleId, user, asOf, country, state, vendorId,
                freightMode, contractId);
    }

    /** @param mode a freight mode's name, or null */
    public SettingContext withFreightMode(String mode) {
        return new SettingContext(orgUnitId, orgPath, customerId, vehicleId, userId, asOf, country, state, vendorId,
                mode, contractId);
    }

    /** @param familyId a client contract's family id, or null */
    public SettingContext withContract(UUID familyId) {
        return new SettingContext(orgUnitId, orgPath, customerId, vehicleId, userId, asOf, country, state, vendorId,
                freightMode, familyId);
    }
}
