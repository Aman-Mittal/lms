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

/**
 * Settings: typed, validated values grouped into profiles attached to a scope,
 * and the effective value of each for a context.
 *
 * <p>A setting exists only when a module registers it in code: a
 * {@link com.lms.settings.api.SettingContributor} bean lists its
 * {@link com.lms.settings.api.SettingDefinition}s (key, type, system default,
 * bounds, and the scope types it may be set at). A key no module registered,
 * a value outside its bounds and a scope its definition does not allow are
 * refused when written, so nothing is stored that nothing reads.
 *
 * <p>A profile holds values for one scope: the organisation default, an
 * organisational unit, a customer, a vendor, a vehicle, or one user's own
 * preferences.
 * The effective value of a key for a context is folded from the profiles that
 * apply to it, least specific first: organisation default, then organisational
 * units from the root down (a deeper unit beats a shallower one), then vehicle,
 * customer, vendor and user. The most specific profile that sets the key wins; with
 * none, the system default answers. Every resolution reads all applicable
 * profiles in one statement.
 *
 * <p>Depends on nothing. Other modules reach it through {@code settings::api}
 * only. That a scope's record exists for the caller's tenant is checked with a
 * lookup under row-level security by table name, as custom fields do, rather
 * than by importing the module that owns the record.
 */
@org.springframework.modulith.ApplicationModule(
        displayName = "Settings and Preferences",
        allowedDependencies = {})
package com.lms.settings;
