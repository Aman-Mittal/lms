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
package com.lms.execution.command;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import com.lms.execution.api.TripTrackingPort;
import com.lms.execution.command.domain.Trip;
import com.lms.shared.error.ResourceNotFoundException;
import com.lms.shared.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Turns geofence observations into trip state transitions.
 *
 * <p>The rules are small and they belong here rather than in telematics:
 * leaving the origin means the lorry is moving, entering the destination means
 * it has arrived, and every other crossing -- a toll plaza, a fuel stop, a hub
 * it passes through -- is recorded and changes nothing.
 *
 * <p>Each rule is a setting of the trip's unit, vehicle or customer
 * ({@link ExecutionSettingDefinitions}): departure and arrival happen by
 * themselves unless switched off, and leaving the destination after arriving
 * completes the trip only where switched on. Switched off, the crossing is
 * still recorded by telematics and a person moves the trip.
 */
@Service
public class TripTrackingAdapter implements TripTrackingPort {

    private static final Logger log = LoggerFactory.getLogger(TripTrackingAdapter.class);

    private final TripRepository trips;
    private final TripSettings settings;
    private final TripCompletion completion;

    public TripTrackingAdapter(TripRepository trips, TripSettings settings, TripCompletion completion) {
        this.trips = trips;
        this.settings = settings;
        this.completion = completion;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ActiveTrip> activeTripFor(UUID vehicleId) {
        return trips.findActiveForVehicle(TenantContext.requireTenantId(), vehicleId)
                .map(trip -> new ActiveTrip(trip.id(), trip.tripNo(), trip.status().name(),
                        trip.vehicleId(), trip.originTerminalId(), trip.destinationTerminalId()));
    }

    @Override
    @Transactional
    public void observeGeofenceEntry(UUID tripId, UUID terminalId, Instant at) {
        Trip trip = require(tripId);

        if (!terminalId.equals(trip.destinationTerminalId())) {
            // A crossing worth recording but not a state change. Passing a hub
            // is not arriving at one.
            return;
        }
        if (trip.status() != Trip.TripStatus.IN_TRANSIT) {
            // Arriving somewhere the trip has not left yet, or arriving twice.
            // Neither is an error worth failing an ingest batch over: GPS is
            // noisy and a vehicle sitting on a boundary re-enters repeatedly.
            log.debug("Ignoring destination entry for trip {} in status {}",
                    trip.tripNo(), trip.status());
            return;
        }
        if (!settings.tracking(ExecutionSettingDefinitions.AUTO_ARRIVAL, trip)) {
            return;
        }
        trips.save(trip.arrive(at));
    }

    @Override
    @Transactional
    public void observeGeofenceExit(UUID tripId, UUID terminalId, Instant at) {
        Trip trip = require(tripId);

        if (terminalId.equals(trip.destinationTerminalId()) && trip.status() == Trip.TripStatus.AT_DESTINATION) {
            // Unloaded and gone: complete, where the trip's settings say so.
            if (settings.tracking(ExecutionSettingDefinitions.AUTO_COMPLETION, trip)) {
                completion.complete(trip, at);
            }
            return;
        }
        if (!terminalId.equals(trip.originTerminalId())) {
            return;
        }
        if (trip.status() != Trip.TripStatus.DISPATCHED) {
            log.debug("Ignoring origin exit for trip {} in status {}", trip.tripNo(), trip.status());
            return;
        }
        if (!settings.tracking(ExecutionSettingDefinitions.AUTO_DEPARTURE, trip)) {
            return;
        }
        // The gate-out was recorded by a human at dispatch; this is the lorry
        // physically clearing the yard, which is what IN_TRANSIT means.
        trips.save(trip.beginTransit());
    }

    private Trip require(UUID tripId) {
        return trips.findById(tripId)
                .orElseThrow(() -> new ResourceNotFoundException("Trip", tripId));
    }
}
