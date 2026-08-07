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

/** An allocation as the sourcing desk sees it. */
public record AllocationView(
        UUID id,
        UUID loadId,
        String loadNo,
        String status,
        String strategy,
        Integer currentRank,
        String pendingVendorName,
        Instant respondsBy,
        String awardedVendorName,
        Instant awardedAt,
        int offersMade,
        Instant createdAt) {
}
