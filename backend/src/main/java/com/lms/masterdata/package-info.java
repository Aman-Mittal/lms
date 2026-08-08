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
 * Master Data Management (vision document 3.2).
 *
 * <p>The single source of truth for business partners, vehicles, drivers and
 * the geospatial terminal network. Everything downstream — planning, sourcing,
 * execution, telematics — reads capacities, compliance state and geofences that
 * originate here.
 *
 * <p>{@code allowedDependencies} is empty. Master data is reference data: it is
 * depended upon, and depends on nothing. In particular it does not import
 * {@code identity} — the tenant and organisational scope arrive through
 * {@code TenantContext} in the shared kernel, which is how every module gets
 * them without coupling to how authentication works.
 */
@org.springframework.modulith.ApplicationModule(
        displayName = "Master Data",
        allowedDependencies = {})
package com.lms.masterdata;
