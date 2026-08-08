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
package com.lms.sourcing.events;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Every eligible vendor has declined or let the offer lapse.
 *
 * <p>Published so that somebody is told. This is the state that needs a human:
 * the load is planned, the freight exists, and nobody has agreed to move it.
 * Silence here is how a load sits until a customer rings to ask where it is.
 */
public record AllocationExhausted(
        UUID tenantId,
        UUID loadId,
        UUID allocationId,
        List<UUID> vendorsAsked,
        Instant occurredAt) {

    public AllocationExhausted {
        vendorsAsked = List.copyOf(vendorsAsked);
    }
}
