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
package com.lms.settings.api;

import java.util.Map;

/** Several effective values resolved together, in one read. */
public record EffectiveSettings(Map<String, Resolved<Object>> values) {

    public EffectiveSettings {
        values = Map.copyOf(values);
    }

    public <T> Resolved<T> resolved(SettingKey<T> key) {
        Resolved<Object> found = values.get(key.name());
        if (found == null) {
            throw new IllegalArgumentException("Setting " + key.name() + " was not resolved here");
        }
        return new Resolved<>(found.key(), key.javaType().cast(found.value()), found.source());
    }

    public <T> T get(SettingKey<T> key) {
        return resolved(key).value();
    }
}
