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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.lms.execution.api.TripLookupPort;
import com.lms.settings.api.EffectiveSettings;
import com.lms.settings.api.SettingContext;
import com.lms.settings.api.SettingKey;
import com.lms.shared.error.BusinessRuleViolationException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The tolerances the ETA and temperature detectors apply, read and written
 * through the settings module ({@link DetectorSettingDefinitions}). A tenant
 * that has set none gets the property defaults; nothing has to be seeded.
 *
 * <p>{@code /exceptions/detector-settings} reads and writes the organisation
 * default in whole minutes, as it did when the tolerances were one row per
 * tenant.
 */
@Service
public class DetectorSettingsService {

    private static final int MAX_MINUTES = 10080;
    private static final List<SettingKey<?>> KEYS =
            List.of(DetectorSettingDefinitions.ETA_SLIP, DetectorSettingDefinitions.TEMPERATURE_EXCURSION);

    private final com.lms.settings.api.Settings settings;
    private final TripLookupPort trips;

    public DetectorSettingsService(com.lms.settings.api.Settings settings, TripLookupPort trips) {
        this.settings = settings;
        this.trips = trips;
    }

    /**
     * @param etaSlipMinutes              how much later than committed a projected arrival
     *                                    may be before it is a slip
     * @param temperatureExcursionMinutes how long a reading may stay out of band
     * @param customised                  false when the tenant has set nothing
     */
    public record Settings(int etaSlipMinutes, int temperatureExcursionMinutes,
                           boolean customised) {
    }

    /** The organisation default. Unguarded: the platform is the caller. */
    @Transactional(readOnly = true)
    public Settings effective() {
        EffectiveSettings resolved = settings.resolveAll(KEYS, SettingContext.tenant());
        boolean customised = !resolved.resolved(DetectorSettingDefinitions.ETA_SLIP).source().isSystemDefault()
                || !resolved.resolved(DetectorSettingDefinitions.TEMPERATURE_EXCURSION).source().isSystemDefault();
        return new Settings(minutes(resolved.get(DetectorSettingDefinitions.ETA_SLIP)),
                minutes(resolved.get(DetectorSettingDefinitions.TEMPERATURE_EXCURSION)), customised);
    }

    /** The ETA slip the detector applies to a trip. */
    @Transactional(readOnly = true)
    public Duration etaSlipFor(UUID tripId) {
        return settings.effective(DetectorSettingDefinitions.ETA_SLIP, contextOf(tripId));
    }

    /** The temperature excursion the detector applies to a trip. */
    @Transactional(readOnly = true)
    public Duration temperatureExcursionFor(UUID tripId) {
        return settings.effective(DetectorSettingDefinitions.TEMPERATURE_EXCURSION, contextOf(tripId));
    }

    /** The trip's unit, vehicle and only customer; the tenant's alone for a trip not found. */
    private SettingContext contextOf(UUID tripId) {
        return trips.scopeOf(tripId)
                .map(scope -> SettingContext.ofOrgUnit(scope.orgUnitId()).withVehicle(scope.vehicleId())
                        .withCustomer(scope.soleCustomerId()).withFreightMode(scope.freightMode()))
                .orElse(SettingContext.tenant());
    }

    @Transactional(readOnly = true)
    @PreAuthorize("hasAuthority('EXCEPTION_READ')")
    public Settings view() {
        return effective();
    }

    @Transactional
    @PreAuthorize("hasAuthority('EXCEPTION_MANAGE')")
    public Settings update(int etaSlipMinutes, int temperatureExcursionMinutes) {
        if (etaSlipMinutes < 1 || etaSlipMinutes > MAX_MINUTES
                || temperatureExcursionMinutes < 1 || temperatureExcursionMinutes > MAX_MINUTES) {
            throw new BusinessRuleViolationException("detector-threshold-invalid",
                    "A threshold is between 1 minute and " + MAX_MINUTES + " (one week)");
        }
        Map<SettingKey<?>, Object> values = new HashMap<>();
        values.put(DetectorSettingDefinitions.ETA_SLIP, Duration.ofMinutes(etaSlipMinutes));
        values.put(DetectorSettingDefinitions.TEMPERATURE_EXCURSION, Duration.ofMinutes(temperatureExcursionMinutes));
        settings.setOrgDefaults(values);
        return new Settings(etaSlipMinutes, temperatureExcursionMinutes, true);
    }

    private static int minutes(Duration duration) {
        return (int) duration.toMinutes();
    }
}
