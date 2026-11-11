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

import java.util.Optional;
import java.util.UUID;

import com.lms.execution.api.TripLookupPort;
import com.lms.planning.api.LoadLifecyclePort;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Answers {@link TripLookupPort} from the trip aggregate. */
@Component
public class TripLookupAdapter implements TripLookupPort {

    private final TripRepository trips;
    private final LoadLifecyclePort loads;

    public TripLookupAdapter(TripRepository trips, LoadLifecyclePort loads) {
        this.trips = trips;
        this.loads = loads;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<TripSnapshot> find(UUID tripId) {
        return trips.findById(tripId)
                .map(trip -> new TripSnapshot(trip.id(), trip.tripNo(), trip.status().name(),
                        trip.vehicleId(), trip.vendorPartnerId(), trip.loadId(),
                        trip.originTerminalId(), trip.destinationTerminalId()));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<TripScope> scopeOf(UUID tripId) {
        return trips.findById(tripId).map(trip -> new TripScope(trip.id(), trip.orgUnitId(),
                trip.vehicleId(), loads.customersAboard(trip.loadId()), loads.freightModeOf(trip.loadId())));
    }
}
