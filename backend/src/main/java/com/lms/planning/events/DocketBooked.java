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
package com.lms.planning.events;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * A part-load shipment has been given a docket and can be billed to its
 * customer.
 *
 * <p>One event per docket rather than one per order, because each docket is
 * priced, billed and disputed on its own. A listener that has to unpick a list
 * to stay idempotent per item is a listener that will one day be idempotent
 * per list instead.
 *
 * <p>Carries the raw measurements -- dead weight and volume -- rather than a
 * chargeable weight. What a docket is chargeable at depends on the rate card's
 * volumetric conversion, which is a commercial term that belongs to whoever
 * prices it, not to planning.
 *
 * @param deliveryTerminalId the consignee's site, where the docket is finally
 *                           delivered from the destination hub. What decides
 *                           whether delivery is out of the delivery area.
 */
public record DocketBooked(
        UUID tenantId,
        UUID docketId,
        String docketNo,
        UUID orderId,
        UUID customerPartnerId,
        UUID consigneePartnerId,
        UUID originHubTerminalId,
        UUID destinationHubTerminalId,
        UUID deliveryTerminalId,
        BigDecimal deadWeightKg,
        BigDecimal volumeM3,
        Instant bookedAt) {
}
