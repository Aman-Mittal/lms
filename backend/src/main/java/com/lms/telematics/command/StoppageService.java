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

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import com.lms.shared.geo.LatLon;
import com.lms.telematics.command.domain.GpsPing;
import com.lms.telematics.command.domain.StoppageWatch;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Notices a trip in transit that has stopped moving.
 *
 * <p>Ping-driven rather than a sweep: the free tier has no cron, a sweep would
 * have to scan every tenant, and the ingest path already touches the trip on
 * every fix. The cost is one indexed read per fix of a trip in transit, and a
 * write only when the vehicle moves or the stop is reported. The price of
 * being driven by pings is that a device that goes silent is not noticed here.
 */
@Service
public class StoppageService {

    private final StoppageWatchRepository watches;
    private final double radiusMetres;
    private final Duration threshold;

    public StoppageService(StoppageWatchRepository watches,
                           @Value("${lms.telematics.stoppage-radius-m:100}") double radiusMetres,
                           @Value("${lms.telematics.stoppage-threshold-minutes:45}")
                           long thresholdMinutes) {
        this.watches = watches;
        this.radiusMetres = radiusMetres;
        this.threshold = Duration.ofMinutes(thresholdMinutes);
    }

    /** A stoppage worth reporting. */
    public record Stoppage(Instant since, long stoppedMinutes, long thresholdMinutes) {
    }

    /**
     * Judges one fix of a trip in transit that is outside every terminal.
     *
     * @return the stoppage, only on the fix that crosses the threshold
     */
    public Optional<Stoppage> observe(UUID tenantId, UUID tripId, GpsPing ping) {
        Optional<StoppageWatch> existing = watches.findByTrip(tenantId, tripId);
        if (existing.isEmpty()) {
            watches.save(begin(tenantId, tripId, ping));
            return Optional.empty();
        }

        StoppageWatch watch = existing.get();
        LatLon fix = ping.point();
        switch (watch.judge(fix, ping.recordedAt(), radiusMetres, threshold)) {
            case RESTART -> {
                watches.save(watch.restartedAt(ping.lat(), ping.lon(), ping.recordedAt()));
                return Optional.empty();
            }
            case RAISE -> {
                watches.save(watch.raised(ping.recordedAt()));
                return Optional.of(new Stoppage(watch.stationarySince(),
                        Duration.between(watch.stationarySince(), ping.recordedAt()).toMinutes(),
                        threshold.toMinutes()));
            }
            default -> {
                return Optional.empty();
            }
        }
    }

    /**
     * The vehicle is inside a terminal, or the trip is no longer one that can
     * stop unexpectedly: loading and unloading are not exceptions, so whatever
     * was being watched is forgotten.
     */
    public void forget(UUID tenantId, UUID tripId) {
        watches.findByTrip(tenantId, tripId).ifPresent(watches::delete);
    }

    private static StoppageWatch begin(UUID tenantId, UUID tripId, GpsPing ping) {
        return StoppageWatch.begin(UUID.randomUUID(), tenantId, tripId, ping.lat(), ping.lon(),
                ping.recordedAt());
    }
}
