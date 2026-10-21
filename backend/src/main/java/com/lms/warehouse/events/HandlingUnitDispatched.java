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
package com.lms.warehouse.events;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A packed handling unit has left the warehouse: the hand-off to transport.
 *
 * <p>Carries what is in it, because once dispatched the unit's contents are no
 * longer warehouse stock and cannot be looked up as such. Exactly one of
 * {@code outboundOrderId} and {@code asnId} is set, depending on whether the
 * unit was packed from a pick or filled straight from the dock.
 *
 * <p>{@code destinationTerminalId}, {@code consigneePartnerId},
 * {@code grossWeightKg} and {@code volumeM3} were added for planning, which
 * turns a unit handed to transport into a consignment. They are set together
 * when the dispatch said where the unit is going and what it weighed, and all
 * null when it left some other way; {@code volumeM3} may be null on its own
 * when nobody measured it.
 */
public record HandlingUnitDispatched(
        UUID tenantId,
        UUID handlingUnitId,
        String huNo,
        UUID warehouseId,
        UUID outboundOrderId,
        UUID asnId,
        String shipTo,
        String dispatchRef,
        List<Content> contents,
        Instant dispatchedAt,
        UUID destinationTerminalId,
        UUID consigneePartnerId,
        BigDecimal grossWeightKg,
        BigDecimal volumeM3) {

    public HandlingUnitDispatched {
        contents = List.copyOf(contents);
    }

    /** Whether the unit was handed to the platform's own transport planning. */
    public boolean isHandedToTransport() {
        return destinationTerminalId != null && consigneePartnerId != null
                && grossWeightKg != null;
    }

    /** One SKU and lot inside the unit. */
    public record Content(UUID skuId, String skuCode, String lotNo, int quantity) {
    }
}
