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
 * Consignments have been cancelled, because their order was.
 *
 * <p>One event per cancellation rather than one per consignment: cancelling an
 * order of four hundred consignments is one fact, and one event per
 * consignment would put four hundred rows through the event registry for the
 * sake of listeners that care about a few. The consequence is that a listener
 * handles a list, so it must make each item idempotent on its own.
 *
 * <p>The counterpart of {@link DocketBooked}: finance raised a receivable when
 * a docket was booked, and cancels it on this.
 *
 * @param consignmentIds every consignment cancelled
 * @param docketIds      the part-load dockets among them (a docket is a
 *                       consignment, so these are also in {@code consignmentIds});
 *                       empty for a full-truckload order
 */
public record ConsignmentsCancelled(
        UUID tenantId,
        UUID orderId,
        List<UUID> consignmentIds,
        List<UUID> docketIds,
        Instant cancelledAt) {

    public ConsignmentsCancelled {
        consignmentIds = List.copyOf(consignmentIds);
        docketIds = List.copyOf(docketIds);
    }
}
