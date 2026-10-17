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

import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.relational.core.mapping.Table;

/**
 * One signed change of stock at one location. Written once, never changed.
 *
 * <p>Saved only as new: the database refuses an UPDATE or DELETE on the
 * ledger, and derives the balance at the location from the insert.
 * {@code version} is here because every saved record carries one -- it is
 * what makes {@code save()} insert rather than silently update nothing.
 */
@Table("stock_movement")
public record StockMovement(
        @Id UUID id,
        UUID tenantId,
        UUID warehouseId,
        MovementType movementType,
        LocationType locationType,
        UUID locationId,
        UUID skuId,
        String lotNo,
        int quantity,
        UUID referenceId,
        String reason,
        String actor,
        Instant occurredAt,
        @Version Long version) {

    public static StockMovement of(UUID tenantId, UUID warehouseId, MovementType type,
                                   LocationType locationType, UUID locationId, UUID skuId,
                                   String lotNo, int quantity, UUID referenceId, String reason,
                                   String actor, Instant at) {
        if (quantity == 0) {
            throw new IllegalArgumentException("A movement of nothing is not a movement");
        }
        return new StockMovement(UUID.randomUUID(), tenantId, warehouseId, type, locationType,
                locationId, skuId, lotNo == null ? "" : lotNo, quantity, referenceId, reason,
                actor, at, null);
    }
}
