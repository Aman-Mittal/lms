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
 * Consignment generation and load building (vision document 3.4).
 *
 * <p>Turns validated demand into executable physical units: line items become
 * consignments grouped by destination, and consignments are aggregated into
 * loads that respect a vehicle's hard capacities.
 *
 * <p>Both declared dependencies are synchronous ports rather than events, and
 * both for the same reason: they gate the operator's next click. Load building
 * must know what a vehicle can carry <em>before</em> the load is built, and
 * consignment generation must return the references it just created. Learning
 * either eventually would mean pressing a button and watching nothing happen.
 *
 * <p>The dependency runs one way only. Planning reads from order and tells it
 * what has been planned; order never imports planning, which is what keeps
 * these two out of a cycle that {@code ModularityTests} would reject.
 */
@org.springframework.modulith.ApplicationModule(
        displayName = "Planning & Load Building",
        allowedDependencies = {"order::api", "masterdata::api"})
package com.lms.planning;
