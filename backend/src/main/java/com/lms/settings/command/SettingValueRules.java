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

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Duration;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.IllformedLocaleException;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

import com.lms.settings.api.SettingDefinition;
import com.lms.settings.api.SettingType;

/**
 * Whether a value suits a definition, and the value in its one canonical form.
 *
 * <p>Pure: the same rules check a submitted value, a system default when the
 * registry starts, and a stored value when it is read back -- a value stored
 * under looser bounds than today's is not applied, the system default is.
 */
public final class SettingValueRules {

    private static final Pattern LANGUAGE_TAG = Pattern.compile("^[a-z]{2,3}(-[A-Z][a-z]{3})?(-([A-Z]{2}|[0-9]{3}))?$");

    private SettingValueRules() {
    }

    /** A value refused, with the reason in words a person can act on. */
    public static final class InvalidValue extends RuntimeException {

        InvalidValue(String reason) {
            super(reason);
        }
    }

    /**
     * The canonical value: Boolean, Integer, BigDecimal, String, Duration, or a
     * sorted Map for JSON.
     *
     * @throws InvalidValue when {@code raw} does not suit the definition
     */
    public static Object coerce(SettingDefinition d, Object raw) {
        if (raw == null) {
            throw new InvalidValue("a value is required");
        }
        return switch (d.type()) {
            case BOOLEAN -> bool(raw);
            case INTEGER -> integer(raw, d);
            case DECIMAL -> decimal(raw, d);
            case STRING -> string(raw, d);
            case ENUM -> enumeration(raw, d);
            case DURATION -> duration(raw, d);
            case JSON -> object(raw, d);
        };
    }

    /** How a canonical value is written into JSON: a duration as its ISO-8601 text. */
    public static Object toJson(Object canonical) {
        return canonical instanceof Duration duration ? duration.toString() : canonical;
    }

    private static Boolean bool(Object raw) {
        if (raw instanceof Boolean b) {
            return b;
        }
        throw new InvalidValue("must be true or false");
    }

    private static Integer integer(Object raw, SettingDefinition d) {
        BigDecimal number = number(raw, "a whole number");
        if (number.stripTrailingZeros().scale() > 0) {
            throw new InvalidValue("must be a whole number");
        }
        within(number, d);
        try {
            return number.intValueExact();
        } catch (ArithmeticException e) {
            throw new InvalidValue("is too large");
        }
    }

    private static BigDecimal decimal(Object raw, SettingDefinition d) {
        BigDecimal number = number(raw, "a number");
        BigDecimal plain = number.stripTrailingZeros();
        if (plain.scale() < 0) {
            plain = plain.setScale(0);
        }
        if (plain.scale() > d.scale()) {
            throw new InvalidValue("must have at most " + d.scale() + " decimal places");
        }
        within(plain, d);
        return plain;
    }

    private static BigDecimal number(Object raw, String what) {
        if (raw instanceof BigDecimal n) {
            return n;
        }
        if (raw instanceof Integer || raw instanceof Long || raw instanceof Short || raw instanceof Byte) {
            return BigDecimal.valueOf(((Number) raw).longValue());
        }
        if (raw instanceof BigInteger n) {
            return new BigDecimal(n);
        }
        if (raw instanceof Double || raw instanceof Float) {
            double value = ((Number) raw).doubleValue();
            if (Double.isFinite(value)) {
                return new BigDecimal(raw.toString());
            }
        }
        throw new InvalidValue("must be " + what);
    }

    private static void within(BigDecimal number, SettingDefinition d) {
        if ((d.min() != null && number.compareTo(d.min()) < 0)
                || (d.max() != null && number.compareTo(d.max()) > 0)) {
            throw new InvalidValue("must be " + bounds(d));
        }
    }

    private static String bounds(SettingDefinition d) {
        if (d.min() != null && d.max() != null) {
            return "from " + d.min().toPlainString() + " to " + d.max().toPlainString();
        }
        return d.min() != null ? "at least " + d.min().toPlainString() : "at most " + d.max().toPlainString();
    }

