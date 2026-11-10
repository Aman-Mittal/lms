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
package com.lms.order.command.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.relational.core.mapping.Table;

/**
 * A tenant's close policy: how much of an order may be left unplanned when its
 * last consignment is delivered for it still to close by itself, and whether
 * delivery closes orders at all.
 */
@Table("order_close_setting")
public record OrderCloseSetting(
        @Id UUID id,
        UUID tenantId,
        BigDecimal tolerancePercent,
        boolean autoFulfil,
        @Version Long version,
        Instant createdAt) {

    public static OrderCloseSetting of(UUID tenantId, BigDecimal tolerancePercent, boolean autoFulfil) {
        return new OrderCloseSetting(UUID.randomUUID(), tenantId, tolerancePercent, autoFulfil, null,
                Instant.now());
    }

    public OrderCloseSetting with(BigDecimal tolerancePercent, boolean autoFulfil) {
        return new OrderCloseSetting(id, tenantId, tolerancePercent, autoFulfil, version, createdAt);
    }
}
