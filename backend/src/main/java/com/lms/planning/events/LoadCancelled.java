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
 * A load was cancelled directly, before it left, and everything aboard was
 * released to be planned again.
 *
 * <p>Distinct from the cancellation an order's cancellation cascades into,
 * which cancels the consignments themselves and is announced as
 * {@link ConsignmentsCancelled}. Here the consignments live on.
 *
 * @param previousStatus      DRAFT, PLANNED or AWARDED
 * @param releasedConsignmentIds the consignments that were aboard, now off it
 */
public record LoadCancelled(
        UUID tenantId,
        UUID loadId,
        String loadNo,
        String previousStatus,
        List<UUID> releasedConsignmentIds,
        String reason,
        Instant occurredAt) {

    public LoadCancelled {
        releasedConsignmentIds = List.copyOf(releasedConsignmentIds);
    }
}
