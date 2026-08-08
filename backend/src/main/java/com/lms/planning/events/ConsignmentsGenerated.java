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
 * An order's demand has been turned into trackable consignments.
 *
 * <p>What downstream contexts wait for: a consignment is the first artefact a
 * customer can be given a reference for.
 */
public record ConsignmentsGenerated(
        UUID tenantId,
        UUID orderId,
        List<UUID> consignmentIds,
        List<String> trackingRefs,
        Instant occurredAt) {

    public ConsignmentsGenerated {
        consignmentIds = List.copyOf(consignmentIds);
        trackingRefs = List.copyOf(trackingRefs);
    }
}
