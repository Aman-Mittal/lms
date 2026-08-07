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
package com.lms.telematics.command;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.lms.execution.api.TripTrackingPort;
import com.lms.masterdata.api.TerminalGeofencePort;
import com.lms.shared.geo.GeoUtils;
import com.lms.shared.geo.LatLon;
import com.lms.telematics.command.domain.GpsPing;
import com.lms.telematics.command.domain.RouteDeviation;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Notices when a vehicle has strayed (vision document 3.7.2).
 *
 * <p>The corridor is measured against the straight line between the trip's
 * origin and destination, not against a road-network route. That is a real
 * limitation and it is stated rather than hidden: a genuine route needs a
 * routing engine, and every Apache-compatible option is either a service with
 * an API key or a dataset larger than this deployment's whole database.
 *
 * <p>The consequence is that the corridor has to be wide -- wide enough for a
 * road that bends around a hill. Set narrow, it would alert on every legitimate
 * journey, and an alert that fires on everything is switched off within a week.
 * Set as it is, it still catches what the rule is for: a lorry heading somewhere
 * it has no business being.
 */
@Service
public class RouteDeviationService {

    private final RouteDeviationRepository deviations;
    private final TerminalGeofencePort terminals;

    /**
     * How far off the direct line a vehicle may be before it counts as strayed.
     *
     * <p>Twenty-five kilometres by default. That sounds enormous, and it is,
     * because the reference line is a great circle rather than a road. It is
     * configurable per deployment precisely because the right value depends on
     * terrain the platform cannot see.
     */
    private final BigDecimal corridorMetres;

    public RouteDeviationService(RouteDeviationRepository deviations,
                                 TerminalGeofencePort terminals,
                                 @Value("${lms.telematics.route-corridor-m:25000}")
                                 BigDecimal corridorMetres) {
        this.deviations = deviations;
        this.terminals = terminals;
        this.corridorMetres = corridorMetres;
    }

    /**
     * Judges one point against the trip's corridor.
     *
     * @return the deviation, only when this point <em>opens</em> a new
     *         excursion. An empty result means either that the vehicle is on
     *         route or that it was already known to be off it.
     */
    public Optional<RouteDeviation> evaluate(UUID tenantId, TripTrackingPort.ActiveTrip trip,
                                             GpsPing ping) {
        if (trip.destinationTerminalId() == null) {
            return Optional.empty();
        }

        Optional<LatLon> origin = terminals.centreOf(trip.originTerminalId());
        Optional<LatLon> destination = terminals.centreOf(trip.destinationTerminalId());
        if (origin.isEmpty() || destination.isEmpty()) {
            return Optional.empty();
        }

        double distance = GeoUtils.distanceToPolylineMetres(ping.point(),
                List.of(origin.get(), destination.get()));

        Optional<RouteDeviation> open = deviations.findOpen(tenantId, trip.tripId());
        BigDecimal measured = BigDecimal.valueOf(distance).setScale(2, RoundingMode.HALF_UP);

        if (distance <= corridorMetres.doubleValue()) {
            // Back inside. Close the excursion rather than leaving it open
            // forever -- an alert that never clears is indistinguishable from a
            // stuck alert.
            open.ifPresent(deviation -> deviations.save(deviation.resolve(ping.recordedAt())));
            return Optional.empty();
        }

        if (open.isPresent()) {
            // Already off route. Keep the worst distance reached and stay quiet:
            // "went 2 km off route" and "went 40 km off route" are different
            // events, but only the first report of either is news.
            deviations.save(open.get().deepen(measured));
            return Optional.empty();
        }

        return Optional.of(deviations.save(RouteDeviation.open(UUID.randomUUID(), tenantId,
                trip.tripId(), ping.recordedAt(), ping.lat(), ping.lon(),
                measured, corridorMetres)));
    }
}
