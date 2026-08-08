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
import java.time.Instant;
import java.util.UUID;

import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.relational.core.mapping.Table;

/**
 * A trip crossing a terminal's boundary.
 *
 * <p>Recorded as its own fact rather than left implicit in the ping stream.
 * Once the raw points are pruned, these crossings are the only surviving
 * explanation of why a trip changed state -- and that is what a customer
 * dispute about a delivery time turns on.
 */
@Table("geofence_event")
public record GeofenceEvent(
        @Id UUID id,
        UUID tenantId,
        UUID tripId,
        UUID terminalId,
        EventType eventType,
        Instant occurredAt,
        BigDecimal lat,
        BigDecimal lon,
        @Version Long version,
        Instant createdAt) {

    public enum EventType {
        ENTERED, EXITED
    }

    public static GeofenceEvent of(UUID id, UUID tenantId, UUID tripId, UUID terminalId,
                                   EventType eventType, Instant occurredAt,
                                   BigDecimal lat, BigDecimal lon) {
        return new GeofenceEvent(id, tenantId, tripId, terminalId, eventType,
                occurredAt, lat, lon, null, Instant.now());
    }
}
