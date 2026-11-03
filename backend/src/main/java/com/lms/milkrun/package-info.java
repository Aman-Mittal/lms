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
 * Milk runs: scheduled multi-stop pickup circuits for inbound logistics.
 *
 * <p>A milk run is a fixed circuit that collects from several suppliers in
 * sequence, each inside a pickup window, and delivers to a plant. The module
 * holds three things: versioned routes (the plan, its schedule and its price),
 * the circuit runs generated from them ahead of time, and what actually
 * happened at each stop.
 *
 * <p>Generation runs in-process, per tenant, at startup and on a fixed delay,
 * and is idempotent per route and day -- an instance that slept through the
 * night catches up on wake rather than planning anything twice.
 *
 * <p>A run is carried by an execution trip ({@code execution::api}, to check
 * the trip exists in this tenant). Per-stop arrival and departure are then
 * driven by the geofence crossings telematics publishes for that trip
 * ({@code telematics::events}), with manual recording for when the tracker is
 * silent. Stop and plant terminals are checked against master data through
 * {@code masterdata::api}, because a foreign key does not consult row-level
 * security and would accept another tenant's terminal.
 *
 * <p>Skipped stops, late windows and the priced, completed circuit are
 * published as events. Finance turns a completed circuit into the carrier's
 * payable; the completion event carries the carrying trip's load and vendor,
 * read through {@code execution::api}, so finance need not ask. The control
 * tower raises tickets from skipped stops and late windows.
 */
@org.springframework.modulith.ApplicationModule(
        displayName = "Milk Runs",
        allowedDependencies = {"execution::api", "masterdata::api", "telematics::events"})
package com.lms.milkrun;
