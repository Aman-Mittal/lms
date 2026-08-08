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
package com.lms.telematics;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import com.lms.shared.geo.LatLon;
import com.lms.telematics.api.PingIngestPort.PingReport;
import com.lms.telematics.command.PingSanityFilter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The filter that stops a bad coordinate becoming a wrong delivery notification.
 *
 * <p>A point that survives to the geofence stage puts a lorry inside a terminal
 * it is nowhere near, which advances the trip state machine, which tells a
 * customer their goods have arrived when they are three hundred kilometres
 * away. That is the failure this class exists to prevent, and it is why the
 * cases below are the ones worth testing.
 */
class PingSanityFilterTest {

    private static final Instant NOW = Instant.parse("2026-08-07T12:00:00Z");
    private static final UUID VEHICLE = UUID.randomUUID();

    private static PingReport ping(double lat, double lon, Instant at) {
        return new PingReport(VEHICLE, at, BigDecimal.valueOf(lat), BigDecimal.valueOf(lon),
                null, null, null, null, "DEVICE");
    }

    @Test
    @DisplayName("accepts an ordinary point")
    void acceptsGoodPoint() {
        assertThat(PingSanityFilter.reject(ping(19.05, 72.85, NOW), NOW, null, null))
                .isEmpty();
    }

    @Test
    @DisplayName("rejects coordinates outside the world")
    void rejectsOutOfRange() {
        assertThat(PingSanityFilter.reject(ping(91, 72.85, NOW), NOW, null, null))
                .get().asString().contains("out of range");
        assertThat(PingSanityFilter.reject(ping(19.05, 181, NOW), NOW, null, null))
                .get().asString().contains("out of range");
    }

    @Test
    @DisplayName("rejects a null island fix")
    void rejectsNullIsland() {
        // Exactly (0,0) is a real coordinate in the Gulf of Guinea, so it passes
        // every range check -- and it is what a device reports when it has no
        // lock. Without this rule, a fleet of lorries appears off West Africa.
        assertThat(PingSanityFilter.reject(ping(0, 0, NOW), NOW, null, null))
                .get().asString().contains("no lock");
    }

    @Test
    @DisplayName("accepts a point genuinely near the equator and prime meridian")
    void acceptsNearNullIsland() {
        // The rule must be exact equality, not proximity. Ghana and São Tomé are
        // real places with real freight.
        assertThat(PingSanityFilter.reject(ping(0.01, 0.01, NOW), NOW, null, null))
                .isEmpty();
    }

    @Test
    @DisplayName("rejects a timestamp far in the future")
    void rejectsFutureTimestamp() {
        assertThat(PingSanityFilter.reject(
                ping(19.05, 72.85, NOW.plusSeconds(3600)), NOW, null, null))
                .get().asString().contains("future");
    }

    @Test
    @DisplayName("tolerates small clock skew")
    void toleratesSkew() {
        // Device clocks drift. Rejecting everything a few seconds ahead would
        // throw away a working fleet's data.
        assertThat(PingSanityFilter.reject(
                ping(19.05, 72.85, NOW.plusSeconds(60)), NOW, null, null))
                .isEmpty();
    }

    @Test
    @DisplayName("rejects an impossible speed")
    void rejectsImplausibleSpeed() {
        PingReport fast = new PingReport(VEHICLE, NOW, BigDecimal.valueOf(19.05),
                BigDecimal.valueOf(72.85), BigDecimal.valueOf(400), null, null, null, "DEVICE");

        assertThat(PingSanityFilter.reject(fast, NOW, null, null))
                .get().asString().contains("implausible speed");
    }

    @Test
    @DisplayName("rejects a negative speed")
    void rejectsNegativeSpeed() {
        PingReport reversing = new PingReport(VEHICLE, NOW, BigDecimal.valueOf(19.05),
                BigDecimal.valueOf(72.85), BigDecimal.valueOf(-5), null, null, null, "DEVICE");

        assertThat(PingSanityFilter.reject(reversing, NOW, null, null))
                .get().asString().contains("negative speed");
    }

    @Test
    @DisplayName("rejects a fix too imprecise to decide a geofence")
    void rejectsPoorAccuracy() {
        PingReport vague = new PingReport(VEHICLE, NOW, BigDecimal.valueOf(19.05),
                BigDecimal.valueOf(72.85), null, null, BigDecimal.valueOf(2000), null, "DEVICE");

        assertThat(PingSanityFilter.reject(vague, NOW, null, null))
                .get().asString().contains("cannot decide a geofence");
    }

    @Test
    @DisplayName("rejects a teleport between consecutive fixes")
    void rejectsImpossibleJump() {
        // Mumbai to Delhi is about 1,150 km. In one minute.
        LatLon mumbai = new LatLon(19.076, 72.877);

        assertThat(PingSanityFilter.reject(
                ping(28.613, 77.209, NOW.plusSeconds(60)), NOW.plusSeconds(60),
                mumbai, NOW))
                .get().asString().contains("kph since the previous fix");
    }

    @Test
    @DisplayName("accepts ordinary motorway progress")
    void acceptsRealisticMovement() {
        // Roughly 1.6 km in a minute: about 95 kph, which is a lorry on a
        // motorway, not a teleport.
        LatLon start = new LatLon(19.076, 72.877);

        assertThat(PingSanityFilter.reject(
                ping(19.090, 72.877, NOW.plusSeconds(60)), NOW.plusSeconds(60), start, NOW))
                .isEmpty();
    }

    @Test
    @DisplayName("does not judge a jump when points arrive out of order")
    void ignoresOutOfOrderPoints() {
        // Two devices on one vehicle do not coordinate, and a buffered flush
        // interleaves with live reporting. Nothing can be inferred from a
        // negative interval, so the check must not apply rather than reject.
        LatLon delhi = new LatLon(28.613, 77.209);

        assertThat(PingSanityFilter.reject(
                ping(19.076, 72.877, NOW.minusSeconds(60)), NOW, delhi, NOW))
                .isEmpty();
    }

    @Test
    @DisplayName("rejects a report with nothing to place it")
    void rejectsIncomplete() {
        assertThat(PingSanityFilter.reject(
                new PingReport(null, NOW, BigDecimal.ONE, BigDecimal.ONE,
                        null, null, null, null, "DEVICE"), NOW, null, null))
                .get().asString().contains("no vehicle");

        assertThat(PingSanityFilter.reject(
                new PingReport(VEHICLE, NOW, null, null, null, null, null, null, "DEVICE"),
                NOW, null, null))
                .get().asString().contains("no coordinates");
    }
}
