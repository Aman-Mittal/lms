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
package com.lms.statutory.query;

import java.time.Instant;
import java.util.UUID;

/**
 * An e-way bill with the consignment it covers.
 *
 * @param expiringSoon true while the sweep has flagged the live bill as close to lapsing
 * @param version      what a change is made against
 */
public record EwayBillView(
        UUID id,
        String billNo,
        String status,
        UUID consignmentId,
        String trackingRef,
        String consignmentStatus,
        UUID orgUnitId,
        Instant generatedAt,
        Instant validUntil,
        String vehicleNo,
        Instant vehicleUpdatedAt,
        Integer distanceKm,
        String source,
        int extensionCount,
        Instant cancelledAt,
        String cancelReason,
        boolean expiringSoon,
        Instant expiringFlaggedAt,
        Instant expiredAt,
        long version,
        Instant createdAt) {
}
