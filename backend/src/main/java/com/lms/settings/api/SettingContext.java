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
import java.util.UUID;

/**
 * What a resolution knows about the thing it resolves for. Every field is
 * optional; a profile whose scope the context does not name does not apply.
 * The tenant is always the one in scope.
 *
 * @param orgUnitId the organisational unit; its profile and those of every
 *                  unit above it apply
 * @param orgPath   the unit's materialised path, when that is what the caller
 *                  holds (an access token carries it); used when
 *                  {@code orgUnitId} is null
 * @param vendorId  the vendor partner, for a key kept per vendor (a freight
 *                  bill's match tolerance, say)
 * @param asOf      the day to judge by: of each profile's dated versions of a
 *                  key, the latest effective on or before it applies; null
 *                  for today, in the tenant's {@code settings.timeZone}
 * @param country   ISO 3166-1 alpha-2 code of the jurisdiction, for platform
 *                  values kept per country; null for global values only
 * @param state     the state or province within {@code country}; null for
 *                  the country's values only
 */
public record SettingContext(UUID orgUnitId, String orgPath, UUID customerId, UUID vehicleId,
                             UUID userId, LocalDate asOf, String country, String state, UUID vendorId) {

    private static final SettingContext TENANT =
            new SettingContext(null, null, null, null, null, null, null, null, null);

    /** The organisation default alone. */
    public static SettingContext tenant() {
        return TENANT;
    }

    public static SettingContext ofOrgUnit(UUID orgUnitId) {
        return new SettingContext(orgUnitId, null, null, null, null, null, null, null, null);
    }

    /** As the rule stood on {@code day}: a document judged by the rule in force on its own date. */
    public SettingContext asOf(LocalDate day) {
        return new SettingContext(orgUnitId, orgPath, customerId, vehicleId, userId, day, country, state, vendorId);
    }

    public SettingContext inJurisdiction(String countryCode, String stateCode) {
        return new SettingContext(orgUnitId, orgPath, customerId, vehicleId, userId, asOf, countryCode,
                stateCode, vendorId);
    }

    public SettingContext withOrgPath(String path) {
        return new SettingContext(orgUnitId, path, customerId, vehicleId, userId, asOf, country, state, vendorId);
    }

    public SettingContext withCustomer(UUID customer) {
        return new SettingContext(orgUnitId, orgPath, customer, vehicleId, userId, asOf, country, state, vendorId);
    }

    public SettingContext withVehicle(UUID vehicle) {
        return new SettingContext(orgUnitId, orgPath, customerId, vehicle, userId, asOf, country, state, vendorId);
    }

    public SettingContext withVendor(UUID vendor) {
        return new SettingContext(orgUnitId, orgPath, customerId, vehicleId, userId, asOf, country, state, vendor);
    }

    public SettingContext withUser(UUID user) {
        return new SettingContext(orgUnitId, orgPath, customerId, vehicleId, user, asOf, country, state, vendorId);
    }
}
