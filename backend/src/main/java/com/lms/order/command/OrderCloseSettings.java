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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.lms.settings.api.EffectiveSettings;
import com.lms.settings.api.SettingContext;
import com.lms.settings.api.SettingKey;
import com.lms.settings.api.Settings;
import com.lms.shared.error.BusinessRuleViolationException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The tenant's close policy, read and written through the settings module
 * ({@link OrderSettingDefinitions}). Without any profile: no tolerance, and
 * delivery of the last consignment fulfils the order.
 *
 * <p>{@code /orders/close-settings} reads and writes the organisation
 * default, as it did when the policy was one row per tenant. What the
 * platform applies to an order is resolved for that order
 * ({@link #effectiveFor}), so a profile for its unit or its customer wins.
 */
@Service
public class OrderCloseSettings {

    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private static final List<SettingKey<?>> KEYS =
            List.of(OrderSettingDefinitions.CLOSE_TOLERANCE, OrderSettingDefinitions.AUTO_FULFIL);

    private final Settings settings;

    public OrderCloseSettings(Settings settings) {
        this.settings = settings;
    }

    /**
     * @param tolerancePercent share of an order's quantity that may be left
     *                         unplanned when its last consignment is delivered
     * @param autoFulfil       false: delivery leaves the order open until a
     *                         person closes it
     * @param customised       false when no profile sets either value
     */
    public record Policy(BigDecimal tolerancePercent, boolean autoFulfil, boolean customised) {
    }

    /** The organisation default. Unguarded: the platform is the caller. */
    @Transactional(readOnly = true)
    public Policy effective() {
        return policy(SettingContext.tenant());
    }

    /** What applies to an order raised at {@code orgUnitId} for {@code customerId}. Unguarded. */
    @Transactional(readOnly = true)
    public Policy effectiveFor(UUID orgUnitId, UUID customerId) {
        return policy(SettingContext.ofOrgUnit(orgUnitId).withCustomer(customerId));
    }

    @Transactional(readOnly = true)
    @PreAuthorize("hasAuthority('ORDER_READ')")
    public Policy view() {
        return effective();
    }

    /**
     * Sets the organisation default. Held to {@code ORDER_FORCE_CLOSE}: a
     * tolerance closes orders nobody asked to close, and switching fulfilment
     * off keeps open orders that would have closed, so it belongs to whoever
     * may end an order regardless of its freight. The settings history of the
     * organisation default records the change.
     */
    @Transactional
    @PreAuthorize("hasAuthority('ORDER_FORCE_CLOSE')")
    public Policy update(BigDecimal tolerancePercent, boolean autoFulfil) {
        if (tolerancePercent == null || tolerancePercent.signum() < 0
                || tolerancePercent.compareTo(HUNDRED) > 0
                || tolerancePercent.stripTrailingZeros().scale() > 2) {
            throw new BusinessRuleViolationException("close-tolerance-out-of-range",
                    "A close tolerance is a percentage from 0 to 100, to two decimal places");
        }
        Map<SettingKey<?>, Object> values = new HashMap<>();
        values.put(OrderSettingDefinitions.CLOSE_TOLERANCE, tolerancePercent);
        values.put(OrderSettingDefinitions.AUTO_FULFIL, autoFulfil);
        settings.setOrgDefaults(values);
        return new Policy(tolerancePercent, autoFulfil, true);
    }

    private Policy policy(SettingContext context) {
        EffectiveSettings resolved = settings.resolveAll(KEYS, context);
        boolean customised = !resolved.resolved(OrderSettingDefinitions.CLOSE_TOLERANCE).source().isSystemDefault()
                || !resolved.resolved(OrderSettingDefinitions.AUTO_FULFIL).source().isSystemDefault();
        return new Policy(resolved.get(OrderSettingDefinitions.CLOSE_TOLERANCE),
                resolved.get(OrderSettingDefinitions.AUTO_FULFIL), customised);
    }
}
