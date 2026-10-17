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

import java.util.UUID;

import com.lms.shared.error.BusinessRuleViolationException;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.relational.core.mapping.Table;

/** One SKU requested on an outbound order, and how many were found. */
@Table("outbound_line")
public record OutboundLine(
        @Id UUID id,
        UUID tenantId,
        UUID orderId,
        int lineNo,
        UUID skuId,
        int orderedQty,
        int pickedQty,
        @Version Long version) {

    public static OutboundLine request(UUID tenantId, UUID orderId, int lineNo, UUID skuId,
                                       int orderedQty) {
        if (orderedQty <= 0) {
            throw new BusinessRuleViolationException("outbound-line-quantity-invalid",
                    "Outbound line " + lineNo + " must ask for at least one unit");
        }
        return new OutboundLine(UUID.randomUUID(), tenantId, orderId, lineNo, skuId, orderedQty,
                0, null);
    }

    public OutboundLine withPicked(int picked) {
        if (picked < 0 || picked > orderedQty) {
            throw new BusinessRuleViolationException("pick-quantity-invalid",
                    "Line " + lineNo + " cannot have " + picked + " of " + orderedQty + " picked");
        }
        return new OutboundLine(id, tenantId, orderId, lineNo, skuId, orderedQty, picked, version);
    }

    public int shortQty() {
        return orderedQty - pickedQty;
    }
}
