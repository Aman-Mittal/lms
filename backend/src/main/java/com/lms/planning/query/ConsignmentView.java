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
package com.lms.planning.query;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** A consignment -- or, for a part load, a docket -- as a tracking screen shows it. */
public record ConsignmentView(
        UUID id,
        String trackingRef,
        String status,
        /** Null for a consignment received from a warehouse hand-off. */
        UUID orderId,
        String orderNo,
        String consigneeName,
        String destinationTerminalCode,
        BigDecimal totalDeadWeightKg,
        BigDecimal totalVolumeM3,
        BigDecimal chargeableWeightKg,
        boolean containsHazmat,
        String materialClasses,
        String loadNo,
        /** LTL consignments are dockets, and carry the hub pair below. */
        String freightMode,
        String originHubCode,
        String destinationHubCode,
        /**
         * The warehouse handling unit carried, for a consignment received from
         * a warehouse hand-off; {@code orderId} is then null.
         */
        UUID handlingUnitId,
        String handlingUnitNo,
        Instant createdAt) {
}
