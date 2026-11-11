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

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import com.lms.execution.api.TripLookupPort;
import com.lms.settings.api.EffectiveSettings;
import com.lms.settings.api.SettingContext;
import com.lms.settings.api.SettingKey;
import com.lms.settings.api.Settings;
import org.springframework.stereotype.Component;

/**
 * The detector thresholds in force for one trip
 * ({@link TelematicsSettingDefinitions}), read once per trip per ingested
 * batch rather than once per fix.
 */
@Component
class TrackingThresholds {

    private static final List<SettingKey<?>> KEYS = List.of(TelematicsSettingDefinitions.STOPPAGE_THRESHOLD,
            TelematicsSettingDefinitions.STOPPAGE_RADIUS, TelematicsSettingDefinitions.ROUTE_CORRIDOR);

    private final Settings settings;
    private final TripLookupPort trips;

    TrackingThresholds(Settings settings, TripLookupPort trips) {
        this.settings = settings;
        this.trips = trips;
    }

    /**
     * @param stoppage       how long a stop may last
     * @param radiusMetres   how far a stopped vehicle may drift
     * @param corridorMetres how far off the direct line it may be
     */
    record Thresholds(Duration stoppage, int radiusMetres, BigDecimal corridorMetres) {
    }

    Thresholds forTrip(UUID tripId, UUID vehicleId) {
        SettingContext context = trips.scopeOf(tripId)
                .map(scope -> SettingContext.ofOrgUnit(scope.orgUnitId()).withVehicle(scope.vehicleId())
                        .withCustomer(scope.soleCustomerId()))
                .orElseGet(() -> SettingContext.tenant().withVehicle(vehicleId));
        EffectiveSettings resolved = settings.resolveAll(KEYS, context);
        return new Thresholds(resolved.get(TelematicsSettingDefinitions.STOPPAGE_THRESHOLD),
                resolved.get(TelematicsSettingDefinitions.STOPPAGE_RADIUS),
                BigDecimal.valueOf(resolved.get(TelematicsSettingDefinitions.ROUTE_CORRIDOR)));
    }
}
