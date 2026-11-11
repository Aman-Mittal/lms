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

/**
 * Where a profile applies, in ascending order of precedence.
 *
 * <p>The fold is fixed: a profile of a later type beats one of an earlier
 * type, and among organisational units the deeper unit wins. A key lists the
 * scope types it may be set at, so the relative order of two types no key
 * allows together (a vehicle and a customer) never decides anything. The
 * families in use are:
 * <ul>
 *   <li>platform: PLATFORM, beneath every family, for a key the platform
 *       operator maintains for all tenants (by jurisdiction: global &lt;
 *       country &lt; state)</li>
 *   <li>commercial: ORG_DEFAULT &lt; ORG_UNIT &lt; CUSTOMER</li>
 *   <li>fleet: ORG_DEFAULT &lt; ORG_UNIT &lt; VEHICLE</li>
 *   <li>personal: ORG_DEFAULT &lt; ORG_UNIT &lt; USER</li>
 * </ul>
 */
public enum ScopeType {

    /**
     * Every tenant, maintained by the platform operator; a profile of this type
     * belongs to no tenant and may name a jurisdiction (a country, optionally
     * a state).
     */
    PLATFORM,
    /** The whole tenant; a profile of this type has no scope id. */
    ORG_DEFAULT,
    /** An organisational unit and everything beneath it. */
    ORG_UNIT,
    /** One vehicle of the fleet. */
    VEHICLE,
    /** One customer partner. */
    CUSTOMER,
    /** One user's own preferences, set only by that user. */
    USER;

    /** Higher wins. */
    public int precedence() {
        return ordinal();
    }

    public boolean needsScopeId() {
        return this != ORG_DEFAULT && this != PLATFORM;
    }
}
