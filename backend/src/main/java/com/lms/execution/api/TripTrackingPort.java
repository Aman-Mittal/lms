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
package com.lms.execution.api;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * What telematics may ask of, and tell, the execution module.
 *
 * <p>Deliberately phrased as observations rather than commands. Telematics says
 * "this vehicle entered that terminal"; it does not say "set the trip to
 * AT_DESTINATION". Which crossing means which transition is a rule about trips,
 * and it belongs in the module whose tests fail when it changes.
 */
public interface TripTrackingPort {

    /**
     * The trip a vehicle is currently running, if any.
     *
     * <p>Empty is the common case and not an error: most vehicles reporting
     * position at any moment are parked, and their points are still worth
     * keeping.
     */
    Optional<ActiveTrip> activeTripFor(UUID vehicleId);

    /**
     * Records that the vehicle has entered a terminal's geofence.
     *
     * <p>Execution decides whether that is a state change. Entering the
     * destination is an arrival; entering a toll plaza on the way is not, and
     * telematics cannot tell the difference without knowing the trip's plan.
     */
    void observeGeofenceEntry(UUID tripId, UUID terminalId, Instant at);

    /** Records that the vehicle has left a terminal's geofence. */
    void observeGeofenceExit(UUID tripId, UUID terminalId, Instant at);

    /**
     * A trip in flight, reduced to what position processing needs.
     *
     * @param originTerminalId      leaving this one means the trip is moving
     * @param destinationTerminalId entering this one means it has arrived
     */
    record ActiveTrip(
            UUID tripId,
            String tripNo,
            String status,
            UUID vehicleId,
            UUID originTerminalId,
            UUID destinationTerminalId) {
    }
}
