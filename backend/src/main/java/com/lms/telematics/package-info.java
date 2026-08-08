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
 * Position ingest, geofencing and route monitoring (vision document 3.7).
 *
 * <p>This is the module that closes the loop. A batch of coordinates arrives,
 * and without anybody pressing anything a trip moves from DISPATCHED to
 * IN_TRANSIT to AT_DESTINATION -- which is the whole claim the platform makes.
 *
 * <p>Depends on {@code masterdata::api} for the geofences a point might fall
 * inside, and on {@code execution::api} to report what it saw. Both
 * synchronous: a position stream that advanced trips eventually would report a
 * lorry arriving after it had already left.
 *
 * <p>Ingest sits behind {@link com.lms.telematics.api.PingIngestPort} so that
 * an MQTT or Kafka consumer can replace the HTTP endpoint without the domain
 * noticing. The vision document's 20,000 points per second is a production
 * figure; this deployment is one 0.1-CPU instance and sustains far less. That
 * ceiling is stated in DOCS/MVP.md rather than implied away.
 */
@org.springframework.modulith.ApplicationModule(
        displayName = "Telematics",
        allowedDependencies = {"masterdata::api", "execution::api"})
package com.lms.telematics;
