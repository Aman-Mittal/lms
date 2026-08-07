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
 * Trip execution, gate operations and the weighbridge (vision document 3.6).
 *
 * <p>Where the plan meets a lorry. The trip state machine here is the spine of
 * the platform: telematics drives it from geofence crossings, finance prices a
 * trip from the timestamps it records, and dispatch is the single point at
 * which the compliance rules of 3.2.2 stop being data and start stopping
 * vehicles.
 *
 * <p>Reads loads through {@code planning::api} and asks {@code masterdata::api}
 * whether a vehicle and driver are fit to dispatch. Both are synchronous, and
 * the second is the reason the port exists at all: a rule that blocks a
 * dispatch cannot be eventually consistent, because by the time the answer
 * arrives the lorry has gone.
 */
@org.springframework.modulith.ApplicationModule(
        displayName = "Trip Execution",
        allowedDependencies = {"planning::api", "masterdata::api"})
package com.lms.execution;
