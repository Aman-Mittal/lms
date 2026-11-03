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
package com.lms.tracking.command.domain;

import java.time.Instant;
import java.util.UUID;

import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.relational.core.mapping.Table;

/** One tracking link. The token itself is never stored, only its hash. */
@Table("tracking_link")
public record TrackingLink(
        @Id UUID id,
        UUID tenantId,
        UUID consignmentId,
        String tokenHash,
        Instant expiresAt,
        Instant revokedAt,
        @Version Long version,
        Instant createdAt) {

    public static TrackingLink issue(UUID tenantId, UUID consignmentId, String tokenHash,
                                     Instant now, Instant expiresAt) {
        return new TrackingLink(UUID.randomUUID(), tenantId, consignmentId, tokenHash, expiresAt,
                null, null, now);
    }

    public TrackingLink revoked(Instant now) {
        return new TrackingLink(id, tenantId, consignmentId, tokenHash, expiresAt, now, version,
                createdAt);
    }

    public boolean isRevoked() {
        return revokedAt != null;
    }
}
