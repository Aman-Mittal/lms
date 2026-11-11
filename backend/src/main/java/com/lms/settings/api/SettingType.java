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

/**
 * The kind of value a setting holds, and so how a submitted value is checked
 * and how it is stored as JSON.
 */
public enum SettingType {
    /** true or false. */
    BOOLEAN,
    /** A whole number, optionally bounded. */
    INTEGER,
    /** A decimal, optionally bounded, with at most {@code scale} decimal places. */
    DECIMAL,
    /** Text, at most {@code maxLength} characters, optionally of a {@link StringFormat}. */
    STRING,
    /** One of the definition's {@code allowedValues}. */
    ENUM,
    /** An ISO-8601 duration such as {@code PT45M}, optionally bounded. */
    DURATION,
    /** A flat JSON object whose properties the definition names and types. */
    JSON
}
