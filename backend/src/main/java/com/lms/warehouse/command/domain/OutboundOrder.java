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
package com.lms.warehouse.command.domain;

import java.time.Instant;
import java.util.UUID;

import com.lms.shared.error.BusinessRuleViolationException;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.relational.core.mapping.Table;

/**
 * Goods to send out of one warehouse: picked, packed, dispatched.
 *
 * <p>Picked once. A line the bins could not fill is left short and the order
 * becomes SHORT_PICKED; what was picked can still be packed and shipped.
 * Back-ordering the remainder is a new order, not a second pick of this one.
 */
@Table("outbound_order")
public record OutboundOrder(
        @Id UUID id,
        UUID tenantId,
        UUID warehouseId,
        String orderNo,
        String shipTo,
        Status status,
        @Version Long version,
        Instant createdAt,
        Instant updatedAt) {

    public enum Status {
        OPEN,
        PICKED,
        /** Picked as far as stock allowed; at least one line is short. */
        SHORT_PICKED,
        PACKED,
        DISPATCHED
    }

    public static OutboundOrder open(UUID tenantId, UUID warehouseId, String orderNo,
                                     String shipTo, Instant at) {
        if (orderNo == null || orderNo.isBlank()) {
            throw new BusinessRuleViolationException("outbound-order-number-required",
                    "An outbound order needs a number");
        }
        return new OutboundOrder(UUID.randomUUID(), tenantId, warehouseId, orderNo.strip(),
                shipTo, Status.OPEN, null, at, at);
    }

    public OutboundOrder picked(boolean shortfall, Instant at) {
        if (status != Status.OPEN) {
            refuse("picked");
        }
        return with(shortfall ? Status.SHORT_PICKED : Status.PICKED, at);
    }

    public OutboundOrder packed(Instant at) {
        if (status != Status.PICKED && status != Status.SHORT_PICKED) {
            refuse("packed");
        }
        return with(Status.PACKED, at);
    }

    public OutboundOrder dispatched(Instant at) {
        if (status != Status.PACKED) {
            refuse("dispatched");
        }
        return with(Status.DISPATCHED, at);
    }

    private void refuse(String what) {
        throw new BusinessRuleViolationException("outbound-order-illegal-transition",
                "Outbound order " + orderNo + " is " + status + " and cannot be " + what);
    }

    private OutboundOrder with(Status next, Instant at) {
        return new OutboundOrder(id, tenantId, warehouseId, orderNo, shipTo, next, version,
                createdAt, at);
    }
}
