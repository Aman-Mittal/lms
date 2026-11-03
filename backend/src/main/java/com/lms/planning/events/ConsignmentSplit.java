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

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A consignment too big for one vehicle has been replaced by parts, each its
 * own full truckload under its own lorry receipt.
 *
 * <p>The split consignment keeps its reference and carries nothing from now
 * on; whoever tracks it follows its parts.
 */
public record ConsignmentSplit(
        UUID tenantId,
        UUID consignmentId,
        String trackingRef,
        /** Null for a consignment not cut from an order; today every split one is. */
        UUID orderId,
        List<UUID> partIds,
        List<String> partTrackingRefs,
        Instant occurredAt) {

    public ConsignmentSplit {
        partIds = List.copyOf(partIds);
        partTrackingRefs = List.copyOf(partTrackingRefs);
    }
}
