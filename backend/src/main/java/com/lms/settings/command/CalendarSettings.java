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

import java.util.List;

import com.lms.settings.api.ScopeType;
import com.lms.settings.api.SettingContributor;
import com.lms.settings.api.SettingDefinition;
import com.lms.settings.api.SettingKey;
import com.lms.settings.api.StringFormat;
import org.springframework.stereotype.Component;

/**
 * The settings module's own setting: the time zone in which a dated version's
 * first day begins for a tenant. The platform may set it per jurisdiction, a
 * tenant for itself.
 */
@Component
public class CalendarSettings implements SettingContributor {

    public static final SettingKey<String> TIME_ZONE = SettingKey.string("settings.timeZone");

    @Override
    public List<SettingDefinition> definitions() {
        return List.of(SettingDefinition.of(TIME_ZONE, "Settings time zone",
                        "The time zone in which a dated setting's first day begins, and so what today is when"
                                + " judging which version is in force.")
                .defaultValue("UTC").format(StringFormat.TIME_ZONE).maxLength(64)
                .alsoAt(ScopeType.PLATFORM).build());
    }
}
