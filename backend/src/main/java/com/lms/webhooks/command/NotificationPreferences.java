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
package com.lms.webhooks.command;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.lms.settings.api.ScopeType;
import com.lms.settings.api.SettingCategory;
import com.lms.settings.api.SettingContributor;
import com.lms.settings.api.SettingDefinition;
import com.lms.settings.api.SettingKey;
import org.springframework.stereotype.Component;

/**
 * Which channels a person wants to hear about each event on:
 * {@code notify.<event>.inApp} and {@code notify.<event>.email} for every
 * subscribable {@link WebhookEventType}, the event in lower camel case
 * ({@code notify.orderClosed.email}).
 *
 * <p>Preferences only. The platform has no notification engine (deferred by
 * design), so nothing sends an e-mail or an in-app message from these yet;
 * they are recorded so that one, when built, reads what people already chose.
 */
@Component
public class NotificationPreferences implements SettingContributor {

    /** The key of one event's channel. */
    public static SettingKey<Boolean> key(WebhookEventType event, String channel) {
        return SettingKey.bool("notify." + camel(event) + "." + channel);
    }

    @Override
    public List<SettingDefinition> definitions() {
        List<SettingDefinition> out = new ArrayList<>();
        for (WebhookEventType event : WebhookEventType.values()) {
            if (!event.subscribable()) {
                continue;
            }
            String what = event.name().toLowerCase(Locale.ROOT).replace('_', ' ');
            out.add(SettingDefinition.of(key(event, "inApp"), "In-app: " + what,
                            "Show " + what + " in the console's notifications.")
                    .defaultValue(true).category(SettingCategory.NOTIFICATIONS).alsoAt(ScopeType.ORG_UNIT, ScopeType.USER).build());
            out.add(SettingDefinition.of(key(event, "email"), "E-mail: " + what,
                            "Send " + what + " by e-mail.")
                    .defaultValue(false).category(SettingCategory.NOTIFICATIONS).alsoAt(ScopeType.ORG_UNIT, ScopeType.USER).build());
        }
        return out;
    }

    static String camel(WebhookEventType event) {
        StringBuilder out = new StringBuilder();
        for (String part : event.name().toLowerCase(Locale.ROOT).split("_")) {
            out.append(out.isEmpty() ? part : Character.toUpperCase(part.charAt(0)) + part.substring(1));
        }
        return out.toString();
    }
}
