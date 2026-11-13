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
package com.lms.delivery.command;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import com.lms.settings.api.EffectiveSettings;
import com.lms.settings.api.SettingContext;
import com.lms.settings.api.SettingKey;
import com.lms.settings.api.Settings;
import com.lms.shared.error.ResourceNotFoundException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Where a consignment's order puts its proof of delivery in the settings
 * hierarchy, and what the settings there say. Read under row-level security,
 * so another tenant's consignment is absent.
 */
@Component
public class PodPolicy {

    private static final List<SettingKey<?>> KEYS = List.of(
            DeliverySettingDefinitions.POD_QUANTITY_TOLERANCE, DeliverySettingDefinitions.POD_FEEDING_REQUIRED,
            DeliverySettingDefinitions.POD_FRONT_REQUIRED, DeliverySettingDefinitions.POD_BACK_REQUIRED);

    /**
     * @param tolerancePercent how far the arrived quantity may differ from the gross, in percent of it
     */
    public record Rules(BigDecimal tolerancePercent, boolean feedingRequired, boolean frontRequired,
                        boolean backRequired) {
    }

    private final JdbcClient jdbc;
    private final Settings settings;

    public PodPolicy(JdbcClient jdbc, Settings settings) {
        this.jdbc = jdbc;
        this.settings = settings;
    }

    /**
     * What the consignment's order says about where a policy applies: its
     * unit, customer and freight mode. A consignment with no order (a
     * warehouse hand-off) has none of them.
     */
    public SettingContext scopeOf(UUID consignmentId) {
        return jdbc.sql("""
                        SELECT so.org_unit_id, so.customer_partner_id, so.freight_mode
                          FROM consignment c
                          LEFT JOIN sales_order so ON so.id = c.order_id
                         WHERE c.id = :id
                        """)
                .param("id", consignmentId)
                .query((rs, row) -> SettingContext.ofOrgUnit(rs.getObject("org_unit_id", UUID.class))
                        .withCustomer(rs.getObject("customer_partner_id", UUID.class))
                        .withFreightMode(rs.getString("freight_mode")))
                .optional()
                .orElseThrow(() -> new ResourceNotFoundException("Consignment", consignmentId));
    }

    public Rules rulesFor(UUID consignmentId) {
        EffectiveSettings resolved = settings.resolveAll(KEYS, scopeOf(consignmentId));
        return new Rules(resolved.get(DeliverySettingDefinitions.POD_QUANTITY_TOLERANCE),
                resolved.get(DeliverySettingDefinitions.POD_FEEDING_REQUIRED),
                resolved.get(DeliverySettingDefinitions.POD_FRONT_REQUIRED),
                resolved.get(DeliverySettingDefinitions.POD_BACK_REQUIRED));
    }
}
