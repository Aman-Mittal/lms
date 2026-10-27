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
import java.time.Instant;
import java.util.UUID;

import com.lms.controltower.command.domain.TripWatch;
import com.lms.execution.api.TripLookupPort;
import com.lms.shared.audit.TransitionAudit;
import com.lms.shared.config.Json;
import com.lms.shared.error.BusinessRuleViolationException;
import com.lms.shared.error.ResourceNotFoundException;
import com.lms.shared.tenant.TenantContext;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Records what a trip was promised, which is what the ETA and temperature
 * detectors measure it against.
 *
 * <p>Held to the same rule as the trip itself: the desk may only touch a trip
 * the tenant's access policies let it.
 */
@Service
public class TripWatchCommandService {

    private final TripWatchRepository watches;
    private final TripLookupPort trips;
    private final TransitionAudit audit;

    public TripWatchCommandService(TripWatchRepository watches, TripLookupPort trips,
                                   TransitionAudit audit) {
        this.watches = watches;
        this.trips = trips;
        this.audit = audit;
    }

    /**
     * Sets, or replaces, a trip's terms.
     *
     * @param committedArrivalAt optional; when the trip promised to arrive
     * @param tempMinC           optional with {@code tempMaxC}; the cargo's band
     */
    @Transactional
    @PreAuthorize("hasAuthority('EXCEPTION_MANAGE')"
            + " and @abac.permits('EXCEPTION_MANAGE', 'TRIP', #tripId)")
    public void set(UUID tripId, Instant committedArrivalAt, BigDecimal tempMinC,
                    BigDecimal tempMaxC) {
        if ((tempMinC == null) != (tempMaxC == null)) {
            throw new BusinessRuleViolationException("temperature-band-incomplete",
                    "A temperature band needs both a minimum and a maximum");
        }
        if (tempMinC != null && tempMinC.compareTo(tempMaxC) > 0) {
            throw new BusinessRuleViolationException("temperature-band-inverted",
                    "The minimum temperature " + tempMinC + " is above the maximum " + tempMaxC);
        }
        if (committedArrivalAt == null && tempMinC == null) {
            throw new BusinessRuleViolationException("trip-watch-empty",
                    "A trip watch needs a committed arrival, a temperature band, or both");
        }
        UUID tenantId = TenantContext.requireTenantId();
        TripLookupPort.TripSnapshot trip = trips.find(tripId)
                .orElseThrow(() -> new ResourceNotFoundException("Trip", tripId));
        if (trip.isCancelled()) {
            throw new BusinessRuleViolationException("trip-cancelled",
                    "Trip " + trip.tripNo() + " is cancelled; there is nothing to watch");
        }

        TripWatch before = watches.findByTrip(tenantId, tripId).orElse(null);
        TripWatch saved = watches.save(before == null
                ? TripWatch.of(UUID.randomUUID(), tenantId, tripId, committedArrivalAt, tempMinC,
                        tempMaxC)
                : before.withTerms(committedArrivalAt, tempMinC, tempMaxC));

        audit.record("TRIP_WATCH_SET", "TRIP_WATCH", saved.id(),
                before == null ? null : terms(before), terms(saved));
    }

    /** Stops watching a trip. Tickets already raised are left as they are. */
    @Transactional
    @PreAuthorize("hasAuthority('EXCEPTION_MANAGE')"
            + " and @abac.permits('EXCEPTION_MANAGE', 'TRIP', #tripId)")
    public void clear(UUID tripId) {
        TripWatch watch = watches.findByTrip(TenantContext.requireTenantId(), tripId)
                .orElseThrow(() -> new ResourceNotFoundException("Trip watch", tripId));
        watches.delete(watch);
        audit.record("TRIP_WATCH_CLEARED", "TRIP_WATCH", watch.id(), terms(watch), null);
    }

    private static Json terms(TripWatch watch) {
        return Json.object("tripId", String.valueOf(watch.tripId()),
                "committedArrivalAt", String.valueOf(watch.committedArrivalAt()),
                "tempMinC", String.valueOf(watch.tempMinC()),
                "tempMaxC", String.valueOf(watch.tempMaxC()));
    }
}
