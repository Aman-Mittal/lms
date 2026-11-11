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

import com.lms.settings.api.ScopeType;
import com.lms.settings.api.SettingContributor;
import com.lms.settings.api.SettingDefinition;
import com.lms.settings.api.SettingKey;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The thresholds of the stoppage and route-deviation detectors. Their system
 * defaults are the deployment's properties, as before profiles existed, so a
 * tenant that sets nothing sees no change.
 *
 * <p>Resolved per trip for its organisational unit, its vehicle and the
 * customer of its freight (the only one aboard; a load carrying several
 * customers' freight is judged by its unit's and vehicle's values). How long
 * a stop may last and how wide the corridor is depend on the customer's
 * promise and the lane; the radius that counts as "not moving" is a matter of
 * the vehicle and its device, so a customer cannot set it.
 */
@Component
public class TelematicsSettingDefinitions implements SettingContributor {

    public static final SettingKey<Duration> STOPPAGE_THRESHOLD = SettingKey.duration("telematics.stoppage.threshold");
    public static final SettingKey<Integer> STOPPAGE_RADIUS = SettingKey.integer("telematics.stoppage.radiusMetres");
    public static final SettingKey<Integer> ROUTE_CORRIDOR =
            SettingKey.integer("telematics.routeDeviation.corridorMetres");

    private final long defaultStoppageMinutes;
    private final int defaultRadiusMetres;
    private final int defaultCorridorMetres;

    public TelematicsSettingDefinitions(@Value("${lms.telematics.stoppage-threshold-minutes:45}")
                                        long defaultStoppageMinutes,
                                        @Value("${lms.telematics.stoppage-radius-m:100}") double defaultRadiusMetres,
                                        @Value("${lms.telematics.route-corridor-m:25000}")
                                        BigDecimal defaultCorridorMetres) {
        this.defaultStoppageMinutes = defaultStoppageMinutes;
        this.defaultRadiusMetres = (int) Math.round(defaultRadiusMetres);
        this.defaultCorridorMetres = defaultCorridorMetres.intValue();
    }

    @Override
    public List<SettingDefinition> definitions() {
        return List.of(
                SettingDefinition.of(STOPPAGE_THRESHOLD, "Stoppage threshold",
                                "How long a trip in transit may stand still outside every terminal before"
                                        + " the control tower raises an extended stoppage.")
                        .defaultValue(Duration.ofMinutes(defaultStoppageMinutes))
                        .between(Duration.ofMinutes(1), Duration.ofDays(7))
                        .alsoAt(ScopeType.ORG_UNIT, ScopeType.VEHICLE, ScopeType.CUSTOMER).build(),
                SettingDefinition.of(STOPPAGE_RADIUS, "Stoppage radius (m)",
                                "How far a vehicle may drift, in metres, and still count as standing"
                                        + " still; GPS jitter and manoeuvring in a lay-by stay inside it.")
                        .defaultValue(defaultRadiusMetres).range(1, 10_000)
                        .alsoAt(ScopeType.ORG_UNIT, ScopeType.VEHICLE).build(),
                SettingDefinition.of(ROUTE_CORRIDOR, "Route corridor (m)",
                                "How far off the straight line from origin to destination, in metres, a"
                                        + " vehicle may be before it has strayed. Wide by default, because the"
                                        + " line is not a road.")
                        .defaultValue(defaultCorridorMetres).range(100, 1_000_000)
                        .alsoAt(ScopeType.ORG_UNIT, ScopeType.VEHICLE, ScopeType.CUSTOMER).build());
    }
}
