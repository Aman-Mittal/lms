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
 * <p>A line the bins could not fill is left short and the order becomes
 * SHORT_PICKED; what was picked can still be packed and shipped. The unfilled
 * remainder is the order's back-order: once the unit carrying what was found
 * is dispatched the order waits as BACK_ORDERED, and a later pick -- alone or
 * in a wave -- fills it into a further handling unit. An order whose every
 * line is filled is picked no further.
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
        /** Everything ordered has left the warehouse. */
        DISPATCHED,
        /** What was found has left; the rest is owed and waits for stock. */
        BACK_ORDERED
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

    /** Whether a pick may take stock for it: never picked, or owed a back-order. */
    public boolean pickable() {
        return status == Status.OPEN || status == Status.SHORT_PICKED
                || status == Status.BACK_ORDERED;
    }

    public void requirePickable() {
        if (!pickable()) {
            refuse("picked");
        }
    }

    /**
     * Records a pick.
     *
     * <p>A first pick always moves the order on, even when it found nothing:
     * that is a shortfall worth seeing. A further pick that found nothing
     * changes nothing -- the order is still owed exactly what it was.
     *
     * @param movedAnything whether this pick took any stock for the order
     * @param remainder     whether any line is still short afterwards
     */
    public OutboundOrder picked(boolean movedAnything, boolean remainder, Instant at) {
        requirePickable();
        if (status != Status.OPEN && !movedAnything) {
            return this;
        }
        return with(remainder ? Status.SHORT_PICKED : Status.PICKED, at);
    }

    public OutboundOrder packed(Instant at) {
        if (status != Status.PICKED && status != Status.SHORT_PICKED) {
            refuse("packed");
        }
        return with(Status.PACKED, at);
    }

    /**
     * @param backOrderOutstanding whether any line is still short, in which
     *                             case the order is owed a further unit
     */
    public OutboundOrder dispatched(boolean backOrderOutstanding, Instant at) {
        if (status != Status.PACKED) {
            refuse("dispatched");
        }
        return with(backOrderOutstanding ? Status.BACK_ORDERED : Status.DISPATCHED, at);
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
