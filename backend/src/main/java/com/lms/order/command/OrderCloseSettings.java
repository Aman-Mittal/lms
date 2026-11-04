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
import java.util.UUID;

import com.lms.order.command.domain.OrderCloseSetting;
import com.lms.shared.audit.TransitionAudit;
import com.lms.shared.config.Json;
import com.lms.shared.error.BusinessRuleViolationException;
import com.lms.shared.tenant.TenantContext;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The tenant's close policy, with defaults for a tenant that has set none: no
 * tolerance, and delivery of the last consignment fulfils the order.
 */
@Service
public class OrderCloseSettings {

    /** The audit resource type of a change to the policy. */
    static final String AUDIT_TYPE = "OrderCloseSetting";

    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private final OrderCloseSettingRepository settings;
    private final TransitionAudit audit;

    public OrderCloseSettings(OrderCloseSettingRepository settings, TransitionAudit audit) {
        this.settings = settings;
        this.audit = audit;
    }

    /**
     * @param tolerancePercent share of an order's quantity that may be left
     *                         unplanned when its last consignment is delivered
     * @param autoFulfil       false: delivery leaves the order open until a
     *                         person closes it
     * @param customised       false when the tenant has set nothing
     */
    public record Policy(BigDecimal tolerancePercent, boolean autoFulfil, boolean customised) {
    }

    /** What the platform applies. Unguarded: planning's delivery report is the caller. */
    @Transactional(readOnly = true)
    public Policy effective() {
        return settings.findByTenant(TenantContext.requireTenantId())
                .map(s -> new Policy(s.tolerancePercent(), s.autoFulfil(), true))
                .orElseGet(() -> new Policy(BigDecimal.ZERO, true, false));
    }

    @Transactional(readOnly = true)
    @PreAuthorize("hasAuthority('ORDER_READ')")
    public Policy view() {
        return effective();
    }

    /**
     * Sets the policy. Held to {@code ORDER_FORCE_CLOSE}: a tolerance closes
     * orders nobody asked to close, and switching fulfilment off keeps open
     * orders that would have closed, so it belongs to whoever may end an order
     * regardless of its freight.
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
        UUID tenantId = TenantContext.requireTenantId();
        Policy before = effective();
        OrderCloseSetting saved = settings.save(settings.findByTenant(tenantId)
                .map(existing -> existing.with(tolerancePercent, autoFulfil))
                .orElseGet(() -> OrderCloseSetting.of(tenantId, tolerancePercent, autoFulfil)));
        audit.record("ORDER_CLOSE_SETTINGS_SET", AUDIT_TYPE, saved.id(),
                Json.object("tolerancePercent", before.tolerancePercent().toPlainString(),
                        "autoFulfil", String.valueOf(before.autoFulfil())),
                Json.object("tolerancePercent", tolerancePercent.toPlainString(),
                        "autoFulfil", String.valueOf(autoFulfil)));
        return new Policy(tolerancePercent, autoFulfil, true);
    }
}
