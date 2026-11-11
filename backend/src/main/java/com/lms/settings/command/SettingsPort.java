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
package com.lms.settings.command;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.lms.settings.api.EffectiveSettings;
import com.lms.settings.api.Resolved;
import com.lms.settings.api.SettingContext;
import com.lms.settings.api.SettingDefinition;
import com.lms.settings.api.SettingKey;
import com.lms.settings.api.Settings;
import com.lms.settings.query.EffectiveSettingsQuery;
import org.springframework.stereotype.Component;

/** The {@link Settings} port, over the effective-settings read and the profile service. */
@Component
class SettingsPort implements Settings {

    private final SettingRegistry registry;
    private final EffectiveSettingsQuery effective;
    private final SettingProfileService profiles;

    SettingsPort(SettingRegistry registry, EffectiveSettingsQuery effective,
                 SettingProfileService profiles) {
        this.registry = registry;
        this.effective = effective;
        this.profiles = profiles;
    }

    @Override
    public <T> T effective(SettingKey<T> key, SettingContext context) {
        return resolve(key, context).value();
    }

    @Override
    public <T> Resolved<T> resolve(SettingKey<T> key, SettingContext context) {
        return resolveAll(List.of(key), context).resolved(key);
    }

    @Override
    public EffectiveSettings resolveAll(Collection<SettingKey<?>> keys, SettingContext context) {
        List<SettingDefinition> wanted = keys.stream().<SettingDefinition>map(registry::require).toList();
        return new EffectiveSettings(effective.resolve(wanted, context));
    }

    @Override
    public void setOrgDefaults(Map<SettingKey<?>, Object> values) {
        Map<String, Object> byName = new LinkedHashMap<>();
        values.forEach((key, value) -> {
            registry.require(key);
            byName.put(key.name(), value);
        });
        profiles.setOrgDefaults(byName);
    }
}
