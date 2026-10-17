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
 * Multimodal itineraries: road to the railhead, rail for the trunk, road to
 * the customer (vision document 3.4).
 *
 * <p>An itinerary is a shipment's route as ordered legs. A ROAD leg is an
 * existing trip, which execution runs; this module follows it through
 * {@code execution::events} and never drives it. A RAIL leg is a booking for a
 * rake of wagons or a number of containers, and moves by terminal events --
 * placed, loaded, departed, arrived, released -- reported over the API,
 * because rail is not tracked by GPS.
 *
 * <p>The rule that gives the module its point is the hand-off: a leg cannot
 * start before its predecessor has arrived and the transfer dwell has passed.
 * A rail departure that breaks it is refused. A road dispatch cannot be
 * refused from here -- the gate is execution's -- so one that breaks it is
 * recorded as an early start. And when a predecessor arrives too late for the
 * next leg's planned start, the connection is missed and a
 * {@link com.lms.multimodal.events.ConnectionMissed} fact is published.
 *
 * <p>Depends on {@code execution::api} to check that a road leg's trip exists
 * and has not yet left, {@code execution::events} to follow it, and
 * {@code masterdata::api} to check terminals and carriers.
 */
@org.springframework.modulith.ApplicationModule(
        displayName = "Multimodal Itineraries",
        allowedDependencies = {"execution::api", "execution::events", "masterdata::api"})
package com.lms.multimodal;
