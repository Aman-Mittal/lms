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
import java.util.UUID;

/**
 * One consignment has been cancelled on its own, by a planner or because the
 * order line it carried was cancelled -- as opposed to with its whole order,
 * which is announced as the order's cancellation.
 *
 * <p>Published alongside {@link ConsignmentsCancelled}, which every listener
 * that winds up cancelled freight (finance, for a docket's receivable) already
 * handles; this one names the consignment and the load it came off, for those
 * who follow one consignment.
 *
 * @param loadId the load it was taken off; null when it was on none
 * @param reason why, as the planner gave it; null when none was given
 */
public record ConsignmentCancelled(
        UUID tenantId,
        UUID consignmentId,
        String trackingRef,
        UUID orderId,
        boolean docket,
        UUID loadId,
        String reason,
        Instant cancelledAt) {
}
