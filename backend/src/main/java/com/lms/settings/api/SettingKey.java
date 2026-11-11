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

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * A typed handle on one setting, held as a constant by the module that owns it.
 *
 * <p>The name is dotted, owner first ({@code order.close.tolerancePercent}),
 * each segment starting with a lower-case letter. The Java type follows the
 * setting type: BOOLEAN is {@link Boolean}, INTEGER {@link Integer}, DECIMAL
 * {@link BigDecimal}, STRING and ENUM {@link String}, DURATION
 * {@link Duration} and JSON a {@link Map}.
 */
public record SettingKey<T>(String name, SettingType type, Class<T> javaType) {

    private static final Pattern NAME =
            Pattern.compile("^[a-z][a-zA-Z0-9]*(\\.[a-z][a-zA-Z0-9]*)+$");

    /** Longer than any name worth typing; the column is text, the API is not. */
    public static final int MAX_NAME = 100;

    public SettingKey {
        if (name == null || name.length() > MAX_NAME || !NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("'" + name + "' is not a setting key: dotted"
                    + " segments, each starting with a lower-case letter, owner first");
        }
    }

    /** Whether {@code candidate} is shaped like a key, before asking whether one is registered. */
    public static boolean wellFormed(String candidate) {
        return candidate != null && candidate.length() <= MAX_NAME && NAME.matcher(candidate).matches();
    }

    public static SettingKey<Boolean> bool(String name) {
        return new SettingKey<>(name, SettingType.BOOLEAN, Boolean.class);
    }

    public static SettingKey<Integer> integer(String name) {
        return new SettingKey<>(name, SettingType.INTEGER, Integer.class);
    }

    public static SettingKey<BigDecimal> decimal(String name) {
        return new SettingKey<>(name, SettingType.DECIMAL, BigDecimal.class);
    }

    public static SettingKey<String> string(String name) {
        return new SettingKey<>(name, SettingType.STRING, String.class);
    }

    public static SettingKey<String> enumeration(String name) {
        return new SettingKey<>(name, SettingType.ENUM, String.class);
    }

    public static SettingKey<Duration> duration(String name) {
        return new SettingKey<>(name, SettingType.DURATION, Duration.class);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public static SettingKey<Map<String, Object>> json(String name) {
        return new SettingKey<>(name, SettingType.JSON, (Class) Map.class);
    }
}