    private static String string(Object raw, SettingDefinition d) {
        if (!(raw instanceof String s) || s.isBlank()) {
            throw new InvalidValue("must be text");
        }
        if (s.length() > d.maxLength()) {
            throw new InvalidValue("must be at most " + d.maxLength() + " characters");
        }
        switch (d.format()) {
            case LOCALE -> {
                if (!LANGUAGE_TAG.matcher(s).matches()) {
                    throw new InvalidValue("must be a language tag such as en-GB");
                }
                try {
                    return new Locale.Builder().setLanguageTag(s).build().toLanguageTag();
                } catch (IllformedLocaleException e) {
                    throw new InvalidValue("must be a language tag such as en-GB");
                }
            }
            case TIME_ZONE -> {
                // Region ids only: a fixed offset such as +05:30 ignores
                // daylight saving where the place has it.
                if (!"UTC".equals(s) && !ZoneId.getAvailableZoneIds().contains(s)) {
                    throw new InvalidValue("must be a time-zone id such as Asia/Kolkata or UTC");
                }
                return s;
            }
            default -> {
                return s;
            }
        }
    }

    private static String enumeration(Object raw, SettingDefinition d) {
        if (raw instanceof String s && d.allowedValues().contains(s)) {
            return s;
        }
        throw new InvalidValue("must be one of " + String.join(", ", d.allowedValues()));
    }

    private static Duration duration(Object raw, SettingDefinition d) {
        Duration value;
        try {
            value = raw instanceof Duration given ? given
                    : raw instanceof String s ? Duration.parse(s) : null;
        } catch (DateTimeParseException e) {
            value = null;
        }
        if (value == null || value.isNegative()) {
            throw new InvalidValue("must be an ISO-8601 duration such as PT45M");
        }
        if ((d.minDuration() != null && value.compareTo(d.minDuration()) < 0)
                || (d.maxDuration() != null && value.compareTo(d.maxDuration()) > 0)) {
            throw new InvalidValue("must be from " + d.minDuration() + " to " + d.maxDuration());
        }
        return value;
    }

    private static Map<String, Object> object(Object raw, SettingDefinition d) {
        if (!(raw instanceof Map<?, ?> map)) {
            throw new InvalidValue("must be an object");
        }
        Map<String, Object> clean = new TreeMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            String name = String.valueOf(entry.getKey());
            SettingType type = d.properties().get(name);
            if (type == null) {
                throw new InvalidValue("has no property '" + name + "'");
            }
            if (entry.getValue() == null) {
                continue;
            }
            Object value = switch (type) {
                case BOOLEAN -> entry.getValue() instanceof Boolean b ? b : null;
                case INTEGER, DECIMAL -> {
                    try {
                        BigDecimal n = number(entry.getValue(), "a number");
                        yield type == SettingType.INTEGER && n.stripTrailingZeros().scale() > 0 ? null : n;
                    } catch (InvalidValue e) {
                        yield null;
                    }
                }
                case STRING -> entry.getValue() instanceof String s && !s.isBlank()
                        && s.length() <= SettingDefinition.MAX_STRING ? s : null;
                default -> null;
            };
            if (value == null) {
                throw new InvalidValue("property '" + name + "' must be " + describe(type));
            }
            if (value instanceof BigDecimal n && type == SettingType.INTEGER) {
                try {
                    value = n.intValueExact();
                } catch (ArithmeticException e) {
                    throw new InvalidValue("property '" + name + "' is too large");
                }
            }
            clean.put(name, value);
        }
        for (String name : d.required()) {
            if (!clean.containsKey(name)) {
                throw new InvalidValue("property '" + name + "' is required");
            }
        }
        return clean;
    }

    private static String describe(SettingType type) {
        return switch (type) {
            case BOOLEAN -> "true or false";
            case INTEGER -> "a whole number";
            case DECIMAL -> "a number";
            default -> "text";
        };
    }
}
