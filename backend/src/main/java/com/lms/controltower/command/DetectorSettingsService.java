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

import java.util.UUID;

import com.lms.controltower.command.domain.DetectorSetting;
import com.lms.shared.audit.TransitionAudit;
import com.lms.shared.config.Json;
import com.lms.shared.error.BusinessRuleViolationException;
import com.lms.shared.tenant.TenantContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The tolerances the ETA and temperature detectors apply, per tenant.
 *
 * <p>The existing exception model has no rules to hang a threshold on -- its
 * only tolerances are platform-wide properties -- so a tenant's own values are
 * one row, and a tenant that has set none gets the property defaults. Nothing
 * has to be seeded.
 */
@Service
public class DetectorSettingsService {

    private static final int MAX_MINUTES = 10080;

    private final DetectorSettingRepository settings;
    private final TransitionAudit audit;
    private final int defaultEtaSlipMinutes;
    private final int defaultTemperatureMinutes;

    public DetectorSettingsService(DetectorSettingRepository settings, TransitionAudit audit,
                                   @Value("${lms.controltower.eta-slip-minutes:60}")
                                   int defaultEtaSlipMinutes,
                                   @Value("${lms.controltower.temperature-excursion-minutes:15}")
                                   int defaultTemperatureMinutes) {
        this.settings = settings;
        this.audit = audit;
        this.defaultEtaSlipMinutes = defaultEtaSlipMinutes;
        this.defaultTemperatureMinutes = defaultTemperatureMinutes;
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

    /** What the detectors apply. Unguarded: the platform is the caller. */
    @Transactional(readOnly = true)
    public Settings effective() {
        return settings.findByTenant(TenantContext.requireTenantId())
                .map(s -> new Settings(s.etaSlipMinutes(), s.temperatureExcursionMinutes(), true))
                .orElseGet(() -> new Settings(defaultEtaSlipMinutes, defaultTemperatureMinutes,
                        false));
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
        UUID tenantId = TenantContext.requireTenantId();
        Settings before = effective();
        DetectorSetting saved = settings.save(settings.findByTenant(tenantId)
                .map(existing -> existing.with(etaSlipMinutes, temperatureExcursionMinutes))
                .orElseGet(() -> DetectorSetting.of(tenantId, etaSlipMinutes,
                        temperatureExcursionMinutes)));

        audit.record("DETECTOR_SETTINGS_SET", "DETECTOR_SETTING", saved.id(),
                Json.object("etaSlipMinutes", String.valueOf(before.etaSlipMinutes()),
                        "temperatureExcursionMinutes",
                        String.valueOf(before.temperatureExcursionMinutes())),
                Json.object("etaSlipMinutes", String.valueOf(etaSlipMinutes),
                        "temperatureExcursionMinutes",
                        String.valueOf(temperatureExcursionMinutes)));
        return new Settings(etaSlipMinutes, temperatureExcursionMinutes, true);
    }
}
