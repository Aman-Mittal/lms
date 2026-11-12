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
import com.lms.settings.api.SettingCategory;
import com.lms.settings.api.SettingContributor;
import com.lms.settings.api.SettingDefinition;
import com.lms.settings.api.SettingKey;
import com.lms.settings.api.StringFormat;
import org.springframework.stereotype.Component;

/**
 * The consoles' display preferences. A person sets them for themself; the
 * tenant may set a default for everyone, or for a unit, beneath that. The
 * consoles apply them -- the server formats nothing differently.
 */
@Component
public class DisplayPreferences implements SettingContributor {

    public static final SettingKey<String> LOCALE = SettingKey.string("ui.locale");
    public static final SettingKey<String> TIME_ZONE = SettingKey.string("ui.timeZone");
    public static final SettingKey<String> DATE_FORMAT = SettingKey.enumeration("ui.dateFormat");
    public static final SettingKey<String> THEME = SettingKey.enumeration("ui.theme");
    public static final SettingKey<Integer> PAGE_SIZE = SettingKey.integer("ui.defaultPageSize");
    public static final SettingKey<String> LANDING_PAGE = SettingKey.enumeration("ui.landingPage");
    /** Whether the consoles keep map geometry, tracks and tiles in the browser (DOCS/adr/0007). */
    public static final SettingKey<Boolean> CLIENT_CACHE = SettingKey.bool("ui.clientCache");

    @Override
    public List<SettingDefinition> definitions() {
        return List.of(
                SettingDefinition.of(LOCALE, "Language",
                                "The language tag a console formats numbers and dates for.")
                        .defaultValue("en-GB").format(StringFormat.LOCALE).maxLength(20)
                        .category(SettingCategory.UI).alsoAt(ScopeType.ORG_UNIT, ScopeType.USER).build(),
                SettingDefinition.of(TIME_ZONE, "Time zone",
                                "The time zone a console shows instants in.")
                        .defaultValue("UTC").format(StringFormat.TIME_ZONE).maxLength(64)
                        .category(SettingCategory.UI).alsoAt(ScopeType.ORG_UNIT, ScopeType.USER).build(),
                SettingDefinition.of(DATE_FORMAT, "Date format", "How a console writes a date.")
                        .defaultValue("YYYY-MM-DD").values("YYYY-MM-DD", "DD/MM/YYYY", "MM/DD/YYYY", "DD MMM YYYY")
                        .category(SettingCategory.UI).alsoAt(ScopeType.ORG_UNIT, ScopeType.USER).build(),
                SettingDefinition.of(THEME, "Theme", "Light, dark, or whatever the device prefers.")
                        .defaultValue("system").values("light", "dark", "system")
                        .category(SettingCategory.UI).alsoAt(ScopeType.ORG_UNIT, ScopeType.USER).build(),
                SettingDefinition.of(PAGE_SIZE, "Rows per page",
                                "How many rows a console's listings ask for at a time.")
                        .defaultValue(50).range(10, 200)
                        .category(SettingCategory.UI).alsoAt(ScopeType.ORG_UNIT, ScopeType.USER).build(),
                SettingDefinition.of(LANDING_PAGE, "Landing page", "The screen a console opens after sign-in.")
                        .defaultValue("dashboard")
                        .values("dashboard", "order-desk", "consignments", "load-building", "exception-desk",
                                "warehouse", "client-billing", "delivery", "map")
                        .category(SettingCategory.UI).alsoAt(ScopeType.ORG_UNIT, ScopeType.USER).build(),
                SettingDefinition.of(CLIENT_CACHE, "Client-side cache",
                                "Whether a console keeps maps, tracks and tiles in the browser so the server "
                                        + "is asked for less. Off on a shared device, or where policy forbids it.")
                        .defaultValue(true)
                        .category(SettingCategory.UI).alsoAt(ScopeType.ORG_UNIT).build());
    }
}
