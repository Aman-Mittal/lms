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
import java.util.UUID;

/**
 * A vendor has taken the load. Execution can now raise a trip against it.
 *
 * @param rankNo which rank finally accepted -- the number that tells a
 *               commercial team whether their preferred vendors are actually
 *               taking the work they are contracted for
 */
public record LoadAwarded(
        UUID tenantId,
        UUID loadId,
        UUID allocationId,
        UUID vendorPartnerId,
        int rankNo,
        Instant occurredAt) {
}
