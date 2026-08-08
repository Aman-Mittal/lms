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
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import com.lms.shared.geo.GeoUtils;
import com.lms.shared.geo.LatLon;
import com.lms.telematics.api.PingIngestPort;

/**
 * Throws out the points that cannot be true.
 *
 * <p>The pragmatic stand-in for the Kalman filter of vision document 3.7.1. A
 * proper filter estimates the true position from a noisy one; this only refuses
 * the ones that are impossible. That is a smaller claim and an honest one --
 * and it catches what actually goes wrong with vehicle telematics, which is not
 * gentle noise but a device reporting the middle of the ocean because it lost
 * its fix.
 *
 * <p>Why it matters more than it looks: a single bad point that survives to the
 * geofence stage puts a lorry inside a terminal it is nowhere near, which
 * advances the trip state machine, which fires a delivery notification to a
 * customer whose goods are three hundred kilometres away.
 */
public final class PingSanityFilter {

    /**
     * Above this, the vehicle is not a lorry.
     *
     * <p>Generous on purpose. Refusing genuine data is worse than admitting a
     * little rubbish, because the rubbish is visible on a map and the missing
     * point is not.
     */
    private static final double MAX_PLAUSIBLE_SPEED_KPH = 200;

    /**
     * Implied speed between consecutive points, above which one of them is
     * wrong.
     *
     * <p>Higher than the reported-speed limit because the implied figure is
     * sensitive to clock skew between a device and the server. Two points
     * thirty seconds apart and one second of skew is a three-percent error;
     * the same skew on two points one second apart is not.
     */
    private static final double MAX_IMPLIED_SPEED_KPH = 250;

    /** A fix worse than this is not precise enough to decide a geofence by. */
    private static final double MAX_ACCURACY_M = 500;

    /**
     * How far ahead of the server a device's clock may run.
     *
     * <p>Not zero: device clocks drift, and rejecting everything a few seconds
     * in the future would throw away a working fleet's data. Far in the future
     * is a different matter -- it means an unset clock, and those points would
     * sort to the end of every track forever.
     */
    private static final Duration MAX_CLOCK_SKEW = Duration.ofMinutes(5);

    private PingSanityFilter() {
    }

    /**
     * Judges one point, given the last one accepted for the same vehicle.
     *
     * @param previous the previous accepted position, or null for the first
     *                 point ever seen from this vehicle
     * @return the reason to reject it, or empty to accept
     */
    public static Optional<String> reject(PingIngestPort.PingReport ping,
                                          Instant now,
                                          LatLon previousPoint,
                                          Instant previousAt) {
        if (ping.vehicleId() == null) {
            return Optional.of("no vehicle identified");
        }
        if (ping.recordedAt() == null) {
            return Optional.of("no recorded timestamp");
        }
        if (ping.lat() == null || ping.lon() == null) {
            return Optional.of("no coordinates");
        }

        double lat = ping.lat().doubleValue();
        double lon = ping.lon().doubleValue();

        if (lat < -90 || lat > 90 || lon < -180 || lon > 180) {
            return Optional.of("coordinates out of range: " + lat + "," + lon);
        }
        // Null Island. A device that has lost its fix frequently reports exactly
        // zero rather than nothing, and zero is a real coordinate in the Gulf of
        // Guinea -- so it passes every range check and lands on the map.
        if (lat == 0 && lon == 0) {
            return Optional.of("null island fix (0,0), which means the device has no lock");
        }

        if (ping.recordedAt().isAfter(now.plus(MAX_CLOCK_SKEW))) {
            return Optional.of("recorded in the future at " + ping.recordedAt());
        }

        if (ping.speedKph() != null
                && ping.speedKph().doubleValue() > MAX_PLAUSIBLE_SPEED_KPH) {
            return Optional.of("implausible speed of " + ping.speedKph() + " kph");
        }
        if (ping.speedKph() != null && ping.speedKph().signum() < 0) {
            return Optional.of("negative speed of " + ping.speedKph() + " kph");
        }

        if (ping.accuracyM() != null && ping.accuracyM().doubleValue() > MAX_ACCURACY_M) {
            return Optional.of("fix accurate only to " + ping.accuracyM()
                    + " m, which cannot decide a geofence");
        }

        if (previousPoint != null && previousAt != null) {
            Optional<String> jump = rejectImpossibleJump(
                    new LatLon(lat, lon), ping.recordedAt(), previousPoint, previousAt);
            if (jump.isPresent()) {
                return jump;
            }
        }

        return Optional.empty();
    }

    private static Optional<String> rejectImpossibleJump(LatLon point, Instant at,
                                                         LatLon previousPoint, Instant previousAt) {
        // Points arriving out of order are ordinary -- a device flushing a
        // buffer sends them oldest first, but two devices on one vehicle do not
        // coordinate. Nothing can be inferred from a negative interval, so the
        // check simply does not apply.
        if (!at.isAfter(previousAt)) {
            return Optional.empty();
        }

        double seconds = Duration.between(previousAt, at).toMillis() / 1000.0;
        if (seconds <= 0) {
            return Optional.empty();
        }

        double metres = GeoUtils.haversineMetres(previousPoint, point);
        double impliedKph = (metres / seconds) * 3.6;

        if (impliedKph > MAX_IMPLIED_SPEED_KPH) {
            return Optional.of("implies " + Math.round(impliedKph) + " kph since the previous fix ("
                    + Math.round(metres) + " m in " + Math.round(seconds) + " s)");
        }
        return Optional.empty();
    }

    /** Exposed so a test can assert against the constant rather than a copy of it. */
    public static BigDecimal maxPlausibleSpeedKph() {
        return BigDecimal.valueOf(MAX_PLAUSIBLE_SPEED_KPH);
    }
}
