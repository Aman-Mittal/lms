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

import com.lms.masterdata.command.domain.Terminal;
import com.lms.shared.geo.LatLon;

/**
 * Intent to register a terminal (vision document 3.2.3).
 *
 * <p>A command states what the caller wants, not how it is stored. The two
 * geofence shapes are expressed as alternatives rather than a bag of nullable
 * columns, so an impossible combination -- a polygon with a radius, a
 * point-radius with no centre -- cannot be constructed in the first place.
 */
public sealed interface RegisterTerminalCommand {

    UUID orgUnitId();

    String code();

    String name();

    Terminal.FunctionalCategory category();

    Integer dockCount();

    LocalTime opensAt();

    LocalTime closesAt();

    Integer avgDwellMinutes();

    List<String> permittedVehicleTypes();

    record Polygon(
            UUID orgUnitId,
            String code,
            String name,
            Terminal.FunctionalCategory category,
            List<LatLon> ring,
            Integer dockCount,
            LocalTime opensAt,
            LocalTime closesAt,
            Integer avgDwellMinutes,
            List<String> permittedVehicleTypes) implements RegisterTerminalCommand {
    }

    record PointRadius(
            UUID orgUnitId,
            String code,
            String name,
            Terminal.FunctionalCategory category,
            LatLon centre,
            double radiusMetres,
            Integer dockCount,
            LocalTime opensAt,
            LocalTime closesAt,
            Integer avgDwellMinutes,
            List<String> permittedVehicleTypes) implements RegisterTerminalCommand {
    }
}
