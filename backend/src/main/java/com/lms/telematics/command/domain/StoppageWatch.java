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

package com.lms.telematics.command.domain;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import com.lms.shared.geo.GeoUtils;
import com.lms.shared.geo.LatLon;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.relational.core.mapping.Table;

/**
 * Where a trip's vehicle has been sitting, and since when.
 *
 * <p>One row per trip. The anchor is the first fix of the current stop and does
 * not drift with later fixes: a vehicle creeping through a jam moves the
 * anchor, GPS jitter around a parked lorry does not.
 */
@Table("stoppage_watch")
public record StoppageWatch(
        @Id UUID id,
        UUID tenantId,
        UUID tripId,
        BigDecimal lat,
        BigDecimal lon,
        Instant stationarySince,
        Instant raisedAt,
        @Version Long version,
        Instant createdAt) {

    /** What one more fix means for the stop being watched. */
    public enum Verdict {
        /** Moved, or nothing was watched yet: start a new stop at this fix. */
        RESTART,
        /** Still there and not long enough to matter, or already reported. */
        NOTHING,
        /** Still there, and for longer than the threshold. Report it. */
        RAISE
    }

    public static StoppageWatch begin(UUID id, UUID tenantId, UUID tripId, BigDecimal lat,
                                      BigDecimal lon, Instant since) {
        return new StoppageWatch(id, tenantId, tripId, lat, lon, since, null, null, Instant.now());
    }

    public LatLon anchor() {
        return new LatLon(lat.doubleValue(), lon.doubleValue());
    }

    public boolean isRaised() {
        return raisedAt != null;
    }

    /**
     * Judges a fix against the stop in progress.
     *
     * @param fix       where the vehicle is now
     * @param at        when the device recorded that
     * @param radiusM   how far from the anchor still counts as not having moved
     * @param threshold how long it may sit before that is an exception
     */
    public Verdict judge(LatLon fix, Instant at, double radiusM, Duration threshold) {
        if (at.isBefore(stationarySince)) {
            // A late-arriving older point says nothing about the present.
            return Verdict.NOTHING;
        }
        if (GeoUtils.haversineMetres(anchor(), fix) > radiusM) {
            return Verdict.RESTART;
        }
        if (isRaised()) {
            return Verdict.NOTHING;
        }
        return Duration.between(stationarySince, at).compareTo(threshold) >= 0
                ? Verdict.RAISE
                : Verdict.NOTHING;
    }

    /** Marks the stop as reported. */
    public StoppageWatch raised(Instant at) {
        return new StoppageWatch(id, tenantId, tripId, lat, lon, stationarySince, at, version,
                createdAt);
    }

    /** The same row, moved to a new stop. */
    public StoppageWatch restartedAt(BigDecimal newLat, BigDecimal newLon, Instant since) {
        return new StoppageWatch(id, tenantId, tripId, newLat, newLon, since, null, version,
                createdAt);
    }
}
