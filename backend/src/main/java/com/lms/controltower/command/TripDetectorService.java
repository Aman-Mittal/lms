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
package com.lms.controltower.command;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Optional;
import java.util.UUID;

import com.lms.controltower.command.domain.ExceptionCategory;
import com.lms.controltower.command.domain.ExceptionTicket.Source;
import com.lms.controltower.command.domain.TripWatch;
import com.lms.shared.tenant.TenantContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The ETA-slip and temperature-excursion detectors.
 *
 * <p>Driven by telematics events, never by sweeping trips: each event reads
 * one indexed row, the trip's watch, and returns at once for a trip nobody
 * gave terms. Unguarded, because the platform is the actor, and each call is
 * its own transaction so that a failure on one delivery cannot undo the
 * publisher's.
 *
 * <p>Both are idempotent twice over: the ticket's source key makes a
 * redelivery a no-op, and a trip has at most one unclosed ticket of each kind.
 */
@Service
public class TripDetectorService {

    private static final DateTimeFormatter AT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'").withZone(ZoneOffset.UTC);

    private final TripWatchRepository watches;
    private final DetectorSettingsService settings;
    private final ExceptionCommandService exceptions;

    public TripDetectorService(TripWatchRepository watches, DetectorSettingsService settings,
                               ExceptionCommandService exceptions) {
        this.watches = watches;
        this.settings = settings;
        this.exceptions = exceptions;
    }

    /**
     * Judges a projected arrival against the committed one.
     *
     * <p>A slip is strictly more than the tenant's tolerance beyond the
     * commitment. An arrival projected earlier, or only a little later, is not
     * one, and a projection that recovers does not close a ticket: a person
     * does that.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<UUID> onEtaProjected(UUID tripId, Instant projectedArrivalAt, BigDecimal lat,
                                         BigDecimal lon, Instant basedOnFixAt) {
        Optional<TripWatch> watch = watches.findByTrip(TenantContext.requireTenantId(), tripId);
        if (watch.isEmpty() || watch.get().committedArrivalAt() == null) {
            return Optional.empty();
        }
        Instant committed = watch.get().committedArrivalAt();
        Duration tolerance = Duration.ofMinutes(settings.effective().etaSlipMinutes());
        Duration slip = Duration.between(committed, projectedArrivalAt);
        if (slip.compareTo(tolerance) <= 0) {
            return Optional.empty();
        }
        return exceptions.raiseForTripDetector(tripId, ExceptionCategory.ETA_DELAY,
                Source.ETA_SLIP_DETECTED,
                "eta-slip:" + tripId + ":" + projectedArrivalAt.toEpochMilli(),
                "Projected arrival " + AT.format(projectedArrivalAt) + " is "
                        + slip.toMinutes() + " minutes after the committed "
                        + AT.format(committed) + "; the tolerance is "
                        + tolerance.toMinutes() + " minutes",
                lat, lon, basedOnFixAt);
    }

    /**
     * Judges one reading against the trip's band.
     *
     * <p>The excursion starts at the first reading out of band and ends at the
     * first one back inside; between readings the cargo is taken to have stayed
     * as it was last seen. It is a ticket once it has lasted the tenant's
     * duration, and one excursion is one ticket however long it goes on. A
     * reading no newer than the last judged is stale or redelivered, and is
     * ignored.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<UUID> onTemperature(UUID tripId, BigDecimal celsius, Instant recordedAt,
                                        BigDecimal lat, BigDecimal lon) {
        Optional<TripWatch> found = watches.findByTrip(TenantContext.requireTenantId(), tripId);
        if (found.isEmpty() || !found.get().hasBand()) {
            return Optional.empty();
        }
        TripWatch watch = found.get();
        if (watch.lastTempAt() != null && !recordedAt.isAfter(watch.lastTempAt())) {
            return Optional.empty();
        }

        if (!watch.isOutside(celsius)) {
            watches.save(watch.observed(recordedAt, null));
            return Optional.empty();
        }

        Instant outSince = watch.tempOutSince() == null ? recordedAt : watch.tempOutSince();
        watches.save(watch.observed(recordedAt, outSince));

        Duration limit = Duration.ofMinutes(settings.effective().temperatureExcursionMinutes());
        Duration outFor = Duration.between(outSince, recordedAt);
        if (outFor.compareTo(limit) < 0) {
            return Optional.empty();
        }
        return exceptions.raiseForTripDetector(tripId, ExceptionCategory.TEMPERATURE_EXCURSION,
                Source.TEMPERATURE_EXCURSION_DETECTED,
                "temperature:" + tripId + ":" + outSince.toEpochMilli(),
                "Cargo temperature has been outside " + watch.tempMinC().stripTrailingZeros()
                        .toPlainString() + " to " + watch.tempMaxC().stripTrailingZeros()
                        .toPlainString() + " C since " + AT.format(outSince) + " ("
                        + outFor.toMinutes() + " minutes; the tolerance is "
                        + limit.toMinutes() + "); latest reading "
                        + celsius.stripTrailingZeros().toPlainString() + " C",
                lat, lon, recordedAt);
    }
}
