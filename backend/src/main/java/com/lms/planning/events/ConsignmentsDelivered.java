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
 * Consignments have reached their consignee.
 *
 * <p>Announced whatever made it so: the trip carrying them completing at the
 * consignee, or a confirmed proof of delivery arriving first. One event per
 * movement, as {@link ConsignmentsCancelled} is, so a listener handles a list
 * and makes each item idempotent on its own. A consignment is announced once:
 * once DELIVERED, nothing moves it again.
 *
 * @param loadId          the load that carried them, or null when a proof
 *                        delivered one consignment on its own
 * @param consignmentIds  every consignment delivered by this movement
 * @param trackingRefs    their references, in the same order
 * @param trigger         TRIP_COMPLETED or PROOF_OF_DELIVERY
 */
public record ConsignmentsDelivered(
        UUID tenantId,
        UUID loadId,
        List<UUID> consignmentIds,
        List<String> trackingRefs,
        String trigger,
        Instant deliveredAt) {

    /** Delivered because the trip carrying them completed at the consignee. */
    public static final String TRIP_COMPLETED = "TRIP_COMPLETED";
    /** Delivered because a confirmed proof of delivery arrived. */
    public static final String PROOF_OF_DELIVERY = "PROOF_OF_DELIVERY";

    public ConsignmentsDelivered {
        consignmentIds = List.copyOf(consignmentIds);
        trackingRefs = List.copyOf(trackingRefs);
    }
}
