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
        Instant dispatchedAt) {

    public HandlingUnitDispatched {
        contents = List.copyOf(contents);
    }

    /** One SKU and lot inside the unit. */
    public record Content(UUID skuId, String skuCode, String lotNo, int quantity) {
    }
}
