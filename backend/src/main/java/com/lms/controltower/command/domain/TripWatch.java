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
package com.lms.controltower.command.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.relational.core.mapping.Table;

/**
 * What a trip was promised, and what the detectors have seen of it so far.
 *
 * <p>One row per trip. The committed arrival and the temperature band are the
 * terms; {@code tempOutSince} and {@code lastTempAt} are the temperature
 * detector's memory between readings.
 */
@Table("trip_watch")
public record TripWatch(
        @Id UUID id,
        UUID tenantId,
        UUID tripId,
        Instant committedArrivalAt,
        BigDecimal tempMinC,
        BigDecimal tempMaxC,
        Instant tempOutSince,
        Instant lastTempAt,
        @Version Long version,
        Instant createdAt) {

    public static TripWatch of(UUID id, UUID tenantId, UUID tripId, Instant committedArrivalAt,
                               BigDecimal tempMinC, BigDecimal tempMaxC) {
        return new TripWatch(id, tenantId, tripId, committedArrivalAt, tempMinC, tempMaxC, null,
                null, null, Instant.now());
    }

    public boolean hasBand() {
        return tempMinC != null;
    }

    public boolean isOutside(BigDecimal celsius) {
        return celsius.compareTo(tempMinC) < 0 || celsius.compareTo(tempMaxC) > 0;
    }

    /**
     * New terms. What the detector remembered was judged against the old band,
     * so it is forgotten rather than carried over.
     */
    public TripWatch withTerms(Instant committed, BigDecimal min, BigDecimal max) {
        return new TripWatch(id, tenantId, tripId, committed, min, max, null, null, version,
                createdAt);
    }

    /** The newest reading judged, and since when the readings have been out of band. */
    public TripWatch observed(Instant at, Instant outSince) {
        return new TripWatch(id, tenantId, tripId, committedArrivalAt, tempMinC, tempMaxC,
                outSince, at, version, createdAt);
    }
}
