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
import org.springframework.stereotype.Service;

/**
 * Notices when a vehicle has strayed (vision document 3.7.2).
 *
 * <p>The corridor is measured against the straight line between the trip's
 * origin and destination, not against a road-network route. That is a real
 * limitation and it is stated rather than hidden: a genuine route needs a
 * routing engine, and every Apache-compatible option is either a service with
 * an API key or a dataset larger than this deployment's whole database. Where
 * the lane has a corridor imported for it ({@link RouteCorridorService}) the
 * vehicle is measured against that polyline instead, and the straight line
 * remains the fallback for every lane without one.
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

    public RouteDeviationService(RouteDeviationRepository deviations,
                                 TerminalGeofencePort terminals) {
        this.deviations = deviations;
        this.terminals = terminals;
    }

    /**
     * Judges one point against the trip's corridor.
     *
     * @param corridorMetres how far off the direct line the vehicle may be
     *                       before it counts as strayed: the trip's setting
     *                       ({@code telematics.routeDeviation.corridorMetres}),
     *                       twenty-five kilometres by default. That sounds
     *                       enormous, and it is, because the reference line is
     *                       a great circle rather than a road; it is a setting
     *                       of the unit, vehicle and customer precisely because
     *                       the right value depends on terrain the platform
     *                       cannot see.
     * @return the deviation, only when this point <em>opens</em> a new
     *         excursion. An empty result means either that the vehicle is on
     *         route or that it was already known to be off it.
     */
    public Optional<RouteDeviation> evaluate(UUID tenantId, TripTrackingPort.ActiveTrip trip,
                                             GpsPing ping, BigDecimal corridorMetres) {
        return evaluate(tenantId, trip, ping, corridorMetres, Optional.empty());
    }

    /**
     * As above, measured against the lane's imported corridor when it has one
     * ({@code corridor}), in which case the corridor's own width is the limit
     * when it states one; against the straight line otherwise.
     */
    public Optional<RouteDeviation> evaluate(UUID tenantId, TripTrackingPort.ActiveTrip trip,
                                             GpsPing ping, BigDecimal corridorMetres,
                                             Optional<RouteCorridorService.Corridor> corridor) {
        if (trip.destinationTerminalId() == null) {
            return Optional.empty();
        }

        List<LatLon> reference;
        String measuredAgainst;
        if (corridor.isPresent()) {
            reference = corridor.get().points();
            measuredAgainst = "CORRIDOR";
            if (corridor.get().widthMetres() != null) {
                corridorMetres = corridor.get().widthMetres();
            }
        } else {
            Optional<LatLon> origin = terminals.centreOf(trip.originTerminalId());
            Optional<LatLon> destination = terminals.centreOf(trip.destinationTerminalId());
            if (origin.isEmpty() || destination.isEmpty()) {
                return Optional.empty();
            }
            reference = List.of(origin.get(), destination.get());
            measuredAgainst = "STRAIGHT_LINE";
        }

        double distance = GeoUtils.distanceToPolylineMetres(ping.point(), reference);

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
                measured, corridorMetres, measuredAgainst)));
    }
}
