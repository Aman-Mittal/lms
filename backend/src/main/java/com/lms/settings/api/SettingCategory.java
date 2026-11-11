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
 * What a setting is about, for grouping on a screen and for the rules a
 * definition must keep: a COMPLIANCE setting is always effective-dated.
 */
public enum SettingCategory {
    /** How the platform runs a tenant's operations. */
    OPERATIONS,
    /** How a console looks and behaves for a person. */
    UI,
    /** Which channels a person is told about events on. */
    NOTIFICATIONS,
    /** A regulatory rule; changes only by a new dated version. */
    COMPLIANCE
}
