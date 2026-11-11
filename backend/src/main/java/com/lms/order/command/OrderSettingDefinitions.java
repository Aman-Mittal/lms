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
package com.lms.order.command;

import java.math.BigDecimal;
import java.util.List;

import com.lms.settings.api.ScopeType;
import com.lms.settings.api.SettingContributor;
import com.lms.settings.api.SettingDefinition;
import com.lms.settings.api.SettingKey;
import org.springframework.stereotype.Component;

/**
 * The order module's settings. Both may be set for the tenant, for an
 * organisational unit (an order raised there or beneath it) and for a
 * customer (that customer's orders): the order's customer beats its unit,
 * and a deeper unit beats a shallower one.
 */
@Component
public class OrderSettingDefinitions implements SettingContributor {

    public static final SettingKey<BigDecimal> CLOSE_TOLERANCE = SettingKey.decimal("order.close.tolerancePercent");
    public static final SettingKey<Boolean> AUTO_FULFIL = SettingKey.bool("order.close.autoFulfil");

    @Override
    public List<SettingDefinition> definitions() {
        return List.of(
                SettingDefinition.of(CLOSE_TOLERANCE, "Close tolerance (%)",
                                "Share of a fully planned order, by weight of freight, that may still be"
                                        + " undelivered when the platform closes the order by itself."
                                        + " Never applied to a close asked for by hand.")
                        .defaultValue(BigDecimal.ZERO).range(0, 100).scale(2)
                        .alsoAt(ScopeType.ORG_UNIT, ScopeType.CUSTOMER, ScopeType.COMBINATION).build(),
                SettingDefinition.of(AUTO_FULFIL, "Fulfil on delivery",
                                "Whether delivering an order's last consignment fulfils it; off leaves"
                                        + " delivered orders for a person to close.")
                        .defaultValue(true)
                        .alsoAt(ScopeType.ORG_UNIT, ScopeType.CUSTOMER).build());
    }
}
