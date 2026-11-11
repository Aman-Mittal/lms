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

import java.time.LocalDate;
import java.time.ZoneOffset;

import com.lms.settings.api.SettingContext;
import com.lms.settings.query.EffectiveSettingsQuery;
import org.springframework.stereotype.Component;

/**
 * Which day it is, for deciding whether a dated version is in force: the
 * tenant's day in its {@code settings.timeZone}, and UTC for platform values,
 * which belong to no tenant.
 */
@Component
public class SettingCalendar {

    private final EffectiveSettingsQuery effective;

    public SettingCalendar(EffectiveSettingsQuery effective) {
        this.effective = effective;
    }

    public LocalDate today() {
        return effective.today(SettingContext.tenant());
    }

    public LocalDate platformToday() {
        return LocalDate.now(ZoneOffset.UTC);
    }
}
