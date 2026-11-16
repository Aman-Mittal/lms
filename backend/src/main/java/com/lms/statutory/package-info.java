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
 * Statutory e-way bills (issue #231): a bill per consignment with its number,
 * validity, status and Part-B vehicle, raised and changed through a pluggable
 * adapter that is chosen per organisational unit.
 *
 * <p>The adapter SPI lives in {@code statutory::api}. Which adapter a unit uses
 * is the setting {@code statutory.ewayBill.adapter}; the default, NONE, switches
 * the feature off, and a deterministic STUB ships for tests and the demo. Every
 * call to an adapter has a timeout and is audited (action, consignment, outcome;
 * never a credential: a unit's credentials are a reference such as
 * {@code secret-ref:eway/branch-1}, resolved by the adapter's own deployment).
 *
 * <p>Trip execution asks {@code statutory::api} whether a dispatch may leave and
 * forwards a trip's Part-B vehicle update, so this module never depends on
 * execution; it finds the freight through {@code planning::api}.
 */
@org.springframework.modulith.ApplicationModule(
        displayName = "Statutory E-way Bills",
        allowedDependencies = {"planning::api", "settings::api", "identity::access"})
package com.lms.statutory;
