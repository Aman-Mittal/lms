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
package com.lms.masterdata.command;

import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

import com.lms.shared.geo.LatLon;

/**
 * Intent to change a terminal.
 *
 * <p>Everything but the code, the category and the organisational unit, which
 * never change. The geofence is optional and, like registration, one shape or
 * the other: {@code null} keeps the present one.
 *
 * @param version the version the caller last read; a stale one is refused
 */
public record UpdateTerminalCommand(
        UUID id,
        String name,
        long version,
        Geofence geofence,
        Integer dockCount,
        LocalTime opensAt,
        LocalTime closesAt,
        Integer avgDwellMinutes,
        List<String> permittedVehicleTypes) {

    public sealed interface Geofence {
    }

    public record Polygon(List<LatLon> ring) implements Geofence {
    }

    public record PointRadius(LatLon centre, double radiusMetres) implements Geofence {
    }
}
