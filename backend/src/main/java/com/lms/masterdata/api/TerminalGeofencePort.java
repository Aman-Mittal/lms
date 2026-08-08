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
package com.lms.masterdata.api;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.lms.shared.geo.LatLon;

/**
 * Which terminals a coordinate falls inside.
 *
 * <p>Called once per GPS ping, which makes it the hottest read in the platform.
 * The exact geometry test stays on this side of the boundary because masterdata
 * owns what a terminal's shape means -- polygon or point-radius -- and a caller
 * that fetched the raw shape would have to reimplement that choice.
 *
 * <p>Synchronous, and it has to be: a geofence crossing drives the trip state
 * machine, and a state machine driven by eventually-consistent positions would
 * report a lorry arriving after it had already left.
 */
public interface TerminalGeofencePort {

    /**
     * Terminals containing the point, cheapest filter first.
     *
     * <p>Two phases, as set out in DOCS/adr/0004: an indexed bounding-box range
     * query narrows the field, then exact ray-casting runs in Java over the
     * handful of survivors.
     */
    List<TerminalMatch> terminalsContaining(LatLon point);

    /** A terminal's centre, for measuring the corridor a trip should stay in. */
    Optional<LatLon> centreOf(UUID terminalId);

    record TerminalMatch(UUID terminalId, String code, String functionalCategory) {
    }
}
