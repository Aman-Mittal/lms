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
package com.lms.sourcing.query;

import java.time.Instant;
import java.util.UUID;

/**
 * One rung of the cascade.
 *
 * <p>The trail these form is what answers "why did this load go to the rank-3
 * vendor at twice the rate", which is asked when the invoice arrives rather
 * than while the allocation is running.
 */
public record OfferView(
        UUID id,
        int rankNo,
        UUID vendorPartnerId,
        String vendorName,
        Instant offeredAt,
        Instant respondsBy,
        String outcome,
        Instant respondedAt) {
}
