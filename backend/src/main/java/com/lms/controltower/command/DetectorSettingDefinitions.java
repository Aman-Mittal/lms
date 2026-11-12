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

import java.time.Duration;
import java.util.List;

import com.lms.settings.api.ScopeType;
import com.lms.settings.api.SettingContributor;
import com.lms.settings.api.SettingDefinition;
import com.lms.settings.api.SettingKey;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The tolerances of the ETA and temperature detectors. Their system defaults
 * are the deployment's properties, as before profiles existed.
 *
 * <p>Both may also be set for an organisational unit, a vehicle and a
 * customer, and the detectors resolve them for the trip's unit and vehicle and
 * the customer of its freight (the only one aboard; a load carrying several
 * customers' freight is judged without one). How late is too late is the
 * customer's promise; how long cargo may sit out of band is as much the body
 * it travels in as the goods.
 */
@Component
public class DetectorSettingDefinitions implements SettingContributor {

    public static final SettingKey<Duration> ETA_SLIP = SettingKey.duration("controltower.detector.etaSlipTolerance");
    public static final SettingKey<Duration> TEMPERATURE_EXCURSION =
            SettingKey.duration("controltower.detector.temperatureExcursionTolerance");

    /** How late past its planned arrival a trip may reach a stop before it is an exception (issue #223). */
    public static final SettingKey<Duration> STOP_LATE = SettingKey.duration("controltower.detector.stopLateTolerance");

    static final Duration SHORTEST = Duration.ofMinutes(1);
    static final Duration LONGEST = Duration.ofDays(7);

    private final int defaultEtaSlipMinutes;
    private final int defaultTemperatureMinutes;
    private final int defaultStopLateMinutes;

    public DetectorSettingDefinitions(@Value("${lms.controltower.eta-slip-minutes:60}") int defaultEtaSlipMinutes,
                                      @Value("${lms.controltower.temperature-excursion-minutes:15}")
                                      int defaultTemperatureMinutes,
                                      @Value("${lms.controltower.stop-late-minutes:30}") int defaultStopLateMinutes) {
        this.defaultEtaSlipMinutes = defaultEtaSlipMinutes;
        this.defaultTemperatureMinutes = defaultTemperatureMinutes;
        this.defaultStopLateMinutes = defaultStopLateMinutes;
    }

    @Override
    public List<SettingDefinition> definitions() {
        return List.of(
                SettingDefinition.of(ETA_SLIP, "ETA slip tolerance",
                                "How much later than committed a projected arrival may be before the"
                                        + " control tower raises an ETA delay.")
                        .defaultValue(Duration.ofMinutes(defaultEtaSlipMinutes)).between(SHORTEST, LONGEST)
                        .alsoAt(ScopeType.ORG_UNIT, ScopeType.VEHICLE, ScopeType.CUSTOMER, ScopeType.COMBINATION).build(),
                SettingDefinition.of(TEMPERATURE_EXCURSION, "Temperature excursion tolerance",
                                "How long a watched trip's cargo may stay outside its band before the"
                                        + " control tower raises a temperature excursion.")
                        .defaultValue(Duration.ofMinutes(defaultTemperatureMinutes)).between(SHORTEST, LONGEST)
                        .alsoAt(ScopeType.ORG_UNIT, ScopeType.VEHICLE, ScopeType.CUSTOMER, ScopeType.COMBINATION).build(),
                SettingDefinition.of(STOP_LATE, "Late-at-stop tolerance",
                                "How much later than its planned arrival a trip's vehicle may reach a stop"
                                        + " before the control tower raises a late-at-stop exception.")
                        .defaultValue(Duration.ofMinutes(defaultStopLateMinutes)).between(SHORTEST, LONGEST)
                        .alsoAt(ScopeType.ORG_UNIT, ScopeType.CUSTOMER, ScopeType.COMBINATION).build());
    }
}
