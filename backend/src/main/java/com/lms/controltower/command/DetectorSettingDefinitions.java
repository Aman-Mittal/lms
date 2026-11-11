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
 * <p>The ETA slip is the tenant's alone: nothing on a projected arrival names
 * a unit or a customer to resolve for. The temperature excursion may also be
 * set per vehicle, since how long cargo may sit out of band is a property of
 * the body it travels in; the detector resolves it for the trip's vehicle.
 */
@Component
public class DetectorSettingDefinitions implements SettingContributor {

    public static final SettingKey<Duration> ETA_SLIP = SettingKey.duration("controltower.detector.etaSlipTolerance");
    public static final SettingKey<Duration> TEMPERATURE_EXCURSION =
            SettingKey.duration("controltower.detector.temperatureExcursionTolerance");

    static final Duration SHORTEST = Duration.ofMinutes(1);
    static final Duration LONGEST = Duration.ofDays(7);

    private final int defaultEtaSlipMinutes;
    private final int defaultTemperatureMinutes;

    public DetectorSettingDefinitions(@Value("${lms.controltower.eta-slip-minutes:60}") int defaultEtaSlipMinutes,
                                      @Value("${lms.controltower.temperature-excursion-minutes:15}")
                                      int defaultTemperatureMinutes) {
        this.defaultEtaSlipMinutes = defaultEtaSlipMinutes;
        this.defaultTemperatureMinutes = defaultTemperatureMinutes;
    }

    @Override
    public List<SettingDefinition> definitions() {
        return List.of(
                SettingDefinition.of(ETA_SLIP, "ETA slip tolerance",
                                "How much later than committed a projected arrival may be before the"
                                        + " control tower raises an ETA delay.")
                        .defaultValue(Duration.ofMinutes(defaultEtaSlipMinutes)).between(SHORTEST, LONGEST)
                        .build(),
                SettingDefinition.of(TEMPERATURE_EXCURSION, "Temperature excursion tolerance",
                                "How long a watched trip's cargo may stay outside its band before the"
                                        + " control tower raises a temperature excursion.")
                        .defaultValue(Duration.ofMinutes(defaultTemperatureMinutes)).between(SHORTEST, LONGEST)
                        .alsoAt(ScopeType.VEHICLE).build());
    }
}
