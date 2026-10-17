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
import java.util.Locale;
import java.util.UUID;

import com.lms.shared.error.BusinessRuleViolationException;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.relational.core.mapping.Table;

/**
 * A pallet, carton or cage that leaves the warehouse as one piece.
 *
 * <p>Filled either by packing a picked order or, for a cross-dock, straight
 * from a receipt at the dock. Dispatch is the hand-off: the contents stop
 * being warehouse stock.
 */
@Table("handling_unit")
public record HandlingUnit(
        @Id UUID id,
        UUID tenantId,
        UUID warehouseId,
        String huNo,
        Source source,
        UUID outboundOrderId,
        UUID asnId,
        Status status,
        String dispatchRef,
        Instant dispatchedAt,
        @Version Long version,
        Instant createdAt) {

    public enum Source {
        PACK,
        CROSS_DOCK
    }

    public enum Status {
        PACKED,
        DISPATCHED
    }

    public static HandlingUnit packedFor(UUID tenantId, UUID warehouseId, UUID outboundOrderId,
                                         Instant at) {
        return new HandlingUnit(UUID.randomUUID(), tenantId, warehouseId, newHuNo(), Source.PACK,
                outboundOrderId, null, Status.PACKED, null, null, null, at);
    }

    public static HandlingUnit crossDockedFrom(UUID tenantId, UUID warehouseId, UUID asnId,
                                               Instant at) {
        return new HandlingUnit(UUID.randomUUID(), tenantId, warehouseId, newHuNo(),
                Source.CROSS_DOCK, null, asnId, Status.PACKED, null, null, null, at);
    }

    public HandlingUnit dispatch(String reference, Instant at) {
        if (status != Status.PACKED) {
            throw new BusinessRuleViolationException("handling-unit-already-dispatched",
                    "Handling unit " + huNo + " was already dispatched at " + dispatchedAt);
        }
        return new HandlingUnit(id, tenantId, warehouseId, huNo, source, outboundOrderId, asnId,
                Status.DISPATCHED, reference, at, version, createdAt);
    }

    /**
     * Random rather than sequential, as with exception tickets: a per-tenant
     * counter would need a lock on every pack, and a clash is caught by the
     * unique constraint regardless.
     */
    private static String newHuNo() {
        return "HU-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
    }
}
