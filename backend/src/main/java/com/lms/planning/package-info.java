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
 * <p>Depends on {@code order::events} to learn that demand has been validated,
 * and on {@code masterdata::api} because load building must ask what a vehicle
 * can actually carry -- a question that has to be answered before the load is
 * built, not eventually afterwards.
 */
@org.springframework.modulith.ApplicationModule(
        displayName = "Planning & Load Building",
        allowedDependencies = {"order::events", "masterdata::api", "masterdata::events"})
package com.lms.planning;
