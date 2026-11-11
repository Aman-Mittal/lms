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

/** A further check on a {@link SettingType#STRING} value. */
public enum StringFormat {
    /** Any text within the length. */
    TEXT,
    /** An IETF language tag the platform can read back, such as {@code en-GB}. */
    LOCALE,
    /** A region-based time-zone id, such as {@code Asia/Kolkata} or {@code UTC}. */
    TIME_ZONE
}
