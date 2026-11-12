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

import java.time.Instant;
import java.util.UUID;

/**
 * The header fields of an order that may be edited, as a whole.
 *
 * <p>A replacement, not a patch: every field is stated, so a null pickup means
 * "no requested pickup" and not "leave it alone". The order number, unit, source
 * and freight mode are not here because they never change.
 *
 * <p>{@code contractLaneId} is the lane the person chose, null to have the
 * contract's lane found when the order is validated; {@code loadType} the load
 * or vehicle type, null for none.
 */
public record OrderHeaderChange(
        UUID customerPartnerId,
        UUID originTerminalId,
        Instant requestedPickupAt,
        Instant requestedDeliveryAt,
        UUID originHubTerminalId,
        UUID destinationHubTerminalId,
        UUID contractLaneId,
        String loadType) {

    /** A header that names no lane and no load type. */
    public OrderHeaderChange(UUID customerPartnerId, UUID originTerminalId, Instant requestedPickupAt,
                             Instant requestedDeliveryAt, UUID originHubTerminalId,
                             UUID destinationHubTerminalId) {
        this(customerPartnerId, originTerminalId, requestedPickupAt, requestedDeliveryAt, originHubTerminalId,
                destinationHubTerminalId, null, null);
    }
}
