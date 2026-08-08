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
package com.lms.masterdata.events;

import java.time.Instant;
import java.util.UUID;

/**
 * A terminal entered the geospatial network.
 *
 * <p>Carries identifiers and labels only — deliberately not the polygon.
 * Telematics needs to know a geofence exists and re-reads the geometry when it
 * evaluates a ping; shipping the ring through the event would duplicate the
 * source of truth and let the two drift.
 */
public record TerminalRegistered(
        UUID tenantId,
        UUID terminalId,
        String code,
        String name,
        String functionalCategory,
        String geofenceType,
        Instant occurredAt) {

    public static TerminalRegistered from(UUID tenantId, UUID terminalId, String code, String name,
                                          String functionalCategory, String geofenceType) {
        return new TerminalRegistered(tenantId, terminalId, code, name,
                functionalCategory, geofenceType, Instant.now());
    }
}
