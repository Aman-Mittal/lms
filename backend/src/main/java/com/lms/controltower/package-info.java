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
 * into tickets. Also hears {@code multimodal::events} (missed connections),
 * {@code milkrun::events} (skipped and late supplier stops) and
 * {@code warehouse::events} (receipt variances and pick shortfalls); a ticket
 * raised from those may be about an itinerary, a circuit run, an ASN or an
 * outbound order rather than a trip, and one with no trip never holds
 * settlement.
 *
 * <p>Two more detectors run off telematics events. A trip given terms through
 * a trip watch -- the arrival it committed to, the temperature band its cargo
 * must stay in -- raises an ETA_DELAY ticket when the projected arrival is later
 * than committed by more than the tenant's tolerance, and a TEMPERATURE_EXCURSION
 * ticket when readings stay out of band for longer than the tenant's duration.
 * Each is at most one unclosed ticket per trip and kind.
 *
 * <p>Uses {@code identity::access} to tell the access decision point whether a
 * ticket is holding money, which is what decides who may close it.
 */
@org.springframework.modulith.ApplicationModule(
        displayName = "Exception & Control Tower",
        allowedDependencies = {"execution::api", "telematics::api", "telematics::events",
                "multimodal::events", "milkrun::events", "warehouse::events",
                "identity::access"})
package com.lms.controltower;
