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
 * The order module's settings. The close settings may be set for the tenant, for an
 * organisational unit (an order raised there or beneath it) and for a
 * customer (that customer's orders): the order's customer beats its unit,
 * and a deeper unit beats a shallower one.
 */
@Component
public class OrderSettingDefinitions implements SettingContributor {

    public static final SettingKey<BigDecimal> CLOSE_TOLERANCE = SettingKey.decimal("order.close.tolerancePercent");
    public static final SettingKey<Boolean> AUTO_FULFIL = SettingKey.bool("order.close.autoFulfil");
    /** How far freight is taken to travel in a day, for an order whose lane names no transit days. */
    public static final SettingKey<Integer> TRANSIT_KM_PER_DAY = SettingKey.integer("order.transit.kmPerDay");

    @Override
    public List<SettingDefinition> definitions() {
        return List.of(
                SettingDefinition.of(CLOSE_TOLERANCE, "Close tolerance (%)",
                                "Share of a fully planned order, by weight of freight, that may still be"
                                        + " undelivered when the platform closes the order by itself."
                                        + " Never applied to a close asked for by hand.")
                        .defaultValue(BigDecimal.ZERO).range(0, 100).scale(2)
                        .alsoAt(ScopeType.ORG_UNIT, ScopeType.CUSTOMER, ScopeType.COMBINATION, ScopeType.CONTRACT)
                        .build(),
                SettingDefinition.of(AUTO_FULFIL, "Fulfil on delivery",
                                "Whether delivering an order's last consignment fulfils it; off leaves"
                                        + " delivered orders for a person to close.")
                        .defaultValue(true)
                        .alsoAt(ScopeType.ORG_UNIT, ScopeType.CUSTOMER).build(),
                SettingDefinition.of(TRANSIT_KM_PER_DAY, "Transit distance per day (km)",
                                "Kilometres freight is taken to travel in a day. An order whose contract lane names"
                                        + " no transit days is promised for the straight-line distance between its"
                                        + " origin and destination divided by this, rounded up.")
                        .defaultValue(400).range(1, 5_000)
                        .alsoAt(ScopeType.ORG_UNIT, ScopeType.CUSTOMER).build());
    }
}
