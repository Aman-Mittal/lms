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
 * Exception and control tower management (vision document 3.8).
 *
 * <p>Where operational anomalies become tickets that somebody owns until they
 * are closed. A ticket is raised by a person (an accident reported by
 * telephone) or by a detector (a route deviation from telematics), and walks
 * RAISED, ASSIGNED, UNDER_INVESTIGATION, RESOLVED, CLOSED.
 *
 * <p>Two rules give the module its shape. The vehicle's position is captured
 * at the moment of raising, through {@code telematics::api}, because "where
 * was it when this happened" cannot be answered honestly later. And a critical
 * ticket suspends settlement of its trip until a manager closes it -- a rule
 * this module states by publishing events and finance enforces by listening,
 * so neither module reaches into the other.
 *
 * <p>Depends on {@code execution::api} to know which trip and vehicle a ticket
 * is about, and on {@code telematics::events} for the detections it turns
 * into tickets.
 */
@org.springframework.modulith.ApplicationModule(
        displayName = "Exception & Control Tower",
        allowedDependencies = {"execution::api", "telematics::api", "telematics::events"})
package com.lms.controltower;
