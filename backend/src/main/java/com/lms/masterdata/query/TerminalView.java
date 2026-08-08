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
 */package com.lms.masterdata.query;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalTime;
import java.util.UUID;

/**
 * A terminal as a map or a planning screen shows it.
 *
 * <p>The polygon is returned as its GeoJSON text rather than parsed. The one
 * consumer that needs the shape is a map, which wants GeoJSON anyway; parsing
 * it into objects here would cost an allocation per vertex per row to hand back
 * something the client immediately re-serialises.
 */
public record TerminalView(
        UUID id,
        String code,
        String name,
        String functionalCategory,
        String geofenceType,
        String polygon,
        BigDecimal centreLat,
        BigDecimal centreLon,
        BigDecimal radiusM,
        Integer dockCount,
        LocalTime opensAt,
        LocalTime closesAt,
        Integer avgDwellMinutes,
        Instant createdAt) {
}
