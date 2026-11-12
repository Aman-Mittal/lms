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
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.lms.execution.api.TripTrackingPort.ActiveTrip;
import com.lms.masterdata.api.TerminalGeofencePort;
import com.lms.shared.geo.GeoUtils;
import com.lms.shared.geo.LatLon;
import com.lms.telematics.command.RouteCorridorService.Corridor;
import com.lms.telematics.command.domain.GpsPing;
import com.lms.telematics.command.domain.RouteDeviation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Route deviation measured against an imported corridor, and against the
 * straight line where there is none (issue #223), with the repository and the
 * terminal port faked.
 */
class RouteCorridorDeviationTest {

    /** Origin and destination on the equator, 2 degrees (about 222 km) apart; the road bends north through (1, 1). */
    private static final LatLon ORIGIN = new LatLon(0, 0);
    private static final LatLon DESTINATION = new LatLon(0, 2);
    private static final List<LatLon> BENT_ROAD = List.of(ORIGIN, new LatLon(1, 1), DESTINATION);
    private static final BigDecimal LIMIT = new BigDecimal("25000");

    private final UUID origin = UUID.randomUUID();
    private final UUID destination = UUID.randomUUID();
    private final UUID tenant = UUID.randomUUID();
    private final ActiveTrip trip = new ActiveTrip(UUID.randomUUID(), "TR-1", "IN_TRANSIT", UUID.randomUUID(),
            origin, destination);

    private RouteDeviationRepository repository;
    private RouteDeviationService service;

    @BeforeEach
    void wire() {
        repository = mock(RouteDeviationRepository.class);
        when(repository.findOpen(any(), any())).thenReturn(Optional.empty());
        when(repository.save(any(RouteDeviation.class))).thenAnswer(call -> call.getArgument(0));
        TerminalGeofencePort terminals = mock(TerminalGeofencePort.class);
        when(terminals.centreOf(origin)).thenReturn(Optional.of(ORIGIN));
        when(terminals.centreOf(destination)).thenReturn(Optional.of(DESTINATION));
        service = new RouteDeviationService(repository, terminals);
    }

    private GpsPing at(double lat, double lon) {
        return new GpsPing(UUID.randomUUID(), tenant, trip.vehicleId(), trip.tripId(),
                Instant.parse("2026-11-04T06:00:00Z"), BigDecimal.valueOf(lat), BigDecimal.valueOf(lon),
                null, null, null, true, "DEVICE", null, Instant.now());
    }

    @Test
    @DisplayName("the distance to a polyline is the distance to its nearest segment")
    void polylineDistance() {
        assertThat(GeoUtils.distanceToPolylineMetres(new LatLon(1, 1), BENT_ROAD)).isLessThan(1.0);
        assertThat(GeoUtils.distanceToPolylineMetres(new LatLon(0, 1), BENT_ROAD)).isBetween(70_000.0, 80_000.0);
        assertThat(GeoUtils.distanceToPolylineMetres(new LatLon(0, 1), List.of(ORIGIN, DESTINATION)))
                .isLessThan(1.0);
    }

    @Test
    @DisplayName("without a corridor the straight line is the reference, as before")
    void fallsBackToTheStraightLine() {
        // On the bend of the road, 111 km from the straight line.
        Optional<RouteDeviation> deviation = service.evaluate(tenant, trip, at(1, 1), LIMIT, Optional.empty());

        assertThat(deviation).isPresent();
        assertThat(deviation.get().reference()).isEqualTo("STRAIGHT_LINE");
        assertThat(deviation.get().distanceM()).isGreaterThan(new BigDecimal("100000"));
        // And the four-argument form every older caller uses is the same thing.
        assertThat(service.evaluate(tenant, trip, at(0, 1), LIMIT)).isEmpty();
    }

    @Test
    @DisplayName("with a corridor the vehicle is measured against the road: on its bend is on route")
    void onTheCorridorIsOnRoute() {
        Optional<RouteDeviation> deviation = service.evaluate(tenant, trip, at(1, 1), LIMIT,
                Optional.of(new Corridor(BENT_ROAD, null)));

        assertThat(deviation).isEmpty();
    }

    @Test
    @DisplayName("with a corridor, a point on the straight line but off the road is a deviation measured against the corridor")
    void offTheCorridorIsADeviation() {
        Optional<RouteDeviation> deviation = service.evaluate(tenant, trip, at(0, 1), LIMIT,
                Optional.of(new Corridor(BENT_ROAD, null)));

        assertThat(deviation).isPresent();
        assertThat(deviation.get().reference()).isEqualTo("CORRIDOR");
        assertThat(deviation.get().distanceM()).isBetween(new BigDecimal("70000"), new BigDecimal("80000"));
        assertThat(deviation.get().corridorM()).isEqualByComparingTo(LIMIT);
    }

    @Test
    @DisplayName("a corridor's own width replaces the setting as the limit")
    void corridorWidth() {
        // 5 km off the road: inside the 25 km setting, outside a 2 km corridor.
        GpsPing nearBend = at(1.045, 1);
        assertThat(service.evaluate(tenant, trip, nearBend, LIMIT, Optional.of(new Corridor(BENT_ROAD, null))))
                .isEmpty();

        Optional<RouteDeviation> narrow = service.evaluate(tenant, trip, nearBend, LIMIT,
                Optional.of(new Corridor(BENT_ROAD, new BigDecimal("2000"))));
        assertThat(narrow).isPresent();
        assertThat(narrow.get().corridorM()).isEqualByComparingTo("2000");
    }
}
