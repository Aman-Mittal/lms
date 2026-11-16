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
package com.lms.statutory.command;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import com.lms.settings.api.EffectiveSettings;
import com.lms.settings.api.Settings;
import org.springframework.stereotype.Component;

/**
 * The e-way bill keys resolved for one organisational unit, in one read. Joins
 * the caller's transaction through the settings port (ADR 0006), so it is
 * called inside the short transaction of a request and never during an
 * adapter call.
 */
@Component
class EwayBillSettings {

    /** The resolved values. {@code gstin} and {@code credentialRef} are null when the unit set none. */
    record Config(String adapter, boolean requiredAtDispatch, int cancelWindowHours, int expiringSoonHours,
                  int timeoutSeconds, int kmPerDay, int defaultDistanceKm, int extensionHours, String gstin,
                  String credentialRef) {

        boolean enabled() {
            return !StatutorySettingDefinitions.NONE.equals(adapter);
        }

        Duration timeout() {
            return Duration.ofSeconds(timeoutSeconds);
        }
    }

    private final Settings settings;

    EwayBillSettings(Settings settings) {
        this.settings = settings;
    }

    Config forUnit(UUID orgUnitId) {
        EffectiveSettings values = settings.resolveAll(StatutorySettingDefinitions.all(),
                StatutorySettingDefinitions.contextOf(orgUnitId));
        Map<String, Object> profile = values.get(StatutorySettingDefinitions.PROFILE);
        return new Config(values.get(StatutorySettingDefinitions.ADAPTER),
                values.get(StatutorySettingDefinitions.REQUIRED_AT_DISPATCH),
                values.get(StatutorySettingDefinitions.CANCEL_WINDOW_HOURS),
                values.get(StatutorySettingDefinitions.EXPIRING_SOON_HOURS),
                values.get(StatutorySettingDefinitions.ADAPTER_TIMEOUT_SECONDS),
                values.get(StatutorySettingDefinitions.KM_PER_DAY),
                values.get(StatutorySettingDefinitions.DEFAULT_DISTANCE_KM),
                values.get(StatutorySettingDefinitions.EXTENSION_HOURS),
                text(profile, "gstin"), text(profile, "credentialRef"));
    }

    private static String text(Map<String, Object> profile, String name) {
        Object value = profile == null ? null : profile.get(name);
        return value instanceof String s && !s.isBlank() ? s : null;
    }
}
